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
                            OffsetDateTime startTime, long aheadCount, SeatPosition seatZone, int queueNumber, List<String> seatLabels) {}
    public record Item(Long id, String movieTitle, int partySize, String kind,
                       ReservationView reservation, List<QueueView> queues, BookingEntryPoint entryPoint, String candidateKind, SeatPosition zone) {}

    public record HistoryItem(String id, Long groupId, String movieTitle, int partySize, BookingEntryPoint entryPoint,
                              String kind, String status, SeatPosition zone, String theaterName, String screenName,
                              OffsetDateTime startTime) {}
    public record HistoryPage(List<HistoryItem> items, Long nextBefore) {}

    public HistoryPage history(Long userId, Long before) {
        holds.requireUser(userId);
        if (before != null && before < 1) throw new IllegalArgumentException("잘못된 페이지입니다.");
        var now = holds.now();
        // Include elapsed records even before the lifecycle worker persists their expiry.
        var endedReservation = "(r.status in :reservations or (r.status=:pending and (r.expiresAt<=:now or r.showtime.startTime<=:now or r.showtime.status<>:scheduled)))";
        var endedQueue = "(q.status in :queues or (q.status in :liveQueues and (q.showtime.startTime<=:now or q.showtime.status<>:scheduled)))"
                + " and not exists (select r.id from Reservation r where r.requestGroup=q.requestGroup and r.showtime=q.showtime)";
        var groups = em.createQuery("select g from BookingRequestGroup g join fetch g.movie where g.user.id=:user and g.id<:before and ("
                + "exists (select r.id from Reservation r where r.requestGroup=g and " + endedReservation + ") or "
                + "exists (select q.id from WaitingQueue q where q.requestGroup=g and " + endedQueue + ")) order by g.id desc", BookingRequestGroup.class)
                .setParameter("user", userId).setParameter("before", before == null ? Long.MAX_VALUE : before)
                .setParameter("reservations", List.of(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED))
                .setParameter("pending", ReservationStatus.PENDING).setParameter("now", now).setParameter("scheduled", ShowtimeStatus.SCHEDULED)
                .setParameter("queues", List.of(QueueStatus.CANCELLED, QueueStatus.EXPIRED))
                .setParameter("liveQueues", List.of(QueueStatus.WAITING, QueueStatus.PAUSED)).setMaxResults(21).getResultList();
        if (groups.isEmpty()) return new HistoryPage(List.of(), null);
        var ids = groups.stream().limit(20).map(BookingRequestGroup::getId).toList();
        var reservations = em.createQuery("select r from Reservation r join fetch r.requestGroup g join fetch g.movie "
                + "join fetch r.showtime s join fetch s.screen c join fetch c.theater where g.id in :ids and " + endedReservation
                + " order by g.id desc, r.id desc", Reservation.class)
                .setParameter("ids", ids).setParameter("reservations", List.of(ReservationStatus.CANCELLED, ReservationStatus.EXPIRED))
                .setParameter("pending", ReservationStatus.PENDING).setParameter("now", now).setParameter("scheduled", ShowtimeStatus.SCHEDULED).getResultList();
        var queues = em.createQuery("select q from WaitingQueue q join fetch q.requestGroup g join fetch g.movie "
                + "join fetch q.showtime s join fetch s.screen c join fetch c.theater where g.id in :ids and " + endedQueue
                + " order by g.id desc, q.id desc", WaitingQueue.class)
                .setParameter("ids", ids).setParameter("queues", List.of(QueueStatus.CANCELLED, QueueStatus.EXPIRED))
                .setParameter("liveQueues", List.of(QueueStatus.WAITING, QueueStatus.PAUSED))
                .setParameter("now", now).setParameter("scheduled", ShowtimeStatus.SCHEDULED).getResultList();
        var items = new ArrayList<HistoryItem>();
        for (var r : reservations) items.add(historyItem("reservation-" + r.getId(), r.getRequestGroup(), r.getShowtime(),
                "holding", r.getStatus() == ReservationStatus.CANCELLED ? "CANCELLED" : "EXPIRED", r.getRequestGroup().getCandidateZone()));
        for (var q : queues) items.add(historyItem("queue-" + q.getId(), q.getRequestGroup(), q.getShowtime(),
                "waiting", q.getStatus() == QueueStatus.CANCELLED ? "CANCELLED" : "EXPIRED", q.getSeatZone()));
        items.sort(Comparator.comparing(HistoryItem::groupId).reversed());
        return new HistoryPage(List.copyOf(items), groups.size() > 20 ? ids.getLast() : null);
    }

    private HistoryItem historyItem(String id, BookingRequestGroup group, Showtime show, String kind, String status, SeatPosition zone) {
        return new HistoryItem(id, group.getId(), group.getMovie().getTitle(), group.getPartySize(), group.getEntryPoint(),
                kind, status, zone, show.getScreen().getTheater().getName(), show.getScreen().getName(), offset(show.getStartTime()));
    }

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
                    and (a.seatZone=q.seatZone or (a.seatZone is null and q.seatZone is null))
                    where q.id in :ids group by q.id
                    """, Object[].class).setParameter("waiting", QueueStatus.WAITING)
                    .setParameter("ids", queues.stream().map(WaitingQueue::getId).toList()).getResultList();
            for (var row : counts) ahead.put((Long) row[0], (Long) row[1]);
        }
        var exactQueues = queues.stream().filter(q -> q.getRequestGroup().getStatus() == BookingGroupStatus.ACTIVE)
                .filter(q -> !q.getRequestedSeatIds().isEmpty()).toList();
        var requestedIds = exactQueues.stream().flatMap(q -> q.getRequestedSeatIds().stream()).distinct().toList();
        var requestedSeats = requestedIds.isEmpty() ? Map.<Long, Seat>of() : em.createQuery(
                "select s from Seat s where s.id in :ids", Seat.class).setParameter("ids", requestedIds).getResultList()
                .stream().collect(Collectors.toMap(Seat::getId, s -> s));
        if (!requestedIds.isEmpty()) {
            var others = em.createQuery("select q from WaitingQueue q where q.showtime.id in :shows and q.requestGroup is not null and q.status=:waiting", WaitingQueue.class)
                    .setParameter("shows", exactQueues.stream().map(q -> q.getShowtime().getId()).distinct().toList())
                    .setParameter("waiting", QueueStatus.WAITING).getResultList();
            for (var q : exactQueues) {
                var zones = q.getRequestedSeatIds().stream().map(requestedSeats::get).filter(Objects::nonNull)
                        .map(Seat::getSeatPosition).collect(Collectors.toSet());
                ahead.put(q.getId(), others.stream().filter(a -> a.getShowtime().getId().equals(q.getShowtime().getId())
                        && a.getQueueNumber() < q.getQueueNumber() && BookingQueueLifecycle.competing(a, q.getRequestedSeatIds(), zones)).count());
            }
        }
        var result = new LinkedHashMap<Long, Item>();
        for (var slot : slots) {
            var group = slot.getRequestGroup();
            var r = slot.getReservation();
            var show = r.getShowtime();
            result.put(group.getId(), new Item(group.getId(), group.getMovie().getTitle(), group.getPartySize(), "holding",
                    new ReservationView(r.getId(), show.getScreen().getTheater().getName(), show.getScreen().getName(),
                            offset(show.getStartTime()), seatLabels.getOrDefault(r.getId(), List.of()),
                            offset(r.getExpiresAt()), offset(now)), List.of(), group.getEntryPoint(),
                    group.getCandidateKind(), group.getCandidateZone()));
        }
        for (var queue : queues) {
            var group = queue.getRequestGroup();
            var item = result.computeIfAbsent(group.getId(), id -> new Item(id, group.getMovie().getTitle(),
                    group.getPartySize(), "waiting", null, new ArrayList<>(), group.getEntryPoint(),
                    group.getCandidateKind(), group.getCandidateZone()));
            if (item.kind().equals("holding")) continue;
            var show = queue.getShowtime();
            item.queues().add(new QueueView(queue.getId(), queue.getStatus(), show.getScreen().getTheater().getName(),
                    show.getScreen().getName(), offset(show.getStartTime()), ahead.getOrDefault(queue.getId(), 0L), queue.getSeatZone(), queue.displayNumber(),
                    queue.getRequestedSeatIds().stream().map(requestedSeats::get).filter(Objects::nonNull)
                            .map(s -> s.getSeatRow() + s.getSeatNumber()).toList()));
        }
        return List.copyOf(result.values());
    }

    private static OffsetDateTime offset(LocalDateTime value) { return value.atOffset(ZoneOffset.ofHours(9)); }
}
