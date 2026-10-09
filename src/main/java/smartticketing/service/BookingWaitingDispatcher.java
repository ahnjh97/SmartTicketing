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
    private static final int GROUP_QUERY_BATCH_SIZE = 100;
    // A hint only: inventory is checked again under the existing MySQL locks.
    // Closed/started shows must still be visited to expire their waiting rows.
    private static final String ACTIONABLE = """
            (s.status<>:scheduled or s.startTime<=:now or exists (
                select i.id from ShowtimeSeat i where i.showtime=s and i.status=:available
                and i.reservation is null and i.holdExpiredAt is null and i.seat.active=true))
            """;
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingWaitingService waiting;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private BookingDispatchGate dispatchGate;

    public BookingWaitingDispatcher(EntityManager em, BookingHoldService holds, BookingWaitingService waiting, PlatformTransactionManager manager) {
        this.em = em; this.holds = holds; this.waiting = waiting;
        read = new TransactionTemplate(manager); read.setReadOnly(true);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<Long> pendingShows() {
        return read.execute(s -> actionable("select distinct s.id from WaitingQueue q join q.showtime s where q.requestGroup is not null and q.status=:status and " + ACTIONABLE + " order by s.id")
                .setParameter("status", QueueStatus.WAITING).getResultList());
    }

    private TypedQuery<Long> actionable(String query) {
        return em.createQuery(query,Long.class).setParameter("scheduled",ShowtimeStatus.SCHEDULED)
                .setParameter("now",holds.now()).setParameter("available",SeatStatus.AVAILABLE);
    }

    public int dispatch(Long showId) {
        // Skip group write locks when no seat can possibly be assigned. A racing release is retried.
        if (Boolean.TRUE.equals(read.execute(s -> actionable("select s.id from Showtime s where s.id=:show and " + ACTIONABLE)
                .setParameter("show",showId).getResultList().isEmpty()))) return 0;
        var zones = read.execute(s -> em.createQuery("select distinct q.seatZone from WaitingQueue q where q.showtime.id=:show and q.requestGroup is not null and q.status=:status", SeatPosition.class)
                .setParameter("show", showId).setParameter("status", QueueStatus.WAITING).getResultList());
        if (zones.contains(null)) return gatedZone(showId, null); // Legacy waits still take all DB zone locks.
        int allocated = 0;
        BookingDispatchGate.Busy busy = null;
        for (var zone : zones.stream().sorted(Comparator.comparing(Enum::name)).toList()) {
            try { allocated += gatedZone(showId, zone); }
            catch (BookingDispatchGate.Busy occupied) { busy = occupied; }
        }
        // Process other zones first, but retry the event until every busy zone has been visited.
        if (busy != null) throw busy;
        return allocated;
    }

    private int gatedZone(Long showId, SeatPosition zone) {
        return dispatchGate == null ? dispatchZone(showId, zone)
                : dispatchGate.run(showId, zone, () -> dispatchZone(showId, zone));
    }

    private int dispatchZone(Long showId, SeatPosition zone) {
        var groups = read.execute(s -> em.createQuery("""
                select distinct q.requestGroup.id from WaitingQueue q where q.showtime.id=:show
                and q.requestGroup is not null and q.status=:status and (:zone is null or q.seatZone=:zone) order by q.requestGroup.id
                """, Long.class).setParameter("show", showId)
                .setParameter("zone", zone).setParameter("status", QueueStatus.WAITING).getResultList());
        if (groups.isEmpty()) return 0;
        return write.execute(s -> {
            em.clear();
            // Multiple-group transactions acquire EVERY group before ANY zone, in ID order.
            var locked = new HashMap<Long, BookingRequestGroup>();
            for (var id : groups) locked.put(id, em.find(BookingRequestGroup.class, id, LockModeType.PESSIMISTIC_WRITE));
            var shows = new TreeSet<Long>(); shows.add(showId);
            var scopes = new TreeSet<BookingZoneLocks.Scope>();
            BookingZoneLocks.add(scopes, showId, zone);
            for (var groupId : groups) scopes.addAll(BookingZoneLocks.groupScopes(em, groupId));
            for (int start=0; start<groups.size(); start+=GROUP_QUERY_BATCH_SIZE) {
                var batch=groups.subList(start,Math.min(start+GROUP_QUERY_BATCH_SIZE,groups.size()));
                shows.addAll(em.createQuery("select distinct q.showtime.id from WaitingQueue q where q.requestGroup.id in :groups",Long.class)
                        .setParameter("groups",batch).getResultList());
            }
            BookingZoneLocks.lock(em, scopes);
            for (int start=0; start<groups.size(); start+=GROUP_QUERY_BATCH_SIZE) {
                var batch=groups.subList(start,Math.min(start+GROUP_QUERY_BATCH_SIZE,groups.size()));
                var current=em.createQuery("select q from WaitingQueue q where q.requestGroup.id in :groups order by q.id",WaitingQueue.class)
                        .setParameter("groups",batch).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
                if (current.stream().anyMatch(q -> !shows.contains(q.getShowtime().getId()))) return 0;
            }
            var show = em.find(Showtime.class, showId);
            var queues = em.createQuery("""
                    select q from WaitingQueue q where q.showtime.id=:show and q.requestGroup is not null
                    and q.status=:status and (:zone is null or q.seatZone=:zone) order by q.seatZone,coalesce(q.zoneQueueNumber,q.queueNumber),q.id
                """, WaitingQueue.class).setParameter("show", showId).setParameter("status", QueueStatus.WAITING)
                    .setParameter("zone", zone)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            // A registration committed after the discovery snapshot. Retry on the next sweep rather
            // than acquiring its group out of order or bypassing its potentially earlier number.
            if (queues.stream().anyMatch(q -> !locked.containsKey(q.getRequestGroup().getId()))) return 0;
            int allocated = 0;
            BookingHoldService.ZoneInventory lockedInventory = null;
            for (var q : queues) {
                var group = locked.get(q.getRequestGroup().getId());
                if (group.getStatus() != BookingGroupStatus.ACTIVE || q.getStatus() != QueueStatus.WAITING) continue;
                if (!show.getStartTime().isAfter(holds.now()) || show.getStatus() != ShowtimeStatus.SCHEDULED) {
                    BookingZoneLocks.finish(em, shows);
                    q.setStatus(QueueStatus.EXPIRED); q.setUpdatedAt(holds.now());
                    BookingQueueLifecycle.changed(em, q, holds.now()); continue;
                }
                try { waiting.validate(group, show); }
                catch (BookingRejection mismatch) { continue; }

                // Higher-priority waits remain eligible while another candidate is held.
                // These managed rows retain our own allocations as the batch advances.
                // Never reuse them outside this write transaction or across dispatch calls.
                if (lockedInventory == null) lockedInventory = holds.readWaitingInventory(showId,
                        zone == null ? List.of(SeatPosition.values()) : List.of(zone));
                var inventory = lockedInventory.rows();
                var requested = BookingQueueLifecycle.currentSeatIds(em, q.getId());
                if (!requested.isEmpty()) {
                    var exact = inventory.stream().filter(i -> requested.contains(i.getSeat().getId())).toList();
                    if (exact.size() != group.getPartySize() || exact.stream().anyMatch(i -> i.getStatus() != SeatStatus.AVAILABLE
                            || i.getReservation() != null || i.getHoldExpiredAt() != null || !i.getSeat().isActive())) continue;
                    BookingZoneLocks.finish(em, shows);
                    holds.acquireWaiting(group.getUser().getId(), group.getId(), showId, requested, lockedInventory);
                    NotificationService.waitingAcquired(em, group);
                    allocated++;
                    continue;
                }
                var best = SmartSeatCandidates.analyze(inventory, show.getScreen().getId(), group.getPartySize(), group.getSeatPreferences(), q.getSeatZone())
                        .blocks().stream().min(SmartSeatCandidates.priorityOrder()
                                .thenComparingDouble(SmartSeatCandidates.Block::centerDistance)
                                .thenComparing(SmartSeatCandidates.Block::row).thenComparing(SmartSeatCandidates.Block::segment)
                                .thenComparingInt(SmartSeatCandidates.Block::firstPosition));
                if (best.isEmpty()) continue; // Allocate in number order among requests whose conditions currently match.
                BookingZoneLocks.finish(em, shows);
                holds.acquireWaiting(group.getUser().getId(), group.getId(), showId, best.get().seatIds(), lockedInventory);
                NotificationService.waitingAcquired(em, group);
                allocated++;
            }
            return allocated;
        });
    }

}
