package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.ShowtimeResponse.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ShowtimeQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final EntityManager em;
    private final BookingCatalogService catalog;
    private final Clock clock;

    public ShowtimeQueryService(EntityManager em, BookingCatalogService catalog,
            @Qualifier("bookingQueryClock") Clock clock) {
        this.em = em; this.catalog = catalog; this.clock = clock.withZone(SEOUL);
    }

    public Items<MovieItem> theaterMovies(Long theaterId, LocalDate date) {
        catalog.requireTheater(theaterId);
        validateDate(date);
        var now = LocalDateTime.now(clock);
        var movies = em.createQuery("""
                select distinct m from Showtime s join s.movie m join s.screen c
                where c.theater.id = :theater and m.active = true and c.active = true
                and s.status = :status and s.startTime >= :from and s.startTime < :until
                and s.startTime > :now order by m.id
                """, Movie.class).setParameter("theater", theaterId).setParameter("status", ShowtimeStatus.SCHEDULED)
                .setParameter("from", date.atStartOfDay()).setParameter("until", date.plusDays(1).atStartOfDay())
                .setParameter("now", now).getResultList();
        return new Items<>(movies.stream().map(m -> new MovieItem(m.getId(), m.getTitle(), m.getPosterUrl(),
                m.getRunningTime(), m.getRating())).toList(), offset(now));
    }

    public Items<ShowtimeItem> showtimes(Long movieId, Long theaterId, LocalDate date,
            LocalTime startFrom, LocalTime startUntil) {
        validateDate(date);
        if ((startFrom == null) != (startUntil == null))
            throw new IllegalArgumentException("시작 시간 범위의 양 끝을 함께 입력해주세요.");
        if (startFrom != null && startFrom.equals(startUntil))
            throw new IllegalArgumentException("시작 시간 범위의 양 끝은 달라야 합니다. 하루 전체는 범위를 생략해주세요.");
        if (movieId == null && theaterId == null)
            throw new IllegalArgumentException("movieId 또는 theaterId가 필요합니다.");
        if (movieId != null) catalog.requireMovie(movieId);
        if (theaterId != null) catalog.requireTheater(theaterId);
        var from = startFrom == null ? date.atStartOfDay() : date.atTime(startFrom);
        var until = startUntil == null ? date.plusDays(1).atStartOfDay() : date.atTime(startUntil);
        if (!until.isAfter(from)) until = until.plusDays(1);
        var now = LocalDateTime.now(clock);
        String filters = (movieId == null ? "" : " and m.id = :movie")
                + (theaterId == null ? "" : " and t.id = :theater");
        var request = em.createQuery("""
                select s from Showtime s join fetch s.movie m join fetch s.screen c join fetch c.theater t
                where m.active = true and c.active = true and t.active = true and s.status = :status
                and s.startTime >= :from and s.startTime < :until and s.startTime > :now
                """ + filters + " order by s.startTime, s.id", Showtime.class)
                .setParameter("status", ShowtimeStatus.SCHEDULED).setParameter("from", from)
                .setParameter("until", until).setParameter("now", now);
        if (movieId != null) request.setParameter("movie", movieId);
        if (theaterId != null) request.setParameter("theater", theaterId);
        var shows = request.getResultList();
        var seats = inventory(shows.stream().map(Showtime::getId).toList());
        var items = shows.stream().map(s -> {
            var list = seats.getOrDefault(s.getId(), List.of());
            var availability = summarize(list);
            return new ShowtimeItem(s.getId(), s.getMovie().getId(), s.getScreen().getTheater().getId(),
                    s.getScreen().getId(), s.getScreen().getName(), offset(s.getStartTime()), offset(s.getEndTime()),
                    s.getEndTime().toLocalDate().isAfter(s.getStartTime().toLocalDate()), s.getPricePerPerson(),
                    list.size(), availability.available, availability.maxContiguous, availability.layoutComplete, s.getStatus(), availability.bookableParties);
        }).toList();
        return new Items<>(items, offset(now));
    }

    private static void validateDate(LocalDate date) {
        if (date == null || date.getYear() < 1000 || date.getYear() > 9998)
            throw new IllegalArgumentException("날짜는 1000~9998년 범위로 입력해주세요.");
    }

    public SeatMap seats(Long showtimeId) {
        BookingCatalogService.positiveId(showtimeId);
        var now = LocalDateTime.now(clock);
        var result = em.createQuery("""
                select s from Showtime s join fetch s.movie m join fetch s.screen c join fetch c.theater t
                where s.id = :id and m.active = true and c.active = true and t.active = true
                """, Showtime.class).setParameter("id", showtimeId).getResultList();
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다.");
        var showtime = result.getFirst();
        if (showtime.getStatus() != ShowtimeStatus.SCHEDULED || !showtime.getStartTime().isAfter(now))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "예매가 종료된 회차입니다.");
        var inventory = inventory(List.of(showtimeId)).getOrDefault(showtimeId, List.of());
        var summary = summarize(inventory);
        return new SeatMap(showtimeId, inventory, inventory.size(), summary.available,
                summary.maxContiguous, summary.layoutComplete, offset(now));
    }

    // 여러 회차의 좌석 정보를 한 번에 읽어 회차 수에 따른 N+1 조회를 방지한다.
    private Map<Long, List<SeatItem>> inventory(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = em.createQuery("""
                select s from ShowtimeSeat s join fetch s.seat seat
                where s.showtime.id in :ids and seat.active = true and seat.screen.id = s.showtime.screen.id
                order by s.showtime.id, seat.seatRow, seat.seatNumber, seat.id
                """, ShowtimeSeat.class).setParameter("ids", ids).getResultList();
        Map<Long, List<SeatItem>> grouped = new HashMap<>();
        for (var row : rows) {
            var seat = row.getSeat();
            grouped.computeIfAbsent(row.getShowtime().getId(), ignored -> new ArrayList<>())
                    .add(new SeatItem(seat.getId(), seat.getSeatRow(), seat.getSeatNumber(), seat.getAdjacencySegment(),
                            seat.getPositionInSegment(), seat.getSeatPosition(), row.getStatus()));
        }
        return grouped;
    }

    private record Segment(String row, String segment) {}
    private record Availability(long available, int maxContiguous, boolean layoutComplete, List<Integer> bookableParties) {}

    private static Availability summarize(List<SeatItem> seats) {
        long available = 0;
        boolean complete = !seats.isEmpty();
        Map<Segment, SortedMap<Integer, Boolean>> segments = new HashMap<>();
        for (var seat : seats) {
            if (seat.status() == SeatStatus.AVAILABLE) available++;
            if (seat.segment() == null || seat.segment().isBlank() || seat.positionInSegment() == null
                    || seat.positionInSegment() < 1) { complete = false; continue; }
            var positions = segments.computeIfAbsent(new Segment(seat.row(), seat.segment()), ignored -> new TreeMap<>());
            // 잘못된 중복 위치는 연속 좌석 가능으로 계산하지 않는다.
            boolean duplicate = positions.containsKey(seat.positionInSegment());
            if (duplicate) complete = false;
            positions.put(seat.positionInSegment(), !duplicate && seat.status() == SeatStatus.AVAILABLE);
        }
        int longest = available > 0 ? 1 : 0;
        var runs = new ArrayList<Integer>();
        for (var positions : segments.values()) {
            int run = 0, previous = -1;
            for (var entry : positions.entrySet()) {
                if (entry.getValue() && (run == 0 || entry.getKey() == previous + 1)) run++;
                else {
                    if (run > 0) runs.add(run);
                    run = entry.getValue() ? 1 : 0;
                }
                longest = Math.max(longest, run); previous = entry.getKey();
            }
            if (run > 0) runs.add(run);
        }
        return new Availability(available, longest, complete, complete ? SeatPartyRules.bookableParties(runs) : List.of());
    }

    private static OffsetDateTime offset(LocalDateTime time) { return time.atZone(SEOUL).toOffsetDateTime(); }
}
