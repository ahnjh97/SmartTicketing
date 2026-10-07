package smartticketing.service;

import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.*;
import java.util.*;

/** Independent smart bookings. Recommendation batches have no persisted parent. */
@Service
@Transactional
public class SmartBookingCandidatesService {
    private final EntityManager em;
    private final BookingGroupService groups;
    private final BookingHoldService holds;
    private final BookingWaitingService waiting;
    private final BookingPaymentService payments;
    private final BookingIdempotency operations;

    public SmartBookingCandidatesService(EntityManager em, BookingGroupService groups, BookingHoldService holds,
            BookingWaitingService waiting, BookingPaymentService payments, BookingIdempotency operations) {
        this.em=em; this.groups=groups; this.holds=holds; this.waiting=waiting; this.payments=payments; this.operations=operations;
    }

    public record Candidate(Long groupId, String kind, SeatPosition zone, Long showtimeId,
            String theaterName, String screenName, OffsetDateTime startTime, OffsetDateTime endTime,
            BookingGroupStatus status, WaitingResponse waiting, PaymentResponse payment, int preferenceRank, String movieTitle, int partySize) {}
    public record Candidates(List<Candidate> candidates) {}
    public record Created(List<Long> groupIds) {}
    private record Option(Showtime show, SeatPosition zone, int preference, int theater, long ahead, boolean available, List<Long> seatIds) {}
    private record Selected(String kind, Option option) {}

    public BookingResult create(Long userId, String key, CreateBookingGroupRequest request) {
        BookingIdempotency.key(key); holds.lockBookingUser(userId); holds.requireUser(userId);
        if (request == null || request.entryPoint() == BookingEntryPoint.THEATER_NORMAL)
            throw new IllegalArgumentException("스마트예매 조건이 필요합니다.");
        return operations.execute(userId, BookingOperationType.CREATE_SMART_CANDIDATES, key, request, holds.now(), () -> {
            var template = groups.build(userId, request);
            var options = options(template);
            if (options.isEmpty()) throw new BookingRejection(409, "NO_CANDIDATES", "조건에 맞는 새 후보가 없습니다. 기존 대기를 확인하거나 시간·극장·인원을 변경해주세요.");

            var tie = Comparator.comparingInt(Option::theater).thenComparing(o -> o.show().getStartTime())
                    .thenComparing(o -> o.show().getId()).thenComparing(Option::zone);
            var preferred = Comparator.comparingInt(Option::preference).thenComparing(o -> !o.available())
                    .thenComparingLong(Option::ahead).thenComparing(tie);
            var fast = Comparator.comparing((Option o) -> !o.available()).thenComparingLong(Option::ahead)
                    .thenComparingInt(Option::preference).thenComparing(tie);
            // Preference steps and current queue load are both meaningful; this is not a wait-time estimate.
            var balanced = Comparator.comparingLong((Option o) -> o.preference()*2L + Math.min(o.ahead(),12) + (o.available()?0:2))
                    .thenComparing(preferred);
            var remaining = new ArrayList<>(options);
            var selected = new ArrayList<Selected>();
            choose(selected, remaining, "PREFERRED", preferred);
            // Priority is PREFERRED -> BALANCED -> FAST. Stop at the first bookable
            // option; only higher-priority unavailable options remain as waits.
            if (!selected.getLast().option().available()) {
                choose(selected, remaining, "BALANCED", balanced);
            }
            if (!selected.getLast().option().available()) {
                choose(selected, remaining, "FAST", fast);
            }
            var immediate = selected.stream().filter(s -> s.option().available()).findFirst().orElse(null);
            // Validate every draft before writes: a rejected request cannot leave a partial bundle.
            var drafts = new ArrayList<BookingRequestGroup>();
            for (var selection : selected) {
                var option = selection.option();
                var draft = groups.build(userId, new CreateBookingGroupRequest(BookingEntryPoint.THEATER_SMART,
                        request.movieId(), CinemaDay.date(option.show().getStartTime()), request.partySize(),
                        null, null, option.show().getId(), request.audience()));
                draft.setCandidateKind(selection.kind()); draft.setCandidateZone(option.zone());
                // 같은 스마트예매 요청에서 생성된 후보들은 동일한 createdAt을 공유한다.
                // 대기 후보의 승급 시 다른 후보가 이미 즉시 선점 중인지 식별하는 배치 기준으로 사용한다.
                draft.setCreatedAt(template.getCreatedAt()); draft.setUpdatedAt(template.getUpdatedAt());
                drafts.add(draft);
            }
            drafts.forEach(em::persist);
            for (int index = 0; index < drafts.size(); index++) {
                var draft = drafts.get(index);
                var candidate = selected.get(index);
                if (candidate.equals(immediate)) {
                    // Immediate holds retain the same zone queue ordering.
                    waiting.registerAndHold(userId, draft.getId(), candidate.option().show().getId(), candidate.option().seatIds());
                    continue;
                }
                var result = waiting.register(userId, draft.getId(), UUID.randomUUID().toString(),
                        new WaitingRequest(List.of(draft.getSelectedShowtime().getId())));
                if (result.status() >= 400) throw new IllegalStateException("후보 등록이 변경되었습니다. 같은 요청으로 다시 시도해주세요.");
            }
            return new Created(drafts.stream().map(BookingRequestGroup::getId).toList());
        });
    }

    private static void choose(List<Selected> selected, List<Option> remaining, String kind, Comparator<Option> order) {
        if (remaining.isEmpty()) return;
        var option=remaining.stream().min(order).orElseThrow(); selected.add(new Selected(kind,option));
        remaining.removeIf(other -> other.show().getId().equals(option.show().getId()));
    }

    private List<Option> options(BookingRequestGroup template) {
        var theaterIds=template.getTheaterPreferences().stream().map(Theater::getId).toList();
        if (template.getEntryPoint()==BookingEntryPoint.MOVIE_SMART && theaterIds.isEmpty())
            throw new BookingRejection(409,"NO_THEATER_SCOPE","선호극장을 설정해주세요.");
        var from=CinemaDay.start(template.getViewingDate()); var until=from.plusDays(1);
        boolean movie=template.getEntryPoint()==BookingEntryPoint.MOVIE_SMART;
        if(movie) {
            from=CinemaDay.time(template.getViewingDate(),template.getStartTimeFrom());
            until=CinemaDay.time(template.getViewingDate(),template.getStartTimeTo());
            if(!until.isAfter(from)) until=until.plusDays(1);
        }
        var query=em.createQuery("""
                select s from Showtime s where s.movie.id=:movie and s.startTime>:now and s.startTime>=:from
                """ + (movie?" and s.startTime<=:until and s.screen.theater.id in :theaters":" and s.startTime<:until and s.id=:show")
                + " order by s.id", Showtime.class).setParameter("movie",template.getMovie().getId())
                .setParameter("now",holds.now()).setParameter("from",from).setParameter("until",until);
        if(movie) query.setParameter("theaters",theaterIds); else query.setParameter("show",template.getSelectedShowtime().getId());
        var shows=query.getResultList();
        // Lock shows in a stable order, then take current inventory/queue snapshots.
        for(var show:shows) em.refresh(show,LockModeType.PESSIMISTIC_WRITE);
        var preferences=new ArrayList<>(new LinkedHashSet<>(template.getSeatPreferences()));
        for(var zone:List.of(SeatPosition.MIDDLE_MIDDLE,SeatPosition.MIDDLE_REAR,SeatPosition.MIDDLE_FRONT,
                SeatPosition.SIDE_MIDDLE,SeatPosition.SIDE_REAR,SeatPosition.SIDE_FRONT)) if(!preferences.contains(zone)) preferences.add(zone);
        var result=new ArrayList<Option>();
        boolean duplicateShow = false;
        for(var show:shows) {
            try { BookingHoldService.validateShow(show,holds.now()); BookingAudiencePolicy.revalidate(template,show.getStartTime().toLocalDate()); }
            catch(BookingRejection excluded) { continue; }
            var inventory=holds.lockInventory(show.getId());
            // Structural capacity must fit the party even when every currently usable seat is occupied.
            var capacity=inventory.stream().filter(i->i.getStatus()!=SeatStatus.BLOCKED).map(i->{
                var free=new ShowtimeSeat(); free.setSeat(i.getSeat()); free.setStatus(SeatStatus.AVAILABLE); return free;
            }).toList();
            var queues=em.createQuery("select q from WaitingQueue q where q.showtime.id=:s and q.status in :states order by q.id",WaitingQueue.class)
                    .setParameter("s",show.getId()).setParameter("states",List.of(QueueStatus.WAITING,QueueStatus.PAUSED,QueueStatus.HOLDING))
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            if (queues.stream().anyMatch(q -> q.getUser().getId().equals(template.getUser().getId()))) {
                duplicateShow = true;
                continue;
            }
            for(var zone:preferences) {
                if(SmartSeatCandidates.analyze(capacity,show.getScreen().getId(),template.getPartySize(),preferences,zone).blocks().isEmpty()) continue;
                long ahead=queues.stream().filter(q->q.getStatus()==QueueStatus.WAITING && (q.getSeatZone()==null || q.getSeatZone()==zone)).count();
                var blocks=SmartSeatCandidates.analyze(inventory,show.getScreen().getId(),template.getPartySize(),preferences,zone).blocks();
                boolean available=ahead==0 && !blocks.isEmpty();
                var best = blocks.stream().min(SmartSeatCandidates.priorityOrder()
                        .thenComparingDouble(SmartSeatCandidates.Block::centerDistance)
                        .thenComparing(SmartSeatCandidates.Block::row).thenComparing(SmartSeatCandidates.Block::segment)
                        .thenComparingInt(SmartSeatCandidates.Block::firstPosition));
                result.add(new Option(show,zone,preferences.indexOf(zone),Math.max(0,theaterIds.indexOf(show.getScreen().getTheater().getId())),ahead,available,
                        best.map(SmartSeatCandidates.Block::seatIds).orElse(List.of())));
            }
        }
        if (result.isEmpty() && duplicateShow) BookingQueueLifecycle.rejectDuplicateShow();
        return result;
    }

    public Candidates get(Long userId, Long selectedId) {
        if (selectedId != null) {
            var selected=em.find(BookingRequestGroup.class,selectedId);
            if(selected==null || !selected.getUser().getId().equals(userId) || selected.getEntryPoint()==BookingEntryPoint.THEATER_NORMAL)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"스마트예매 후보를 찾을 수 없습니다.");
        }
        // Discover IDs without locking; acquire all groups in the same global order as allocation.
        var ids=em.createQuery("""
                select g.id from BookingRequestGroup g where g.user.id=:user and g.entryPoint<>:normal
                and (g.id=:selected or exists (select q.id from WaitingQueue q where q.requestGroup=g
                    and q.status in :queues and q.showtime.startTime>:now and q.showtime.status=:scheduled)
                    or exists (select h.id from BookingGroupHold h where h.requestGroup=g and h.expiresAt>:now
                        and h.reservation.status=:pending and h.reservation.showtime.startTime>:now)) order by g.id
                """,Long.class).setParameter("user",userId).setParameter("normal",BookingEntryPoint.THEATER_NORMAL)
                .setParameter("selected",selectedId).setParameter("queues",List.of(QueueStatus.WAITING,QueueStatus.PAUSED))
                .setParameter("now",holds.now()).setParameter("scheduled",ShowtimeStatus.SCHEDULED)
                .setParameter("pending",ReservationStatus.PENDING).getResultList();
        var children=new ArrayList<BookingRequestGroup>();
        for(var id:ids) { var g=holds.lockOwnedGroup(userId,id); em.refresh(g,LockModeType.PESSIMISTIC_WRITE); children.add(g); }
        var showIds=new TreeSet<Long>();
        for(var g:children) { if(g.getSelectedShowtime()!=null) showIds.add(g.getSelectedShowtime().getId()); showIds.addAll(BookingQueueLifecycle.showIds(em,g.getId())); }
        showIds.forEach(id->em.find(Showtime.class,id,LockModeType.PESSIMISTIC_WRITE));
        holds.requireUser(userId);
        var items=new ArrayList<Candidate>();
        for(var g:children) {
            var queues=waiting.get(userId,g.getId());
            var reservations=em.createQuery("select r from Reservation r where r.requestGroup.id=:g order by r.id desc",Reservation.class)
                    .setParameter("g",g.getId()).setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            var payment=reservations.isEmpty()?null:payments.get(userId,reservations.getFirst().getId());
            boolean active=queues.items().stream().anyMatch(q->q.status()==QueueStatus.WAITING || q.status()==QueueStatus.PAUSED)
                    || payment!=null && payment.reservation().status()==ReservationStatus.PENDING;
            if(!active && !Objects.equals(selectedId,g.getId())) continue;
            var show=g.getSelectedShowtime();
            if(show==null && !reservations.isEmpty()) show=reservations.getFirst().getShowtime();
            if(show==null && !queues.items().isEmpty()) show=em.find(Showtime.class,queues.items().getFirst().showtimeId());
            if(show==null) continue;
            items.add(new Candidate(g.getId(),g.getCandidateKind()==null?"DIRECT":g.getCandidateKind(),g.getCandidateZone(),show.getId(),show.getScreen().getTheater().getName(),
                    show.getScreen().getName(),offset(show.getStartTime()),offset(show.getEndTime()),g.getStatus(),queues,payment,
                    preferenceRank(g),g.getMovie().getTitle(),g.getPartySize()));
        }
        return new Candidates(items);
    }
    private static int preferenceRank(BookingRequestGroup group) {
        var preferences = new ArrayList<>(new LinkedHashSet<>(group.getSeatPreferences()));
        for (var zone : List.of(SeatPosition.MIDDLE_MIDDLE, SeatPosition.MIDDLE_REAR, SeatPosition.MIDDLE_FRONT,
                SeatPosition.SIDE_MIDDLE, SeatPosition.SIDE_REAR, SeatPosition.SIDE_FRONT))
            if (!preferences.contains(zone)) preferences.add(zone);
        return preferences.indexOf(group.getCandidateZone());
    }
    private static OffsetDateTime offset(LocalDateTime t) { return t.atOffset(ZoneOffset.ofHours(9)); }
}
