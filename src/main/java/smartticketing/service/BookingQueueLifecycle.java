package smartticketing.service;

import jakarta.persistence.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.LocalDateTime;
import java.util.*;

/** Called only with the owning group locked; queue changes share the reservation transaction. */
final class BookingQueueLifecycle {
    private BookingQueueLifecycle() {}

    static boolean competing(WaitingQueue queue, List<Long> seatIds, Set<SeatPosition> zones) {
        return competing(queue.getSeatZone(), queue.getRequestedSeatIds(), seatIds, zones);
    }

    static boolean competing(SeatPosition zone, List<Long> requested, List<Long> seatIds, Set<SeatPosition> zones) {
        if (!requested.isEmpty()) return requested.stream().anyMatch(seatIds::contains);
        return zone == null || zones.contains(zone);
    }

    // The show mutex is held by callers. Use a current read for the element collection
    // as well as the queue row: lazy collection reads otherwise use an older RR snapshot.
    static List<Long> currentSeatIds(EntityManager em, Long queueId) {
        return em.createNativeQuery("select seat_id from waiting_queue_seats where waiting_queue_id=:id order by seat_order for update", Long.class)
                .setParameter("id", queueId).getResultList();
    }

    static SortedSet<Long> showIds(EntityManager em, Long group) {
        return new TreeSet<>(em.createQuery("select q.showtime.id from WaitingQueue q where q.requestGroup.id=:g", Long.class)
                .setParameter("g", group).getResultList());
    }

    static void lockShows(EntityManager em, Long group, Long extra) {
        var ids = showIds(em, group);
        if (extra != null) ids.add(extra);
        ids.forEach(id -> em.find(Showtime.class, id, LockModeType.PESSIMISTIC_WRITE));
        // Under REPEATABLE_READ, an earlier ID lookup may predate the group lock.
        // Never mutate a queue on a show omitted by that snapshot. Roll back and retry
        // in a fresh transaction rather than acquiring a newly discovered lower show ID.
        if (rows(em, group).stream().anyMatch(q -> !ids.contains(q.getShowtime().getId())))
            throw new org.springframework.dao.TransientDataAccessResourceException("대기 회차가 변경되었습니다. 같은 요청으로 재시도해주세요.");
    }

    static List<WaitingQueue> rows(EntityManager em, Long group) {
        return em.createQuery("select q from WaitingQueue q where q.requestGroup.id=:g order by q.id", WaitingQueue.class)
                .setParameter("g", group).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    static void held(EntityManager em, BookingRequestGroup group, Long show, LocalDateTime expires, LocalDateTime now) {
        for (var q : rows(em, group.getId())) {
            if (q.getStatus() != QueueStatus.WAITING) continue;
            if (q.getShowtime().getId().equals(show)) {
                q.setStatus(QueueStatus.HOLDING); q.setOpportunityExpiresAt(expires);
            } else q.setStatus(QueueStatus.PAUSED);
            q.setUpdatedAt(now);
            changed(em, q, now);
        }
    }

    static void completed(EntityManager em, Long group, LocalDateTime now) {
        for (var q : rows(em, group)) {
            if (q.getStatus() == QueueStatus.HOLDING) q.setStatus(QueueStatus.COMPLETED);
            else if (q.getStatus() == QueueStatus.WAITING || q.getStatus() == QueueStatus.PAUSED) q.setStatus(QueueStatus.CANCELLED);
            else continue;
            q.setUpdatedAt(now);
            changed(em, q, now);
        }
    }

    static boolean released(EntityManager em, Long group, boolean cancelled, LocalDateTime now) {
        boolean waitingHold = false;
        for (var q : rows(em, group)) {
            if (q.getStatus() == QueueStatus.HOLDING) {
                waitingHold = true; q.setStatus(cancelled ? QueueStatus.CANCELLED : QueueStatus.EXPIRED);
            } else if (q.getStatus() == QueueStatus.PAUSED) {
                q.setStatus(q.getShowtime().getStartTime().isAfter(now) && q.getShowtime().getStatus() == ShowtimeStatus.SCHEDULED
                        ? QueueStatus.WAITING : QueueStatus.EXPIRED);
            } else continue;
            q.setUpdatedAt(now);
            changed(em, q, now);
        }
        return waitingHold;
    }

    static void cancelled(EntityManager em, Long group, LocalDateTime now) {
        for (var q : rows(em, group)) {
            if (q.getStatus() == QueueStatus.WAITING || q.getStatus() == QueueStatus.PAUSED || q.getStatus() == QueueStatus.HOLDING) {
                q.setStatus(QueueStatus.CANCELLED); q.setUpdatedAt(now);
                changed(em, q, now);
            }
        }
    }

    static void changed(EntityManager em, WaitingQueue q, LocalDateTime now) {
        BookingOutbox.append(em, q.getShowtime().getId(), BookingOutboxEvent.Type.WAITING_CHANGED,
                q.getRequestGroup().getId(), "WAITING_" + q.getStatus().name(), now);
    }
}
