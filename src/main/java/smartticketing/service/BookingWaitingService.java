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
import static smartticketing.service.BookingHoldService.reject;

@Service
@Transactional
public class BookingWaitingService {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingPaymentService payments;
    private final BookingIdempotency operations;
    private static final List<QueueStatus> ACTIVE = List.of(QueueStatus.WAITING, QueueStatus.PAUSED, QueueStatus.HOLDING);
    private record Intent(Long groupId, List<Long> showtimeIds, SeatPosition seatZone, List<Long> seatIds) {}

    public BookingWaitingService(EntityManager em, BookingHoldService holds, BookingPaymentService payments, BookingIdempotency operations) {
        this.em = em; this.holds = holds; this.payments = payments; this.operations = operations;
    }

    // Internal smart orchestration only: caller holds inventory/queue locks and has
    // verified immediate availability. Register and acquire in the same transaction.
    void registerAndHold(Long user, Long group, Long show, List<Long> seats) {
        // 스마트예매에서 즉시 선점되는 후보는 대기번호 자체를 발급하지 않는다.
        try {
            holds.acquire(user, group, BookingHoldService.Source.SMART, show, seats);
        } catch (BookingRejection changed) {
            throw new IllegalStateException("좌석 확보 조건이 변경되었습니다. 같은 요청으로 다시 시도해주세요.", changed);
        }
    }

    public BookingResult register(Long user, Long id, String key, WaitingRequest request) {
        BookingIdempotency.key(key); holds.lockBookingUser(user); holds.requireUser(user);
        if (request == null || request.showtimeIds() == null || request.showtimeIds().isEmpty()
                || request.showtimeIds().stream().anyMatch(s -> s == null || s < 1))
            throw new IllegalArgumentException("대기할 회차를 선택해주세요.");
        var ids = request.showtimeIds().stream().distinct().sorted().toList();
        if (ids.size() != request.showtimeIds().size()) throw new IllegalArgumentException("같은 회차를 중복 신청할 수 없습니다.");
        var seatIds = request.seatIds() == null ? List.<Long>of() : BookingHoldService.normalizeSeats(request.seatIds());
        return operations.execute(user, BookingOperationType.REGISTER_WAITING, key, new Intent(id, ids, request.seatZone(), seatIds), holds.now(), () -> {
            var group = holds.lockOwnedGroup(user, id);
            if (group.getStatus() != BookingGroupStatus.ACTIVE) reject(409, "진행 중인 선점 또는 종료된 그룹에는 대기를 추가할 수 없습니다.");
            var shows = new TreeSet<>(BookingQueueLifecycle.showIds(em, id)); shows.addAll(ids);
            shows.forEach(s -> em.find(Showtime.class, s, LockModeType.PESSIMISTIC_WRITE));
            var existing = BookingQueueLifecycle.rows(em, id);
            if (existing.stream().anyMatch(q -> !shows.contains(q.getShowtime().getId())))
                throw new org.springframework.dao.TransientDataAccessResourceException("대기 회차가 변경되었습니다. 같은 요청으로 재시도해주세요.");
            if (!seatIds.isEmpty()) {
                if (group.getEntryPoint() != BookingEntryPoint.THEATER_NORMAL || ids.size() != 1 || request.seatZone() != null)
                    reject(400, "직접 선택한 좌석 대기는 일반예매의 한 회차에서만 가능합니다.");
                return manualSeats(group, ids.getFirst(), seatIds, existing);
            }
            if (request.seatZone() != null) {
                if (ids.size() != 1) reject(400, "구역 변경은 한 회차씩 신청해주세요.");
                var result = manualZone(group, ids.getFirst(), request.seatZone(), existing);
                if (group.getCandidateZone() != null) group.setCandidateZone(request.seatZone());
                return result;
            }
            var zone = group.getCandidateZone() != null ? group.getCandidateZone()
                    : group.getSeatPreferences().stream().findFirst().orElse(SeatPosition.MIDDLE_MIDDLE);
            var additions = new ArrayList<WaitingQueue>();
            for (var showId : ids) {
                // A terminal row is returned unchanged, never resurrected or renumbered.
                if (existing.stream().anyMatch(q -> q.getShowtime().getId().equals(showId))) continue;
                var show = em.find(Showtime.class, showId);
                validate(group, show);
                var duplicates = em.createQuery("""
                        select q from WaitingQueue q where q.user.id=:user and q.showtime.id=:show
                        and q.status in :statuses order by q.id
                        """, WaitingQueue.class).setParameter("user", user).setParameter("show", showId)
                        .setParameter("statuses", ACTIVE).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                if (!duplicates.isEmpty()) BookingQueueLifecycle.rejectDuplicateShow();
                // The show mutex serializes number issuance, including parallel groups of the same user.
                var numbers = em.createQuery("select q from WaitingQueue q where q.showtime.id=:s order by q.queueNumber desc", WaitingQueue.class)
                        .setParameter("s", showId).setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                int previous = numbers.isEmpty() ? 0 : numbers.getFirst().getQueueNumber();
                if (previous == Integer.MAX_VALUE) reject(409, "대기 번호를 더 발급할 수 없습니다.");
                var q = new WaitingQueue(); q.setUser(group.getUser()); q.setRequestGroup(group); q.setShowtime(show);
                q.setQueueNumber(previous + 1); q.setStatus(QueueStatus.WAITING);
                var zoneNumbers = em.createQuery("select q from WaitingQueue q where q.showtime.id=:s and q.seatZone=:z order by q.zoneQueueNumber desc", WaitingQueue.class)
                        .setParameter("s", showId).setParameter("z", zone).setMaxResults(1)
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                int last = zoneNumbers.isEmpty() ? 0 : zoneNumbers.getFirst().getZoneQueueNumber();
                if (last == Integer.MAX_VALUE) reject(409, "구역 대기 번호를 더 발급할 수 없습니다.");
                q.setSeatZone(zone); q.setZoneQueueNumber(nextZoneNumber(showId, zone, last));
                q.setCreatedAt(holds.now()); q.setUpdatedAt(holds.now()); additions.add(q);
            }
            additions.forEach(em::persist);
            return response(group);
        });
    }

    private WaitingResponse manualSeats(BookingRequestGroup group, Long showId, List<Long> ids, List<WaitingQueue> existing) {
        var show = em.find(Showtime.class, showId); validate(group, show);
        if (ids.size() != group.getPartySize()) reject(400, "관람 인원만큼 좌석을 선택해주세요.");
        var inventory = holds.lockInventory(showId);
        var selected = inventory.stream().filter(i -> ids.contains(i.getSeat().getId())).toList();
        if (selected.size() != ids.size() || selected.stream().anyMatch(i -> !i.getSeat().isActive()
                || !i.getSeat().getScreen().getId().equals(show.getScreen().getId()) || i.getStatus() == SeatStatus.BLOCKED))
            reject(400, "이 회차에서 이용 가능한 좌석을 선택해주세요.");
        var zones = new HashSet<SeatPosition>(); selected.forEach(i -> zones.add(i.getSeat().getSeatPosition()));
        if (zones.size() != 1 || zones.contains(null))
            throw new BookingRejection(400, "WAITING_SINGLE_ZONE_REQUIRED", "대기 좌석은 같은 구역 안에서 선택해주세요. 서로 다른 구역의 좌석을 함께 대기할 수 없습니다.");
        var row = existing.stream().filter(q -> q.getShowtime().getId().equals(showId)).findFirst().orElse(null);
        if (row != null && row.getStatus() != QueueStatus.WAITING) reject(409, "이미 종료되거나 확보된 대기입니다.");
        if (row != null && row.getRequestedSeatIds().equals(ids)) return response(group);
        var rows = em.createQuery("select q from WaitingQueue q where q.showtime.id=:s order by q.id", WaitingQueue.class)
                .setParameter("s", showId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (rows.stream().anyMatch(q -> q.getUser().getId().equals(group.getUser().getId())
                && !Objects.equals(q.getRequestGroup() == null ? null : q.getRequestGroup().getId(), group.getId())
                && ACTIVE.contains(q.getStatus())))
            BookingQueueLifecycle.rejectDuplicateShow();
        int last = rows.stream().mapToInt(WaitingQueue::getQueueNumber).max().orElse(0);
        if (last == Integer.MAX_VALUE) reject(409, "대기 번호를 더 발급할 수 없습니다.");
        SeatPosition zone = zones.iterator().next();
        if (row == null) { row = new WaitingQueue(); row.setUser(group.getUser()); row.setRequestGroup(group); row.setShowtime(show); row.setCreatedAt(holds.now()); }
        if (row.getSeatZone() != null) rememberZoneNumber(showId, row.getSeatZone(), row.getZoneQueueNumber());
        int zoneNumber = nextZoneNumber(showId, zone,
                rows.stream().filter(q -> q.getSeatZone() == zone).map(WaitingQueue::getZoneQueueNumber).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0));
        row.setQueueNumber(last + 1); row.setSeatZone(zone); row.setZoneQueueNumber(zoneNumber);
        row.getRequestedSeatIds().clear(); row.getRequestedSeatIds().addAll(ids);
        row.setStatus(QueueStatus.WAITING); row.setUpdatedAt(holds.now());
        if (row.getId() == null) em.persist(row);
        return response(group);
    }

    private WaitingResponse manualZone(BookingRequestGroup group, Long showId, SeatPosition zone, List<WaitingQueue> existing) {
        var show = em.find(Showtime.class, showId);
        validate(group, show);
        var row = existing.stream().filter(q -> q.getShowtime().getId().equals(showId)).findFirst().orElse(null);
        if (row != null && row.getStatus() != QueueStatus.WAITING) reject(409, "이미 종료되거나 확보된 대기입니다.");
        if (row != null && row.getSeatZone() == zone && row.getRequestedSeatIds().isEmpty()) return response(group);
        var inventory = holds.lockInventory(showId);
        var capacity = inventory.stream().filter(i -> i.getStatus()!=SeatStatus.BLOCKED).map(i -> {
            var free=new ShowtimeSeat(); free.setSeat(i.getSeat()); free.setStatus(SeatStatus.AVAILABLE); return free;
        }).toList();
        if (SmartSeatCandidates.analyze(capacity, show.getScreen().getId(), group.getPartySize(), group.getSeatPreferences(), zone).blocks().isEmpty())
            reject(409, "이 구역에는 요청 인원에 맞는 좌석 조합이 없습니다.");
        var rows=em.createQuery("select q from WaitingQueue q where q.showtime.id=:s order by q.id", WaitingQueue.class)
                .setParameter("s",showId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(rows.stream().anyMatch(q -> q.getUser().getId().equals(group.getUser().getId())
                && !Objects.equals(q.getRequestGroup()==null?null:q.getRequestGroup().getId(),group.getId())
                && ACTIVE.contains(q.getStatus())))
            BookingQueueLifecycle.rejectDuplicateShow();
        int global=rows.stream().mapToInt(WaitingQueue::getQueueNumber).max().orElse(0);
        int local=rows.stream().filter(q->q.getSeatZone()==zone).mapToInt(WaitingQueue::getZoneQueueNumber).max().orElse(0);
        if(global==Integer.MAX_VALUE || local==Integer.MAX_VALUE) reject(409,"대기 번호를 더 발급할 수 없습니다.");
        if(row==null) { row=new WaitingQueue(); row.setUser(group.getUser()); row.setRequestGroup(group); row.setShowtime(show); row.setCreatedAt(holds.now()); }
        if (row.getSeatZone()!=null) rememberZoneNumber(showId, row.getSeatZone(), row.getZoneQueueNumber());
        row.setQueueNumber(global+1); row.setZoneQueueNumber(nextZoneNumber(showId, zone, local)); row.setSeatZone(zone);
        row.setStatus(QueueStatus.WAITING); row.setUpdatedAt(holds.now());
        row.getRequestedSeatIds().clear();
        if(row.getId()==null) em.persist(row);
        return response(group);
    }

    // Callers already hold the show mutex, so the initial insert is serialized too.
    private WaitingZoneSequence rememberZoneNumber(Long show, SeatPosition zone, int previous) {
        String id=show+"_"+zone.name();
        var counter=em.find(WaitingZoneSequence.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(counter==null) { counter=new WaitingZoneSequence(); counter.setId(id); counter.setLastNumber(previous); em.persist(counter); }
        else counter.setLastNumber(Math.max(previous,counter.getLastNumber()));
        return counter;
    }

    private int nextZoneNumber(Long show, SeatPosition zone, int previous) {
        var counter=rememberZoneNumber(show,zone,previous);
        if(counter.getLastNumber()==Integer.MAX_VALUE) reject(409,"구역 대기 번호를 더 발급할 수 없습니다.");
        counter.setLastNumber(counter.getLastNumber()+1);
        return counter.getLastNumber();
    }

    public WaitingResponse get(Long user, Long id) {
        holds.requireUser(user);
        try {
            var group = holds.lockOwnedGroup(user, id);
            holds.expireLockedGroup(group);
            BookingQueueLifecycle.lockShows(em, id, null);
            return response(group);
        } catch (BookingRejection e) { throw new ResponseStatusException(HttpStatus.valueOf(e.status), e.getMessage()); }
    }

    public BookingResult cancel(Long user, Long id, String key) {
        BookingIdempotency.key(key); holds.requireUser(user);
        return operations.execute(user, BookingOperationType.CANCEL_GROUP, key, id, holds.now(), 200, () -> {
            var group = holds.lockOwnedGroup(user, id);
            if (group.getStatus() == BookingGroupStatus.COMPLETED) reject(409, "결제된 예약은 예약 상세에서 취소해주세요.");
            holds.expireLockedGroup(group);
            if (group.getStatus() == BookingGroupStatus.HOLDING) {
                var slot = em.find(BookingGroupHold.class, id, LockModeType.PESSIMISTIC_WRITE);
                if (slot == null) throw new IllegalStateException("선점 슬롯이 없습니다.");
                payments.cancelLocked(user, slot.getReservation().getId());
            }
            BookingQueueLifecycle.lockShows(em, id, null);
            BookingQueueLifecycle.cancelled(em, id, holds.now());
            group.setStatus(BookingGroupStatus.CANCELLED); group.setUpdatedAt(holds.now());
            return response(group);
        });
    }

    @Transactional(noRollbackFor = BookingRejection.class)
    void validate(BookingRequestGroup group, Showtime show) {
        if (group.getUser().getStatus() != UserStatus.ACTIVE) reject(409, "활성 회원만 대기 배정을 받을 수 있습니다.");
        BookingHoldService.validateShow(show, holds.now());
        BookingHoldService.validateGroupShow(group, show);
        if (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART && group.getTheaterPreferences().stream()
                .noneMatch(t -> t.getId().equals(show.getScreen().getTheater().getId())))
            reject(400, "저장된 선호극장 밖의 회차입니다.");
        BookingAudiencePolicy.revalidate(group, show.getStartTime().toLocalDate());
    }

    private WaitingResponse response(BookingRequestGroup group) {
        var now = holds.now();
        var rows = BookingQueueLifecycle.rows(em, group.getId());
        var items = new ArrayList<WaitingResponse.Item>();
        for (var q : rows) {
            if ((q.getStatus() == QueueStatus.WAITING || q.getStatus() == QueueStatus.PAUSED)
                    && (!q.getShowtime().getStartTime().isAfter(now) || q.getShowtime().getStatus() != ShowtimeStatus.SCHEDULED)) {
                q.setStatus(QueueStatus.EXPIRED); q.setUpdatedAt(now);
            }
            var requestedSeats = q.getRequestedSeatIds().isEmpty() ? List.<Seat>of() : em.createQuery(
                    "select s from Seat s where s.id in :ids order by s.seatRow, s.seatNumber", Seat.class)
                    .setParameter("ids", q.getRequestedSeatIds()).getResultList();
            // Every waiting request in the same zone shares the same position count.
            long ahead = em.createQuery("""
                    select q from WaitingQueue q where q.showtime.id=:s and q.requestGroup is not null
                    and q.status=:status and coalesce(q.zoneQueueNumber,q.queueNumber)<:number
                    and ((:zone is null and q.seatZone is null) or q.seatZone=:zone) order by q.id
                    """, WaitingQueue.class).setParameter("s", q.getShowtime().getId()).setParameter("status", QueueStatus.WAITING)
                    .setParameter("number", q.displayNumber()).setParameter("zone", q.getSeatZone())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().size();
            var show = q.getShowtime();
            items.add(new WaitingResponse.Item(q.getId(), show.getId(), q.displayNumber(), ahead, q.getStatus(),
                    offset(q.getOpportunityExpiresAt()), show.getScreen().getTheater().getName(), show.getScreen().getName(), offset(show.getStartTime()), q.getSeatZone(),
                    List.copyOf(q.getRequestedSeatIds()), requestedSeats.stream().map(s -> s.getSeatRow() + s.getSeatNumber()).toList()));
        }
        var choices = new ArrayList<WaitingResponse.Choice>();
        if (group.getStatus() == BookingGroupStatus.ACTIVE) {
            var from = CinemaDay.start(group.getViewingDate()); var until = from.plusDays(1);
            if (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART) {
                from = CinemaDay.time(group.getViewingDate(), group.getStartTimeFrom()); until = CinemaDay.time(group.getViewingDate(), group.getStartTimeTo());
                if (!until.isAfter(from)) until = until.plusDays(1);
            }
            var candidates = em.createQuery("select s from Showtime s where s.movie.id=:m and s.startTime>=:from and s.startTime>:now"
                    + (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART ? " and s.startTime<=:until" : " and s.startTime<:until")
                    + " order by s.startTime,s.id", Showtime.class)
                    .setParameter("m", group.getMovie().getId()).setParameter("from", from).setParameter("until", until).setParameter("now", now).getResultList();
            for (var show : candidates) {
                if (rows.stream().anyMatch(q -> q.getShowtime().getId().equals(show.getId()))) continue;
                try { validate(group, show); } catch (BookingRejection e) { continue; }
                choices.add(new WaitingResponse.Choice(show.getId(), show.getScreen().getTheater().getName(), show.getScreen().getName(), offset(show.getStartTime()), offset(show.getEndTime())));
            }
        }
        var slot = group.getStatus() == BookingGroupStatus.HOLDING ? em.find(BookingGroupHold.class, group.getId()) : null;
        return new WaitingResponse(group.getId(), group.getStatus(), slot == null ? null : slot.getReservation().getId(), items, choices, offset(now));
    }

    private static OffsetDateTime offset(LocalDateTime t) { return t == null ? null : t.atOffset(ZoneOffset.ofHours(9)); }
}
