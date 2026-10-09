package smartticketing.service;

import jakarta.persistence.EntityManager;
import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;
import java.util.*;

/** Request-local immutable geometry; never retain entities across retry transactions. */
final class SmartCandidateSeats {
    private final Map<Long, List<SmartSeatCandidates.SeatData>> layouts = new HashMap<>();
    record Snapshot(List<SmartSeatCandidates.SeatData> current, List<SmartSeatCandidates.SeatData> capacity) {}

    Map<Long, Snapshot> read(EntityManager em, List<Long> shows, Collection<SeatPosition> zones) {
        var result = new HashMap<Long, Snapshot>();
        for (int start = 0; start < shows.size(); start += 100) {
            var batch = shows.subList(start, Math.min(start + 100, shows.size()));
            var missing = batch.stream().filter(id -> !layouts.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                var geometry = new HashMap<Long, List<SmartSeatCandidates.SeatData>>();
                missing.forEach(id -> geometry.put(id, new ArrayList<>()));
                var rows = em.createQuery("""
                        select i.showtime.id, s.id, s.seatRow, s.seatNumber, s.seatPosition,
                               s.adjacencySegment, s.positionInSegment
                        from ShowtimeSeat i join i.seat s
                        where i.showtime.id in :shows and s.active=true and s.screen.id=i.showtime.screen.id
                        """, Object[].class).setParameter("shows", missing).getResultList();
                for (var row : rows) geometry.get((Long) row[0]).add(new SmartSeatCandidates.SeatData(
                        (Long) row[1], (String) row[2], (Integer) row[3], (SeatPosition) row[4],
                        (String) row[5], (Integer) row[6], false));
                geometry.forEach((id, seats) -> layouts.put(id, List.copyOf(seats)));
            }
            var available = new HashMap<Long, Set<Long>>();
            var blocked = new HashMap<Long, Set<Long>>();
            // Occupied rows need no payload: absence means unavailable. BLOCKED affects capacity.
            var query = em.createQuery("""
                    select i.showtime.id, i.seat.id, i.status from ShowtimeSeat i
                    where i.showtime.id in :shows
                    and (i.status=:blocked or (i.status=:available and i.reservation is null and i.holdExpiredAt is null))
                    """ + (zones == null ? "" : " and i.seat.seatPosition in :zones"), Object[].class)
                    .setParameter("shows", batch).setParameter("blocked", SeatStatus.BLOCKED)
                    .setParameter("available", SeatStatus.AVAILABLE);
            if (zones != null) query.setParameter("zones", zones);
            for (var row : query.getResultList()) {
                var target = row[2] == SeatStatus.BLOCKED ? blocked : available;
                target.computeIfAbsent((Long) row[0], ignored -> new HashSet<>()).add((Long) row[1]);
            }
            for (var show : batch) {
                var free = available.getOrDefault(show, Set.of());
                var excluded = blocked.getOrDefault(show, Set.of());
                var layout = layouts.get(show);
                result.put(show, new Snapshot(
                        layout.stream().map(seat -> seat.withAvailability(free.contains(seat.id()))).toList(),
                        layout.stream().filter(seat -> !excluded.contains(seat.id()) && (zones == null || zones.contains(seat.zone())))
                                .map(seat -> seat.withAvailability(true)).toList()));
            }
        }
        return result;
    }
}
