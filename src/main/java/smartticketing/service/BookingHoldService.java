package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 일반/스마트/대기 호출자가 선택한 좌석을 한 번에 확보한다. 추천/대기 실행은 하지 않는다. */
@Service
@Transactional
public class BookingHoldService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final EntityManager em;
    private final BookingIdempotency operations;
    private final Clock clock;

    public BookingHoldService(EntityManager em, BookingIdempotency operations,
                              @Qualifier("bookingQueryClock") Clock clock) {
        this.em = em; this.operations = operations; this.clock = clock.withZone(SEOUL);
    }

    // New requests serialize before their first snapshot. Dispatch converts an existing slot.
    void lockCapacityUser(Long userId) {
        em.createNativeQuery("insert into booking_user_limits (user_id) values (:user) on duplicate key update user_id=user_id", Object.class)
                .setParameter("user", userId).executeUpdate();
    }

    void requireCapacity(Long userId, int additions, Long replacingGroup) {
        if (additions > remainingCapacity(userId, replacingGroup))
            throw new BookingRejection(409, "ACTIVE_BOOKING_LIMIT", "대기와 선점은 합쳐 최대 3개입니다. 기존 후보를 결제하거나 취소한 뒤 다시 신청해주세요.");
    }

    int remainingCapacity(Long userId, Long replacingGroup) {
        var time = now();
        long queued = em.createQuery("""
                select count(q) from WaitingQueue q where q.user.id=:user
                and q.status in :states and q.showtime.startTime>:now and q.showtime.status=:scheduled
                and (:replacement is null or q.requestGroup.id<>:replacement)
                """, Long.class).setParameter("user", userId)
                .setParameter("states", List.of(QueueStatus.WAITING, QueueStatus.PAUSED))
                .setParameter("now", time).setParameter("scheduled", ShowtimeStatus.SCHEDULED)
                .setParameter("replacement", replacingGroup).getSingleResult();
        long held = em.createQuery("""
                select count(r) from Reservation r where r.user.id=:user and r.status=:pending
                and r.expiresAt>:now and r.showtime.startTime>:now and r.showtime.status=:scheduled
                """, Long.class).setParameter("user", userId).setParameter("pending", ReservationStatus.PENDING)
                .setParameter("now", time).setParameter("scheduled", ShowtimeStatus.SCHEDULED).getSingleResult();
        return (int) Math.max(0, 3 - queued - held);
    }

    public enum Source { MANUAL, SMART, WAITING }
    public record Candidate(Long showtimeId, List<Long> seatIds) {}
    private record HoldIntent(Long groupId, Source source, Long showtimeId, List<Long> seatIds) {}

    public BookingResult manual(Long userId, Long groupId, String key, ManualHoldRequest request) {
        return hold(userId, groupId, key, Source.MANUAL, new Candidate(null, request.seatIds()));
    }

    // The waiting dispatcher calls acquire after locking all competing groups and shows.
    public BookingResult hold(Long userId, Long groupId, String key, Source source, Candidate candidate) {
        BookingIdempotency.key(key);
        lockCapacityUser(userId);
        requireUser(userId);
        if (groupId == null || groupId < 1 || source == null || candidate == null)
            throw new IllegalArgumentException("그룹과 선점 후보가 필요합니다.");
        var ids = normalizeSeats(candidate.seatIds());
        if (source != Source.MANUAL && (candidate.showtimeId() == null || candidate.showtimeId() < 1))
            throw new IllegalArgumentException("후보 회차가 필요합니다.");
        var type = switch (source) {
            case MANUAL -> BookingOperationType.MANUAL_HOLD;
            case SMART -> BookingOperationType.SMART_HOLD;
            case WAITING -> BookingOperationType.WAITING_HOLD;
        };
        return operations.execute(userId, type, key,
                new HoldIntent(groupId, source, candidate.showtimeId(), ids), now(),
                () -> acquire(userId, groupId, source, candidate.showtimeId(), ids));
    }

    // Package scope: smart orchestration supplies its own request-level idempotency transaction.
    @Transactional(noRollbackFor = BookingRejection.class)
    ReservationResponse acquire(Long userId, Long groupId, Source source, Long showtimeId, List<Long> ids) {
        var group = lockOwnedGroup(userId, groupId);
        if (group.getStatus() != BookingGroupStatus.ACTIVE)
            reject(409, "그룹에 활성 선점이 있거나 종료된 요청입니다.");
        if (source != Source.WAITING) requireCapacity(userId, 1, groupId);
        // ACTIVE 그룹에는 새 슬롯을 INSERT만 한다. 없는 슬롯에 FOR UPDATE를 걸면
        // MySQL RR gap lock으로 서로 다른 그룹의 INSERT가 교착될 수 있다.
        // 불일치 슬롯이 실제로 남아 있으면 PK 제약 실패로 전체 롤백하며 덮어쓰지 않는다.
        if (source == Source.MANUAL && group.getEntryPoint() != BookingEntryPoint.THEATER_NORMAL)
            reject(400, "일반예매 그룹만 좌석 직접 선점이 가능합니다.");
        if (source == Source.SMART && group.getEntryPoint() == BookingEntryPoint.THEATER_NORMAL)
            reject(400, "스마트예매 그룹이 필요합니다.");
        if (source == Source.SMART && group.getCandidateKind() != null)
            reject(409, "스마트예매 후보는 구역 대기 순서에 따라 자동 배정됩니다.");
        if (ids.size() != group.getPartySize()) reject(400, "요청 좌석 수는 전체 관람 인원과 같아야 합니다.");
        Long target = showtimeId;
        if (source == Source.MANUAL) {
            if (group.getSelectedShowtime() == null) reject(409, "선택 회차 연결을 확인할 수 없습니다.");
            target = group.getSelectedShowtime().getId();
        }
        BookingQueueLifecycle.lockShows(em, group.getId(), target);
        var show = em.find(Showtime.class, target, LockModeType.PESSIMISTIC_WRITE);
        // 잠금 대기 후의 현재 시각을 사용한다.
        var now = now();
        validateShow(show, now);
        validateGroupShow(group, show);
        if (BookingQueueLifecycle.rows(em, group.getId()).stream().anyMatch(q -> q.getShowtime().getId().equals(show.getId())
                && (q.getStatus() == QueueStatus.EXPIRED || q.getStatus() == QueueStatus.CANCELLED)))
            reject(409, "이 그룹에서 종료된 회차 기회입니다. 새 관람 요청으로 신청해주세요.");
        BookingAudiencePolicy.revalidate(group, show.getStartTime().toLocalDate());
        var inventory = lockInventory(show.getId());
        now = now();
        validateShow(show, now);
        var selected = inventory.stream().filter(s -> ids.contains(s.getSeat().getId())).toList();
        if (selected.size() != ids.size()) reject(400, "회차에 속하지 않는 좌석입니다.");
        for (var row : selected) {
            var seat = row.getSeat();
            if (group.getCandidateZone() != null && seat.getSeatPosition() != group.getCandidateZone())
                reject(409, "후보에 지정된 구역의 좌석만 확보할 수 있습니다.");
            if (!seat.isActive() || !seat.getScreen().getId().equals(show.getScreen().getId()))
                reject(409, "이용할 수 없는 좌석입니다.");
            if (row.getStatus() != SeatStatus.AVAILABLE || row.getReservation() != null || row.getHoldExpiredAt() != null)
                throw new BookingRejection(409, "SEAT_CONFLICT", "요청 좌석을 모두 확보할 수 없습니다.");
        }
        // 수동 선택에는 연속석 조건을 강제하지 않는다. 자동 후보는 알려진 연결정보만 검증한다.
        var ownQueue = BookingQueueLifecycle.rows(em, group.getId()).stream()
                .filter(q -> q.getShowtime().getId().equals(show.getId())).findFirst();
        var requested = ownQueue.map(q -> BookingQueueLifecycle.currentSeatIds(em, q.getId())).orElse(List.of());
        var exact = ownQueue.filter(q -> !requested.isEmpty());
        if (source == Source.WAITING && exact.isPresent() && !requested.equals(ids.stream().sorted().toList()))
            reject(409, "직접 대기한 좌석만 확보할 수 있습니다.");
        if (source == Source.MANUAL) {
            var zones = new HashSet<SeatPosition>(); selected.forEach(i -> zones.add(i.getSeat().getSeatPosition()));
            Integer before = exact.filter(q -> q.getStatus() == QueueStatus.WAITING && requested.equals(ids.stream().sorted().toList()))
                    .map(WaitingQueue::getQueueNumber).orElse(null);
            var earlier = em.createQuery("select q from WaitingQueue q where q.showtime.id=:show and q.status=:waiting and q.requestGroup.id<>:group and (:before is null or q.queueNumber<:before) order by q.queueNumber", WaitingQueue.class)
                    .setParameter("show", show.getId()).setParameter("waiting", QueueStatus.WAITING).setParameter("group", groupId)
                    .setParameter("before", before).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            for (var q : earlier) {
                var waitingSeats = BookingQueueLifecycle.currentSeatIds(em, q.getId());
                if (BookingQueueLifecycle.competing(q.getSeatZone(), waitingSeats, ids, zones)
                        && canAllocateWaiting(q, show, inventory, waitingSeats))
                    throw new BookingRejection(409, "WAITING_PRIORITY", "선택한 좌석에 배정 가능한 앞선 대기가 있습니다. 좌석 대기로 신청해주세요.");
            }
        }
        if (source != Source.MANUAL && !(source == Source.WAITING && group.getEntryPoint() == BookingEntryPoint.THEATER_NORMAL && exact.isPresent()))
            validateAutomaticLayout(selected, inventory);

        // 모든 검증이 끝난 뒤에만 도메인 쓰기를 시작한다.
        var expires = now.plusMinutes(5);
        var reservation = new Reservation();
        reservation.setUser(group.getUser()); reservation.setRequestGroup(group); reservation.setShowtime(show);
        reservation.setReservationType(group.getEntryPoint() == BookingEntryPoint.THEATER_NORMAL ? ReservationType.NORMAL : ReservationType.SMART);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setTotalAmount(10000 * group.getAdultCount() + 8000 * group.getYouthCount());
        reservation.setExpiresAt(expires); reservation.setCreatedAt(now); reservation.setUpdatedAt(now);
        em.persist(reservation);
        int adultSeats = group.getAdultCount();
        for (var row : selected.stream().sorted(Comparator.comparing(s -> s.getSeat().getId())).toList()) {
            var seat = new ReservationSeat(); seat.setReservation(reservation); seat.setSeat(row.getSeat());
            boolean adult = adultSeats-- > 0;
            seat.setAudienceType(adult ? "ADULT" : "YOUTH");
            seat.setPrice(adult ? 10000 : 8000); em.persist(seat);
            row.setStatus(SeatStatus.HOLDING); row.setReservation(reservation); row.setHoldExpiredAt(expires);
        }
        var slot = new BookingGroupHold(); slot.setRequestGroup(group); slot.setReservation(reservation);
        slot.setExpiresAt(expires); em.persist(slot);
        group.setStatus(BookingGroupStatus.HOLDING); group.setUpdatedAt(now);
        BookingQueueLifecycle.held(em, group, show.getId(), expires, now);
        updateAvailable(show, inventory, now);
        NotificationService.acquired(em, reservation, source == Source.WAITING);
        return response(reservation, now);
    }

    private boolean canAllocateWaiting(WaitingQueue queue, Showtime show, List<ShowtimeSeat> inventory, List<Long> requested) {
        var group = queue.getRequestGroup();
        if (group.getStatus() != BookingGroupStatus.ACTIVE || group.getUser().getStatus() != UserStatus.ACTIVE) return false;
        try {
            validateGroupShow(group, show);
            BookingAudiencePolicy.revalidate(group, show.getStartTime().toLocalDate());
        } catch (BookingRejection mismatch) { return false; }
        if (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART && group.getTheaterPreferences().stream()
                .noneMatch(t -> t.getId().equals(show.getScreen().getTheater().getId()))) return false;
        if (!requested.isEmpty()) {
            var seats = inventory.stream().filter(row -> requested.contains(row.getSeat().getId())).toList();
            return seats.size() == group.getPartySize() && seats.stream().allMatch(row -> row.getSeat().isActive()
                    && row.getSeat().getScreen().getId().equals(show.getScreen().getId()) && row.getStatus() == SeatStatus.AVAILABLE
                    && row.getReservation() == null && row.getHoldExpiredAt() == null);
        }
        return !SmartSeatCandidates.analyze(inventory, show.getScreen().getId(), group.getPartySize(), group.getSeatPreferences(), queue.getSeatZone()).blocks().isEmpty();
    }

    public ReservationResponse reservation(Long userId, Long reservationId) {
        requireUser(userId);
        // ID만 읽고 도메인 엔티티는 잠금 순서대로 다시 읽는다. 오래된 snapshot 엔티티 재사용 금지.
        var refs = em.createQuery("select r.requestGroup.id from Reservation r where r.id=:id and r.user.id=:user", Long.class)
                .setParameter("id", reservationId).setParameter("user", userId).getResultList();
        if (refs.isEmpty() || refs.getFirst() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "예매를 찾을 수 없습니다.");
        var group = ownedGroupForRead(userId, refs.getFirst());
        var slot = group.getStatus() == BookingGroupStatus.HOLDING
                ? em.find(BookingGroupHold.class, group.getId(), LockModeType.PESSIMISTIC_WRITE) : null;
        // Old reservations must not acquire another active reservation's show out of order.
        if (slot != null && slot.getReservation().getId().equals(reservationId)) expireLockedGroup(group);
        var showId = em.createQuery("select r.showtime.id from Reservation r where r.id=:id", Long.class)
                .setParameter("id", reservationId).getSingleResult();
        em.find(Showtime.class, showId, LockModeType.PESSIMISTIC_WRITE);
        return response(em.find(Reservation.class, reservationId, LockModeType.PESSIMISTIC_WRITE), now());
    }

    public BookingGroupResponse group(Long userId, Long groupId) {
        requireUser(userId);
        var group = ownedGroupForRead(userId, groupId);
        expireLockedGroup(group);
        return groupResponse(group);
    }

    BookingRequestGroup ownedGroupForRead(Long userId, Long groupId) {
        try { return lockOwnedGroup(userId, groupId); }
        catch (BookingRejection e) { throw new ResponseStatusException(HttpStatus.valueOf(e.status), e.getMessage()); }
    }

    @Transactional(noRollbackFor = BookingRejection.class)
    BookingRequestGroup lockOwnedGroup(Long userId, Long groupId) {
        var group = em.find(BookingRequestGroup.class, groupId, LockModeType.PESSIMISTIC_WRITE);
        if (group == null || !group.getUser().getId().equals(userId)) reject(404, "관람 요청을 찾을 수 없습니다.");
        return group;
    }

    public boolean expire(Long groupId) {
        var group = em.find(BookingRequestGroup.class, groupId, LockModeType.PESSIMISTIC_WRITE);
        return group != null && expireLockedGroup(group);
    }

    @Transactional(readOnly = true)
    public List<Long> expiredGroupIds(int limit) {
        return em.createQuery("select h.id from BookingGroupHold h where h.expiresAt <= :now order by h.expiresAt, h.id", Long.class)
                .setParameter("now", now()).setMaxResults(limit).getResultList();
    }

    boolean expireLockedGroup(BookingRequestGroup group) {
        if (group.getStatus() != BookingGroupStatus.HOLDING) return false;
        var slot = em.find(BookingGroupHold.class, group.getId(), LockModeType.PESSIMISTIC_WRITE);
        if (slot == null) throw new IllegalStateException("활성 선점 슬롯이 없어 자동 복구를 중단합니다.");
        if (slot.getExpiresAt().isAfter(now())) return false;
        Long reservationId = slot.getReservation().getId();
        // slot의 lazy 예약을 먼저 읽어 잠그지 않는다. 회차 ID는 스칼라로 찾는다.
        Long showId = em.createQuery("select r.showtime.id from Reservation r where r.id=:id", Long.class)
                .setParameter("id", reservationId).getSingleResult();
        BookingQueueLifecycle.lockShows(em, group.getId(), showId);
        var show = em.find(Showtime.class, showId, LockModeType.PESSIMISTIC_WRITE);
        var reservation = em.find(Reservation.class, reservationId, LockModeType.PESSIMISTIC_WRITE);
        var now = now();
        if (reservation.getStatus() != ReservationStatus.PENDING || reservation.getExpiresAt() == null
                || reservation.getExpiresAt().isAfter(now)) return false;
        if (group.getStatus() != BookingGroupStatus.HOLDING || reservation.getRequestGroup() == null
                || !reservation.getRequestGroup().getId().equals(group.getId())
                || !reservation.getUser().getId().equals(group.getUser().getId()))
            throw new IllegalStateException("선점 슬롯 연결이 일치하지 않아 자동 복구를 중단합니다.");
        var inventory = lockInventory(showId);
        var owned = inventory.stream().filter(s -> s.getReservation() != null
                && s.getReservation().getId().equals(reservationId)).toList();
        var expected = em.createQuery("select rs.seat.id from ReservationSeat rs where rs.reservation.id=:id order by rs.seat.id", Long.class)
                .setParameter("id", reservationId).getResultList();
        if (!owned.stream().map(s -> s.getSeat().getId()).sorted().toList().equals(expected)
                || owned.stream().anyMatch(s -> s.getStatus() != SeatStatus.HOLDING
                || !Objects.equals(s.getHoldExpiredAt(), reservation.getExpiresAt())))
            throw new IllegalStateException("좌석 연결이 일치하지 않아 자동 복구를 중단합니다.");
        for (var seat : owned) {
            seat.setStatus(SeatStatus.AVAILABLE); seat.setReservation(null); seat.setHoldExpiredAt(null);
        }
        reservation.setStatus(ReservationStatus.EXPIRED); reservation.setUpdatedAt(now);
        group.setStatus(BookingGroupStatus.ACTIVE); group.setUpdatedAt(now);
        BookingQueueLifecycle.released(em, group.getId(), false, now);
        em.remove(slot); updateAvailable(show, inventory, now);
        return true;
    }

    List<ShowtimeSeat> lockInventory(Long showId) {
        // 회차 행이 재고 변경의 공통 mutex다. 좌석은 PK 순으로 잠근다.
        return em.createQuery("select s from ShowtimeSeat s where s.showtime.id=:id order by s.id", ShowtimeSeat.class)
                .setParameter("id", showId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    static void validateShow(Showtime show, LocalDateTime now) {
        if (show == null) reject(404, "회차를 찾을 수 없습니다.");
        if (show.getStatus() != ShowtimeStatus.SCHEDULED || !show.getStartTime().isAfter(now)
                || !show.getMovie().isActive() || !show.getScreen().isActive() || !show.getScreen().getTheater().isActive())
            reject(409, "예매가 종료되었거나 비활성 회차입니다.");
        if (!Integer.valueOf(10000).equals(show.getPricePerPerson()))
            reject(409, "회차 가격을 확인할 수 없습니다.");
    }

    static void validateGroupShow(BookingRequestGroup group, Showtime show) {
        if (!group.getMovie().getId().equals(show.getMovie().getId())) reject(400, "그룹의 영화와 회차가 다릅니다.");
        if (group.getSelectedShowtime() != null) {
            if (!group.getSelectedShowtime().getId().equals(show.getId())
                    || !group.getViewingDate().equals(CinemaDay.date(show.getStartTime())))
                reject(400, "그룹에서 선택한 회차 및 날짜와 다릅니다.");
        } else {
            if (group.getStartTimeFrom() == null || group.getStartTimeTo() == null
                    || group.getStartTimeFrom().equals(group.getStartTimeTo()))
                reject(409, "관람 요청의 시간 범위를 확인할 수 없습니다.");
            var from = CinemaDay.time(group.getViewingDate(), group.getStartTimeFrom());
            var until = CinemaDay.time(group.getViewingDate(), group.getStartTimeTo());
            if (!until.isAfter(from)) until = until.plusDays(1);
            if (show.getStartTime().isBefore(from) || show.getStartTime().isAfter(until))
                reject(400, "요청 시간 범위 밖의 회차입니다.");
        }
    }

    private static void validateAutomaticLayout(List<ShowtimeSeat> selected, List<ShowtimeSeat> inventory) {
        Map<String, SortedSet<Integer>> runs = new HashMap<>();
        for (var row : selected) {
            var s = row.getSeat();
            if (s.getAdjacencySegment() == null || s.getAdjacencySegment().isBlank()
                    || s.getPositionInSegment() == null || s.getPositionInSegment() < 1)
                reject(409, "좌석 연결정보가 확인되지 않았습니다.");
            long samePosition = inventory.stream().map(ShowtimeSeat::getSeat)
                    .filter(x -> Objects.equals(x.getSeatRow(), s.getSeatRow())
                            && Objects.equals(x.getAdjacencySegment(), s.getAdjacencySegment())
                            && Objects.equals(x.getPositionInSegment(), s.getPositionInSegment())).count();
            if (samePosition != 1) reject(409, "좌석 연결정보가 중복되었습니다.");
            runs.computeIfAbsent(s.getSeatRow() + "\u0000" + s.getAdjacencySegment(), ignored -> new TreeSet<>())
                    .add(s.getPositionInSegment());
        }
        List<Integer> sizes = new ArrayList<>();
        for (var positions : runs.values()) {
            int previous = -1, size = 0;
            for (int position : positions) {
                if (size > 0 && position != previous + 1) { sizes.add(size); size = 0; }
                size++; previous = position;
            }
            sizes.add(size);
        }
        sizes.sort(Integer::compareTo);
        if (!SeatPartyRules.allows(sizes)) reject(409, "전체 연석 또는 인원별 허용된 분할 연석 조합이 필요합니다.");
    }

    static List<Long> normalizeSeats(List<Long> seats) {
        if (seats == null || seats.isEmpty() || seats.size() > 6
                || seats.stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(seats).size() != seats.size())
            throw new IllegalArgumentException("중복 없는 좌석 ID 1~6개가 필요합니다.");
        return seats.stream().sorted().toList();
    }

    Users requireUser(Long id) {
        var user = id == null ? null : em.find(Users.class, id);
        if (user == null || user.getStatus() != UserStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "활성 회원 인증이 필요합니다.");
        return user;
    }

    BookingGroupResponse groupResponse(BookingRequestGroup g) {
        var slot = g.getStatus() == BookingGroupStatus.HOLDING
                ? em.find(BookingGroupHold.class, g.getId(), LockModeType.PESSIMISTIC_WRITE) : null;
        return new BookingGroupResponse(g.getId(), g.getEntryPoint(), g.getMovie().getId(), g.getViewingDate(),
                g.getPartySize(), g.getStartTimeFrom(), g.getStartTimeTo(),
                g.getSelectedShowtime() == null ? null : g.getSelectedShowtime().getId(),
                g.getTheaterPreferences().stream().map(Theater::getId).toList(), List.copyOf(g.getSeatPreferences()),
                g.getStatus(), slot == null ? null : slot.getReservation().getId(), BookingAudiencePolicy.audience(g), g.getRatingSnapshot());
    }

    ReservationResponse response(Reservation r, LocalDateTime now) {
        var show = r.getShowtime(); var screen = show.getScreen();
        var seats = em.createQuery("select s from ReservationSeat s join fetch s.seat where s.reservation.id=:id order by s.seat.id", ReservationSeat.class)
                .setParameter("id", r.getId()).getResultList();
        return new ReservationResponse(r.getId(), r.getRequestGroup().getId(), r.getStatus(), r.getReservationType(),
                show.getMovie().getId(), show.getMovie().getTitle(), show.getId(), screen.getTheater().getId(), screen.getTheater().getName(),
                screen.getId(), screen.getName(), offset(show.getStartTime()), offset(show.getEndTime()),
                seats.stream().map(s -> s.getSeat().getId()).toList(),
                seats.stream().map(s -> s.getSeat().getSeatRow() + s.getSeat().getSeatNumber()).toList(),
                r.getTotalAmount(), offset(r.getExpiresAt()), offset(now),
                seats.stream().map(s -> new ReservationResponse.SeatPrice(s.getSeat().getId(), s.getAudienceType(), s.getPrice())).toList());
    }

    static void updateAvailable(Showtime show, List<ShowtimeSeat> inventory, LocalDateTime now) {
        show.setAvailableSeats((int) inventory.stream().filter(s -> s.getSeat().isActive()
                && s.getSeat().getScreen().getId().equals(show.getScreen().getId()) && s.getStatus() == SeatStatus.AVAILABLE).count());
        show.setUpdatedAt(now);
    }

    LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS); }
    private static OffsetDateTime offset(LocalDateTime value) { return value == null ? null : value.atZone(SEOUL).toOffsetDateTime(); }
    static void reject(int status, String message) { throw new BookingRejection(status, message); }
}
