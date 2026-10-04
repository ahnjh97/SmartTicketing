package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only overview. Payment/recovery still performs authoritative locked validation. */
@Service
@Transactional(readOnly = true)
public class BookingActivityService {
    private final EntityManager em;
    private final BookingHoldService holds;
    public BookingActivityService(EntityManager em, BookingHoldService holds) { this.em = em; this.holds = holds; }

    public record ReservationView(Long id, String theaterName, String screenName, OffsetDateTime startTime,
                                  List<String> seatLabels, OffsetDateTime expiresAt, OffsetDateTime serverTime) {}
    public record QueueView(Long id, QueueStatus status, String theaterName, String screenName,
                            OffsetDateTime startTime, long aheadCount) {}
    public record Item(Long id, String movieTitle, int partySize, String kind,
                       ReservationView reservation, List<QueueView> queues) {}

    public List<Item> active(Long userId) {
        holds.requireUser(userId);
        var now = holds.now();
        var slots = em.createQuery("""
                select h from BookingGroupHold h
                join fetch h.requestGroup g join fetch g.movie
                join fetch h.reservation r join fetch r.showtime s
                join fetch s.screen c join fetch c.theater
                where g.user.id=:user and g.status=:holding and r.status=:pending
                and h.expiresAt>:now and r.expiresAt>:now and s.startTime>:now and s.status=:scheduled
                order by h.expiresAt, h.id
                """, BookingGroupHold.class)
                .setParameter("user", userId).setParameter("holding", BookingGroupStatus.HOLDING)
                .setParameter("pending", ReservationStatus.PENDING).setParameter("now", now)
                .setParameter("scheduled", ShowtimeStatus.SCHEDULED).getResultList();
        var seats = slots.isEmpty() ? List.<ReservationSeat>of() : em.createQuery("""
                select rs from ReservationSeat rs join fetch rs.seat
                where rs.reservation.id in :ids order by rs.seat.seatRow, rs.seat.seatNumber
                """, ReservationSeat.class).setParameter("ids", slots.stream().map(h -> h.getReservation().getId()).toList()).getResultList();
        var seatLabels = seats.stream().collect(Collectors.groupingBy(rs -> rs.getReservation().getId(),
                Collectors.mapping(rs -> rs.getSeat().getSeatRow() + rs.getSeat().getSeatNumber(), Collectors.toList())));

        var queues = em.createQuery("""
                select q from WaitingQueue q join fetch q.requestGroup g join fetch g.movie
                join fetch q.showtime s join fetch s.screen c join fetch c.theater
                where g.user.id=:user and g.status in :groups and q.status in :states
                and s.startTime>:now and s.status=:scheduled order by g.id desc, s.startTime, q.id
                """, WaitingQueue.class).setParameter("user", userId)
                .setParameter("groups", List.of(BookingGroupStatus.ACTIVE, BookingGroupStatus.HOLDING))
                .setParameter("states", List.of(QueueStatus.WAITING, QueueStatus.PAUSED))
                .setParameter("now", now).setParameter("scheduled", ShowtimeStatus.SCHEDULED).getResultList();
        var ahead = new HashMap<Long, Long>();
        if (!queues.isEmpty()) {
            // Count all requested positions together without loading/locking people ahead.
            var counts = em.createQuery("""
                    select q.id, count(a.id) from WaitingQueue q
                    left join WaitingQueue a on a.showtime.id=q.showtime.id
                    and a.requestGroup is not null and a.status=:waiting and a.queueNumber<q.queueNumber
                    where q.id in :ids group by q.id
                    """, Object[].class).setParameter("waiting", QueueStatus.WAITING)
                    .setParameter("ids", queues.stream().map(WaitingQueue::getId).toList()).getResultList();
            for (var row : counts) ahead.put((Long) row[0], (Long) row[1]);
        }
        var result = new LinkedHashMap<Long, Item>();
        for (var slot : slots) {
            var group = slot.getRequestGroup();
            var r = slot.getReservation();
            var show = r.getShowtime();
            result.put(group.getId(), new Item(group.getId(), group.getMovie().getTitle(), group.getPartySize(), "holding",
                    new ReservationView(r.getId(), show.getScreen().getTheater().getName(), show.getScreen().getName(),
                            offset(show.getStartTime()), seatLabels.getOrDefault(r.getId(), List.of()),
                            offset(r.getExpiresAt()), offset(now)), List.of()));
        }
        for (var queue : queues) {
            var group = queue.getRequestGroup();
            var item = result.computeIfAbsent(group.getId(), id -> new Item(id, group.getMovie().getTitle(),
                    group.getPartySize(), "waiting", null, new ArrayList<>()));
            if (item.kind().equals("holding")) continue;
            var show = queue.getShowtime();
            item.queues().add(new QueueView(queue.getId(), queue.getStatus(), show.getScreen().getTheater().getName(),
                    show.getScreen().getName(), offset(show.getStartTime()), ahead.getOrDefault(queue.getId(), 0L)));
        }
        return List.copyOf(result.values());
    }

    private static OffsetDateTime offset(LocalDateTime value) { return value.atOffset(ZoneOffset.ofHours(9)); }
}
