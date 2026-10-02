package smartticketing.service;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.util.*;

/** Database-backed recovery: no in-memory seat-release event is required for correctness. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BookingWaitingDispatcher {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingWaitingService waiting;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public BookingWaitingDispatcher(EntityManager em, BookingHoldService holds, BookingWaitingService waiting, PlatformTransactionManager manager) {
        this.em = em; this.holds = holds; this.waiting = waiting;
        read = new TransactionTemplate(manager); read.setReadOnly(true);
        write = new TransactionTemplate(manager);
    }

    public List<Long> pendingShows() {
        return read.execute(s -> em.createQuery("select distinct q.showtime.id from WaitingQueue q where q.requestGroup is not null and q.status=:status order by q.showtime.id", Long.class)
                .setParameter("status", QueueStatus.WAITING).getResultList());
    }

    public int dispatch(Long showId) {
        var groups = read.execute(s -> em.createQuery("""
                select distinct q.requestGroup.id from WaitingQueue q where q.showtime.id=:show
                and q.requestGroup is not null and q.status in :statuses order by q.requestGroup.id
                """, Long.class).setParameter("show", showId)
                .setParameter("statuses", List.of(QueueStatus.WAITING, QueueStatus.PAUSED)).getResultList());
        if (groups.isEmpty()) return 0;
        return write.execute(s -> {
            em.clear();
            // Multiple-group transactions acquire EVERY group before ANY show, in ID order.
            var locked = new HashMap<Long, BookingRequestGroup>();
            for (var id : groups) locked.put(id, em.find(BookingRequestGroup.class, id, LockModeType.PESSIMISTIC_WRITE));
            var shows = new TreeSet<Long>(); shows.add(showId);
            for (var id : groups) shows.addAll(BookingQueueLifecycle.showIds(em, id));
            shows.forEach(id -> em.find(Showtime.class, id, LockModeType.PESSIMISTIC_WRITE));
            for (var id : groups) {
                if (BookingQueueLifecycle.rows(em, id).stream().anyMatch(q -> !shows.contains(q.getShowtime().getId()))) return 0;
            }
            var show = em.find(Showtime.class, showId);
            var queues = em.createQuery("""
                    select q from WaitingQueue q where q.showtime.id=:show and q.requestGroup is not null
                    and q.status=:status order by q.queueNumber,q.id
                    """, WaitingQueue.class).setParameter("show", showId).setParameter("status", QueueStatus.WAITING)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            // A registration committed after the discovery snapshot. Retry on the next sweep rather
            // than acquiring its group out of order or bypassing its potentially earlier number.
            if (queues.stream().anyMatch(q -> !locked.containsKey(q.getRequestGroup().getId()))) return 0;
            int allocated = 0;
            for (var q : queues) {
                var group = locked.get(q.getRequestGroup().getId());
                if (group.getStatus() != BookingGroupStatus.ACTIVE || q.getStatus() != QueueStatus.WAITING) continue;
                if (!show.getStartTime().isAfter(holds.now()) || show.getStatus() != ShowtimeStatus.SCHEDULED) {
                    q.setStatus(QueueStatus.EXPIRED); q.setUpdatedAt(holds.now()); continue;
                }
                try { waiting.validate(group, show); }
                catch (BookingRejection mismatch) { continue; }
                var inventory = holds.lockInventory(showId);
                var best = SmartSeatCandidates.analyze(inventory, show.getScreen().getId(), group.getPartySize(), group.getSeatPreferences())
                        .blocks().stream().min(Comparator.comparing(SmartSeatCandidates.Block::split)
                                .thenComparingInt(SmartSeatCandidates.Block::preferenceRank)
                                .thenComparing(SmartSeatCandidates.Block::row).thenComparing(SmartSeatCandidates.Block::segment)
                                .thenComparingInt(SmartSeatCandidates.Block::firstPosition));
                if (best.isEmpty()) continue; // An unsatisfiable earlier party must not block a later party.
                holds.acquire(group.getUser().getId(), group.getId(), BookingHoldService.Source.WAITING, showId, best.get().seatIds());
                allocated++;
            }
            return allocated;
        });
    }
}
