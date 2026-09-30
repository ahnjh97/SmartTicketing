package smartticketing.service;

import smartticketing.dto.booking.BookingSeedResult;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** 기존 데이터를 수정/삭제하지 않고, 명시적으로 소유한 테스트 상영관에 미래 회차만 추가한다. */
@Service
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "booking.seed.enabled", havingValue = "true")
public class BookingSeedService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String[] SCENARIOS = {"normal", "sold-out", "fragmented"};
    private final EntityManager em;
    private final Clock clock;

    public BookingSeedService(EntityManager em, Clock bookingSeedClock) {
        this.em = em;
        this.clock = bookingSeedClock;
    }

    @Transactional
    public BookingSeedResult seed(List<Long> theaterIds) {
        if (theaterIds == null || theaterIds.isEmpty() || theaterIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("booking.seed.theater-ids에 기존 극장 ID를 지정해주세요.");
        }
        // 동일 극장의 동시 초기화를 직렬화한다. 어떤 쓰기도 하기 전에 모든 ID를 검증한다.
        List<Theater> theaters = new ArrayList<>();
        for (Long id : theaterIds.stream().distinct().sorted().toList()) {
            var theater = em.find(Theater.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (theater == null || !theater.isActive()) {
                throw new IllegalArgumentException("활성 극장을 찾을 수 없습니다: " + id);
            }
            theaters.add(theater);
        }
        var activeMovies = em.createQuery("select m from Movie m where m.active = true order by m.id", Movie.class).getResultList();
        var missingRuntime = activeMovies.stream().filter(m -> m.getRunningTime() == null || m.getRunningTime() <= 0)
                .map(Movie::getId).toList();
        var movies = activeMovies.stream().filter(m -> m.getRunningTime() != null && m.getRunningTime() > 0).toList();
        var warnings = new ArrayList<String>();
        movies.stream().filter(m -> m.getRunningTime() > 14 * 60)
                .forEach(m -> warnings.add("테스트 운영시간(10~24시)보다 긴 영화 제외: " + m.getId()));
        if (movies.isEmpty()) {
            warnings.add("유효한 상영시간이 있는 활성 영화가 없어 생성하지 않았습니다.");
            return new BookingSeedResult(0, 0, 0, 0, missingRuntime, warnings);
        }
        int[] counts = new int[4];
        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        for (Theater theater : theaters) {
            for (String scenario : SCENARIOS) {
                String name = "[테스트] " + scenario;
                String key = "booking-v1-" + scenario;
                var matches = em.createQuery("select s from Screen s where s.theater.id = :theater and (s.seedKey = :key or s.name = :name)", Screen.class)
                        .setParameter("theater", theater.getId()).setParameter("key", key).setParameter("name", name).getResultList();
                Screen screen;
                List<Seat> seats;
                if (matches.isEmpty()) {
                    screen = new Screen();
                    screen.setTheater(theater);
                    screen.setName(name);
                    screen.setSeedKey(key);
                    em.persist(screen);
                    seats = createSeats(screen);
                    counts[0]++;
                    counts[1] += seats.size();
                } else {
                    screen = matches.stream().filter(s -> key.equals(s.getSeedKey())).findFirst().orElse(matches.getFirst());
                    if (!key.equals(screen.getSeedKey()) || !screen.isActive()) {
                        warnings.add("기존/비활성 상영관 보존: " + screen.getId());
                        continue;
                    }
                    seats = em.createQuery("select s from Seat s where s.screen.id = :screen order by s.seatRow, s.seatNumber", Seat.class)
                            .setParameter("screen", screen.getId()).getResultList();
                    if (!validLayout(seats)) {
                        warnings.add("수정된 좌석 배치를 보존하고 생성 제외: " + screen.getId());
                        continue;
                    }
                }
                var existing = em.createQuery("select s from Showtime s where s.screen.id = :screen and s.startTime < :end and s.endTime > :start", Showtime.class)
                        .setParameter("screen", screen.getId())
                        .setParameter("start", now.toLocalDate().atStartOfDay().minusMinutes(20))
                        .setParameter("end", now.toLocalDate().plusDays(7).atStartOfDay().plusMinutes(20)).getResultList();
                for (int day = 0; day < 7; day++) {
                    LocalDate date = now.toLocalDate().plusDays(day);
                    LocalDateTime start = date.atTime(10, 0);
                    int offset = Math.floorMod(date.toEpochDay() + theater.getId(), movies.size());
                    // 10시부터 자정까지 실제 러닝타임 + 정리시간 20분으로 배치한다.
                    for (int slot = 0; slot < movies.size(); slot++) {
                        Movie movie = movies.get((offset + slot) % movies.size());
                        LocalDateTime end = start.plusMinutes(movie.getRunningTime());
                        if (end.isAfter(date.plusDays(1).atStartOfDay())) continue;
                        if (start.isAfter(now) && !overlaps(existing, start, end)) {
                            createShowtime(screen, movie, seats, scenario, start, end, now);
                            counts[2]++;
                            counts[3] += seats.size();
                        }
                        start = end.plusMinutes(20);
                    }
                }
            }
        }
        em.flush();
        return new BookingSeedResult(counts[0], counts[1], counts[2], counts[3], missingRuntime, List.copyOf(warnings));
    }

    private List<Seat> createSeats(Screen screen) {
        var result = new ArrayList<Seat>();
        for (int row = 0; row < 9; row++) {
            for (int number = 1; number <= 12; number++) {
                var seat = new Seat();
                seat.setScreen(screen);
                seat.setSeatRow(String.valueOf((char) ('A' + row)));
                seat.setSeatNumber(number);
                seat.setSeatPosition(position(row, number));
                seat.setAdjacencySegment(segment(number));
                seat.setPositionInSegment(segmentPosition(number));
                em.persist(seat);
                result.add(seat);
            }
        }
        return result;
    }

    private boolean validLayout(List<Seat> seats) {
        if (seats.size() != 108) return false;
        for (int i = 0; i < seats.size(); i++) {
            var seat = seats.get(i);
            int row = i / 12, number = i % 12 + 1;
            if (!seat.isActive() || !String.valueOf((char) ('A' + row)).equals(seat.getSeatRow())
                    || !Integer.valueOf(number).equals(seat.getSeatNumber())
                    || seat.getSeatPosition() != position(row, number)
                    || !segment(number).equals(seat.getAdjacencySegment())
                    || !Integer.valueOf(segmentPosition(number)).equals(seat.getPositionInSegment())) return false;
        }
        return true;
    }

    private SeatPosition position(int row, int number) {
        String side = number >= 4 && number <= 9 ? "MIDDLE" : "SIDE";
        return SeatPosition.valueOf(side + "_" + (row < 3 ? "FRONT" : row < 6 ? "MIDDLE" : "REAR"));
    }

    private String segment(int number) { return number <= 3 ? "left" : number <= 9 ? "center" : "right"; }
    private int segmentPosition(int number) { return number <= 3 ? number : number <= 9 ? number - 3 : number - 9; }

    private boolean overlaps(List<Showtime> existing, LocalDateTime start, LocalDateTime end) {
        return existing.stream().anyMatch(s -> start.isBefore(s.getEndTime().plusMinutes(20))
                && end.plusMinutes(20).isAfter(s.getStartTime()));
    }

    private void createShowtime(Screen screen, Movie movie, List<Seat> seats, String scenario,
                                LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        var showtime = new Showtime();
        showtime.setScreen(screen);
        showtime.setMovie(movie);
        showtime.setStartTime(start);
        showtime.setEndTime(end);
        showtime.setPricePerPerson(10_000);
        showtime.setTotalSeats(seats.size());
        int available = (int) seats.stream().filter(s -> available(scenario, s)).count();
        showtime.setAvailableSeats(available);
        showtime.setCreatedAt(now);
        showtime.setUpdatedAt(now);
        em.persist(showtime);
        for (Seat seat : seats) {
            var inventory = new ShowtimeSeat();
            inventory.setShowtime(showtime);
            inventory.setSeat(seat);
            inventory.setStatus(available(scenario, seat) ? SeatStatus.AVAILABLE : SeatStatus.BLOCKED);
            em.persist(inventory);
        }
    }

    private boolean available(String scenario, Seat seat) {
        return "normal".equals(scenario) || "fragmented".equals(scenario) && seat.getPositionInSegment() % 2 == 1;
    }
}
