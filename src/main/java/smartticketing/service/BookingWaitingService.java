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
    private record Intent(Long groupId, List<Long> showtimeIds) {}

    public BookingWaitingService(EntityManager em, BookingHoldService holds, BookingPaymentService payments, BookingIdempotency operations) {
        this.em = em; this.holds = holds; this.payments = payments; this.operations = operations;
    }

    public BookingResult register(Long user, Long id, String key, WaitingRequest request) {
        BookingIdempotency.key(key); holds.requireUser(user);
        if (request == null || request.showtimeIds() == null || request.showtimeIds().isEmpty()
                || request.showtimeIds().stream().anyMatch(s -> s == null || s < 1))
            throw new IllegalArgumentException("대기할 회차를 선택해주세요.");
        var ids = request.showtimeIds().stream().distinct().sorted().toList();
        if (ids.size() != request.showtimeIds().size()) throw new IllegalArgumentException("같은 회차를 중복 신청할 수 없습니다.");
        return operations.execute(user, BookingOperationType.REGISTER_WAITING, key, new Intent(id, ids), holds.now(), () -> {
            var group = holds.lockOwnedGroup(user, id);
            if (group.getStatus() != BookingGroupStatus.ACTIVE) reject(409, "진행 중인 선점 또는 종료된 그룹에는 대기를 추가할 수 없습니다.");
            var shows = new TreeSet<>(BookingQueueLifecycle.showIds(em, id)); shows.addAll(ids);
            shows.forEach(s -> em.find(Showtime.class, s, LockModeType.PESSIMISTIC_WRITE));
            var existing = BookingQueueLifecycle.rows(em, id);
            if (existing.stream().anyMatch(q -> !shows.contains(q.getShowtime().getId())))
                throw new org.springframework.dao.TransientDataAccessResourceException("대기 회차가 변경되었습니다. 같은 요청으로 재시도해주세요.");
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
                if (!duplicates.isEmpty()) reject(409, "다른 그룹에서 이미 이 회차에 대기 중입니다.");
                // The show mutex serializes number issuance, including parallel groups of the same user.
                var numbers = em.createQuery("select q from WaitingQueue q where q.showtime.id=:s order by q.queueNumber desc", WaitingQueue.class)
                        .setParameter("s", showId).setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                int previous = numbers.isEmpty() ? 0 : numbers.getFirst().getQueueNumber();
                if (previous == Integer.MAX_VALUE) reject(409, "대기 번호를 더 발급할 수 없습니다.");
                var q = new WaitingQueue(); q.setUser(group.getUser()); q.setRequestGroup(group); q.setShowtime(show);
                q.setQueueNumber(previous + 1); q.setStatus(QueueStatus.WAITING);
                q.setCreatedAt(holds.now()); q.setUpdatedAt(holds.now()); additions.add(q);
            }
            additions.forEach(em::persist);
            return response(group);
        });
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
            // Current locking read; PAUSED and legacy rows do not participate in allocation.
            long ahead = em.createQuery("""
                    select q from WaitingQueue q where q.showtime.id=:s and q.requestGroup is not null
                    and q.status=:status and q.queueNumber<:number order by q.id
                    """, WaitingQueue.class).setParameter("s", q.getShowtime().getId()).setParameter("status", QueueStatus.WAITING)
                    .setParameter("number", q.getQueueNumber()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().size();
            var show = q.getShowtime();
            items.add(new WaitingResponse.Item(q.getId(), show.getId(), q.getQueueNumber(), ahead, q.getStatus(),
                    offset(q.getOpportunityExpiresAt()), show.getScreen().getTheater().getName(), show.getScreen().getName(), offset(show.getStartTime())));
        }
        var choices = new ArrayList<WaitingResponse.Choice>();
        if (group.getStatus() == BookingGroupStatus.ACTIVE) {
            var from = CinemaDay.start(group.getViewingDate()); var until = from.plusDays(1);
            if (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART) {
                from = CinemaDay.time(group.getViewingDate(), group.getStartTimeFrom()); until = CinemaDay.time(group.getViewingDate(), group.getStartTimeTo());
                if (!until.isAfter(from)) until = until.plusDays(1);
            }
            var candidates = em.createQuery("select s from Showtime s where s.movie.id=:m and s.startTime>=:from and s.startTime<:until and s.startTime>:now order by s.startTime,s.id", Showtime.class)
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
