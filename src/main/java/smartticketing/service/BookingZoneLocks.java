package smartticketing.service;

import jakarta.persistence.EntityManager;
import smartticketing.entity.WaitingZoneSequence;
import smartticketing.entity.enums.SeatPosition;
import java.util.*;

/** Group -> sorted (show, zone) -> inventory -> short aggregate/outbox writes. */
final class BookingZoneLocks {
    private BookingZoneLocks() {}
    record Scope(Long show, SeatPosition zone) implements Comparable<Scope> {
        public int compareTo(Scope other) {
            int order = show.compareTo(other.show);
            return order == 0 ? zone.name().compareTo(other.zone.name()) : order;
        }
        String key() { return show + "_" + zone.name(); }
    }
    static Set<SeatPosition> seatZones(EntityManager em, Long show, Collection<Long> seats) {
        if (seats.isEmpty()) return Set.of();
        return new HashSet<>(em.createQuery("select distinct i.seat.seatPosition from ShowtimeSeat i where i.showtime.id=:show and i.seat.id in :seats", SeatPosition.class)
                .setParameter("show", show).setParameter("seats", seats).getResultList());
    }
    static SortedSet<Scope> groupScopes(EntityManager em, Long group) {
        var scopes = new TreeSet<Scope>();
        // The caller owns the group lock. READ_COMMITTED avoids an old pre-lock queue snapshot.
        for (var row : em.createQuery("select q.showtime.id,q.seatZone from WaitingQueue q where q.requestGroup.id=:group", Object[].class)
                .setParameter("group", group).getResultList()) add(scopes, (Long) row[0], (SeatPosition) row[1]);
        return scopes;
    }
    static void add(Set<Scope> scopes, Long show, SeatPosition zone) {
        // Legacy waits without a zone compete with all zones until they are drained.
        if (zone == null) for (var value : SeatPosition.values()) scopes.add(new Scope(show, value));
        else scopes.add(new Scope(show, zone));
    }
    static SortedSet<Scope> lockGroup(EntityManager em, Long group, Long show, Collection<SeatPosition> zones) {
        var scopes = groupScopes(em, group);
        if (show != null) for (var zone : zones) add(scopes, show, zone);
        if (show != null && !em.createQuery("select q.id from WaitingQueue q where q.showtime.id=:show and q.seatZone is null and q.status=smartticketing.entity.enums.QueueStatus.WAITING", Long.class)
                .setParameter("show", show).setMaxResults(1).getResultList().isEmpty()) add(scopes, show, null);
        lock(em, scopes);
        return scopes;
    }
    static void lock(EntityManager em, Collection<Scope> scopes) {
        for (var scope : new TreeSet<>(scopes)) lockCounter(em, scope.key());
    }
    static void lockCounter(EntityManager em, String key) {
        // Atomic upsert also serializes creation of the first queue number in a zone.
        em.createNativeQuery("insert into waiting_zone_sequences (id,last_number) values (:id,0) on duplicate key update id=id")
                .setParameter("id", key).executeUpdate();
    }
    static WaitingZoneSequence counter(EntityManager em, String key) {
        lockCounter(em, key);
        var counter = em.find(WaitingZoneSequence.class, key);
        em.refresh(counter);
        return counter;
    }
    static void finish(EntityManager em, Collection<Long> shows) {
        // Only after validation. Serialize the shared summary/legacy number/outbox tail in ID order.
        // Do this before FK inserts, avoiding concurrent shared-FK -> exclusive-counter upgrades.
        for (var show : new TreeSet<>(shows)) em.createNativeQuery("update showtimes set available_seats=available_seats where id=:show")
                .setParameter("show", show).executeUpdate();
    }
    static void finishGroup(EntityManager em, Long group, Long extra) {
        var shows = BookingQueueLifecycle.showIds(em, group);
        if (extra != null) shows.add(extra);
        finish(em, shows);
    }
}
