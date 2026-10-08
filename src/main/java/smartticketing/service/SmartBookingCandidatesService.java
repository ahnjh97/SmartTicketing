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
    private SmartBookingSummaryCache summaries;
    private org.springframework.transaction.support.TransactionTemplate creationTransaction;
    @org.springframework.beans.factory.annotation.Autowired
    void configure(org.springframework.transaction.PlatformTransactionManager transactions, SmartBookingSummaryCache summaries) {
        this.summaries=summaries;
        creationTransaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        creationTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public static class CandidateChanged extends RuntimeException {}

    public SmartBookingCandidatesService(EntityManager em, BookingGroupService groups, BookingHoldService holds,
            BookingWaitingService waiting, BookingPaymentService payments, BookingIdempotency operations) {
        this.em=em; this.groups=groups; this.holds=holds; this.waiting=waiting; this.payments=payments; this.operations=operations;
    }

    public record Candidate(Long groupId, String kind, SeatPosition zone, Long showtimeId,
            String theaterName, String screenName, OffsetDateTime startTime, OffsetDateTime endTime,
            BookingGroupStatus status, WaitingResponse waiting, PaymentResponse payment, int preferenceRank, String movieTitle, int partySize) {}
    public record Candidates(List<Candidate> candidates, List<List<Long>> batches) {
        public Candidates(List<Candidate> candidates) { this(candidates, List.of()); }
    }
    public record Created(List<Long> groupIds, Candidates initial) {}
    private record Option(Showtime show, SeatPosition zone, int preference, int theater, long ahead, boolean available, List<Long> seatIds) {}
    private record Selected(String kind, Option option) {}

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public BookingResult create(Long userId, String key, CreateBookingGroupRequest request) {
        if(creationTransaction==null)return createAttempt(userId,key,request,false);
        for(int attempt=0;attempt<3;attempt++) {
            boolean fresh=attempt>0;
            try { return creationTransaction.execute(status->createAttempt(userId,key,request,fresh)); }
            catch(CandidateChanged changed) { if(attempt==2)throw new ResponseStatusException(HttpStatus.CONFLICT,"좌석 상태가 변경되었습니다. 다시 시도해주세요."); }
        }
        throw new IllegalStateException();
    }

    private BookingResult createAttempt(Long userId, String key, CreateBookingGroupRequest request, boolean fresh) {
        BookingIdempotency.key(key); holds.lockBookingUser(userId); holds.requireUser(userId);
        if (request == null || request.entryPoint() == BookingEntryPoint.THEATER_NORMAL)
            throw new IllegalArgumentException("스마트예매 조건이 필요합니다.");
        return operations.execute(userId, BookingOperationType.CREATE_SMART_CANDIDATES, key, request, holds.now(), () -> {
            var template = groups.build(userId, request);
            var options = options(template, fresh);
            if (options.isEmpty()) throw new BookingRejection(409, "NO_CANDIDATES", "조건에 맞는 후보가 없습니다. 시간·극장·인원을 변경해주세요.");

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
            // Only the chosen shows are locked. A changed snapshot rolls back the entire
            // attempt before any drafts or queue numbers are written, then retries fresh.
            var selectedShows=selected.stream().map(s->s.option().show()).distinct().sorted(Comparator.comparing(Showtime::getId)).toList();
            for(var show:selectedShows) em.refresh(show,LockModeType.PESSIMISTIC_WRITE);
            for(var show:selectedShows) {
                var inventory=holds.lockInventory(show.getId());
                var queues=em.createQuery("select q from WaitingQueue q where q.showtime.id=:s and q.status=:state order by q.id",WaitingQueue.class)
                        .setParameter("s",show.getId()).setParameter("state",QueueStatus.WAITING).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                var counts=new EnumMap<SeatPosition,Long>(SeatPosition.class);
                for(var zone:SeatPosition.values())counts.put(zone,queues.stream().filter(q->q.getSeatZone()==null || q.getSeatZone()==zone).count());
                var checked=summarize(show,inventory,counts,template.getPartySize());
                try { BookingHoldService.validateShow(show,holds.now()); BookingAudiencePolicy.revalidate(template,show.getStartTime().toLocalDate()); }
                catch(BookingRejection changed) { throw new CandidateChanged(); }
                for(var choice:selected) if(choice.option().show().getId().equals(show.getId())) {
                    var previous=choice.option();
                    var zone=checked.zones().stream().filter(z->z.zone()==previous.zone()).findFirst().orElseThrow();
                    boolean available=zone.ahead()==0&&!zone.seats().isEmpty();
                    if(!zone.capacity() || previous.ahead()!=zone.ahead() || previous.available()!=available
                            || !previous.seatIds().equals(zone.seats()))throw new CandidateChanged();
                }
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
                draft.setCreatedAt(template.getCreatedAt()); draft.setUpdatedAt(template.getUpdatedAt());
                drafts.add(draft);
            }
            drafts.forEach(em::persist);
            for (int index = 0; index < drafts.size(); index++) {
                var draft = drafts.get(index);
                var candidate = selected.get(index);
                if (candidate.equals(immediate)) {
                    // 앞선 후보의 대기는 유지하고, 이 즉시선점 후보에는 대기번호·알림을 만들지 않는다.
                    waiting.registerAndHold(userId, draft.getId(), candidate.option().show().getId(), candidate.option().seatIds());
                    continue;
                }
                var result = waiting.register(userId, draft.getId(), UUID.randomUUID().toString(),
                        new WaitingRequest(List.of(draft.getSelectedShowtime().getId())));
                if (result.status() >= 400) throw new IllegalStateException("후보 등록이 변경되었습니다. 같은 요청으로 다시 시도해주세요.");
            }
            if (immediate != null) {
                NotificationService.clearQueueTurns(em, drafts.stream().map(BookingRequestGroup::getId).toList());
            }
            em.flush();
            var ids=drafts.stream().map(BookingRequestGroup::getId).toList();
            var view=get(userId,null);
            var batches=new ArrayList<List<Long>>(view.batches());batches.addFirst(ids);
            return new Created(ids,new Candidates(view.candidates(),batches));
        });
    }

    private static void choose(List<Selected> selected, List<Option> remaining, String kind, Comparator<Option> order) {
        if (remaining.isEmpty()) return;
        var option=remaining.stream().min(order).orElseThrow(); selected.add(new Selected(kind,option));
        remaining.remove(option);
    }

    private List<Option> options(BookingRequestGroup template, boolean fresh) {
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
                select s from Showtime s join fetch s.screen c join fetch c.theater join fetch s.movie where s.movie.id=:movie and s.startTime>:now and s.startTime>=:from
                """ + (movie?" and s.startTime<=:until and s.screen.theater.id in :theaters":" and s.startTime<:until and s.id=:show")
                + " order by s.id", Showtime.class).setParameter("movie",template.getMovie().getId())
                .setParameter("now",holds.now()).setParameter("from",from).setParameter("until",until);
        if(movie) query.setParameter("theaters",theaterIds); else query.setParameter("show",template.getSelectedShowtime().getId());
        var shows=query.getResultList();
        var preferences=new ArrayList<>(new LinkedHashSet<>(template.getSeatPreferences()));
        for(var zone:List.of(SeatPosition.MIDDLE_MIDDLE,SeatPosition.MIDDLE_REAR,SeatPosition.MIDDLE_FRONT,
                SeatPosition.SIDE_MIDDLE,SeatPosition.SIDE_REAR,SeatPosition.SIDE_FRONT)) if(!preferences.contains(zone)) preferences.add(zone);
        var result=new ArrayList<Option>();
        var ids=shows.stream().map(Showtime::getId).toList();
        var cached=summaries==null||fresh?new HashMap<Long,SmartBookingSummaryCache.Snapshot>():summaries.read(ids,template.getPartySize());
        var missing=ids.stream().filter(id->!cached.containsKey(id)).toList();
        var inventories=new HashMap<Long,List<ShowtimeSeat>>();
        var counts=new HashMap<Long,Map<SeatPosition,Long>>();
        var computed=new HashMap<Long,SmartBookingSummaryCache.Snapshot>();
        // Scalar inventory snapshots avoid putting stale ShowtimeSeat entities into the
        // persistence context before the final locking read. Fetch all shows in batches.
        for(int start=0;start<missing.size();start+=100) {
            var chunk=missing.subList(start,Math.min(start+100,missing.size()));
            var rows=em.createQuery("select i.showtime.id, s, i.status, i.reservation.id, i.holdExpiredAt from ShowtimeSeat i join i.seat s join fetch s.screen where i.showtime.id in :ids order by i.showtime.id,i.id",Object[].class)
                    .setParameter("ids",chunk).getResultList();
            for(var row:rows) {
                var seat=new ShowtimeSeat();seat.setSeat((Seat)row[1]);seat.setStatus((SeatStatus)row[2]);
                if(row[3]!=null)seat.setReservation(em.getReference(Reservation.class,(Long)row[3]));
                seat.setHoldExpiredAt((LocalDateTime)row[4]);
                inventories.computeIfAbsent((Long)row[0],ignored->new ArrayList<>()).add(seat);
            }
            var queues=em.createQuery("select q.showtime.id,q.seatZone,count(q) from WaitingQueue q where q.showtime.id in :ids and q.status=:state group by q.showtime.id,q.seatZone",Object[].class)
                    .setParameter("ids",chunk).setParameter("state",QueueStatus.WAITING).getResultList();
            for(var row:queues) {
                var map=counts.computeIfAbsent((Long)row[0],ignored->new EnumMap<>(SeatPosition.class));
                for(var zone:SeatPosition.values())if(row[1]==null||row[1]==zone)map.merge(zone,(Long)row[2],Long::sum);
            }
        }
        for(var show:shows) {
            try { BookingHoldService.validateShow(show,holds.now()); BookingAudiencePolicy.revalidate(template,show.getStartTime().toLocalDate()); }
            catch(BookingRejection excluded) { continue; }
            var snapshot=cached.get(show.getId());
            if(snapshot==null) {
                snapshot=summarize(show,inventories.getOrDefault(show.getId(),List.of()),counts.getOrDefault(show.getId(),Map.of()),template.getPartySize());
                computed.put(show.getId(),snapshot);
            }
            for(var zone:preferences) {
                var summary=snapshot.zones().stream().filter(z->z.zone()==zone).findFirst().orElseThrow();
                if(!summary.capacity())continue;
                result.add(new Option(show,zone,preferences.indexOf(zone),Math.max(0,theaterIds.indexOf(show.getScreen().getTheater().getId())),summary.ahead(),summary.ahead()==0&&!summary.seats().isEmpty(),summary.seats()));
            }
        }
        if(summaries!=null)summaries.putAll(template.getPartySize(),computed);
        return result;
    }

    private SmartBookingSummaryCache.Snapshot summarize(Showtime show,List<ShowtimeSeat> inventory,Map<SeatPosition,Long> counts,int party) {
        var capacity=inventory.stream().filter(i->i.getStatus()!=SeatStatus.BLOCKED).map(i->{
            var free=new ShowtimeSeat();free.setSeat(i.getSeat());free.setStatus(SeatStatus.AVAILABLE);return free;
        }).toList();
        var structural=SmartSeatCandidates.prepare(capacity,show.getScreen().getId());
        var current=SmartSeatCandidates.prepare(inventory,show.getScreen().getId());
        var zones=new ArrayList<SmartBookingSummaryCache.Zone>();
        var order=SmartSeatCandidates.priorityOrder().thenComparingDouble(SmartSeatCandidates.Block::centerDistance)
                .thenComparing(SmartSeatCandidates.Block::row).thenComparing(SmartSeatCandidates.Block::segment)
                .thenComparingInt(SmartSeatCandidates.Block::firstPosition);
        for(var zone:SeatPosition.values()) {
            // Within a required zone every seat has the same preference rank.
            var prefs=List.of(zone);
            var blocks=SmartSeatCandidates.analyze(current,party,prefs,zone).blocks();
            boolean fits=!blocks.isEmpty() || !SmartSeatCandidates.analyze(structural,party,prefs,zone).blocks().isEmpty();
            zones.add(new SmartBookingSummaryCache.Zone(zone,fits,counts.getOrDefault(zone,0L),blocks.stream().min(order).map(SmartSeatCandidates.Block::seatIds).orElse(List.of())));
        }
        return new SmartBookingSummaryCache.Snapshot(System.currentTimeMillis(),zones);
    }

    @Transactional(readOnly=true)
    public Candidates get(Long userId, Long selectedId) {
        if (selectedId != null) {
            var selected=em.find(BookingRequestGroup.class,selectedId);
            if(selected==null || !selected.getUser().getId().equals(userId) || selected.getEntryPoint()==BookingEntryPoint.THEATER_NORMAL)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"스마트예매 후보를 찾을 수 없습니다.");
        }
        // Status reads use snapshots without taking allocation locks.
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
        var children=ids.isEmpty()?List.<BookingRequestGroup>of():em.createQuery("""
                select g from BookingRequestGroup g join fetch g.movie left join fetch g.seatPreferences
                left join fetch g.selectedShowtime s left join fetch s.screen c left join fetch c.theater
                where g.id in :ids order by g.id
                """,BookingRequestGroup.class).setParameter("ids",ids).getResultList();
        holds.requireUser(userId);
        var queueViews=waiting.snapshots(children);
        var latest=ids.isEmpty()?List.<Reservation>of():em.createQuery("""
                select r from Reservation r join fetch r.showtime s join fetch s.screen c join fetch c.theater join fetch s.movie
                where r.id in (select max(r2.id) from Reservation r2 where r2.requestGroup.id in :ids group by r2.requestGroup.id)
                """,Reservation.class).setParameter("ids",ids).getResultList();
        var latestByGroup=new HashMap<Long,Reservation>();
        latest.forEach(r -> latestByGroup.put(r.getRequestGroup().getId(),r));
        var items=new ArrayList<Candidate>();
        for(var g:children) {
            var queues=queueViews.get(g.getId());
            var reservation=latestByGroup.get(g.getId());
            var payment=reservation==null?null:payments.snapshot(userId,reservation);
            boolean active=queues.items().stream().anyMatch(q->q.status()==QueueStatus.WAITING || q.status()==QueueStatus.PAUSED)
                    || payment!=null && payment.reservation().status()==ReservationStatus.PENDING;
            if(!active && !Objects.equals(selectedId,g.getId())) continue;
            var show=g.getSelectedShowtime();
            if(show==null && reservation!=null) show=reservation.getShowtime();
            if(show==null && !queues.items().isEmpty()) show=em.find(Showtime.class,queues.items().getFirst().showtimeId());
            if(show==null) continue;
            items.add(new Candidate(g.getId(),g.getCandidateKind()==null?"DIRECT":g.getCandidateKind(),g.getCandidateZone(),show.getId(),show.getScreen().getTheater().getName(),
                    show.getScreen().getName(),offset(show.getStartTime()),offset(show.getEndTime()),queues.groupStatus(),queues,payment,
                    preferenceRank(g),g.getMovie().getTitle(),g.getPartySize()));
        }
        var activeIds = new HashSet<>(items.stream().map(Candidate::groupId).toList());
        var batches = new ArrayList<List<Long>>();
        if (!activeIds.isEmpty()) {
            // Return only matching group IDs, never every historical response/initial view.
            var records = em.createNativeQuery("""
                    select o.id, member.group_id from booking_operations o
                    join json_table(coalesce(o.response_body,'{}'), '$.groupIds[*]'
                        columns (ordinality for ordinality, group_id bigint path '$')) member
                    where o.user_id=:user and o.operation_type=:type and o.status=:status
                    and member.group_id in (:ids) order by o.id desc, member.ordinality
                    """, Object[].class).setParameter("user", userId)
                    .setParameter("type", BookingOperationType.CREATE_SMART_CANDIDATES.name())
                    .setParameter("status", BookingOperationStatus.COMPLETED.name()).setParameter("ids",activeIds).getResultList();
            var byOperation = new LinkedHashMap<Long,List<Long>>();
            for (var rawRecord : records) {
                var record = (Object[]) rawRecord;
                byOperation.computeIfAbsent(((Number)record[0]).longValue(),ignored -> new ArrayList<>()).add(((Number)record[1]).longValue());
            }
            batches.addAll(byOperation.values());
        }
        return new Candidates(items, batches);
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
