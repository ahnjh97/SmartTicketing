package smartticketing.service;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.Movie;
import smartticketing.entity.Screen;
import smartticketing.entity.Showtime;
import smartticketing.entity.Theater;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ShowtimeScheduleSeedService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int ROUNDS = 7;
    private static final int LINEUP_SIZE = 10;
    private static final int AD_MINUTES = 10;
    private static final int CLEANING_MINUTES = 20;
    private static final LocalTime FIRST_START = LocalTime.of(8, 0);
    private static final LocalTime LAST_END = LocalTime.of(3, 30);   // 마지막 회차 종료 한도 (다음 날 03:30)
    private static final int PLANNED_SEATS = 120;
    private static final int PRICE = 10_000;

    private final EntityManager em;
    private final int screenCount;
    private final int days;

    public ShowtimeScheduleSeedService(
            EntityManager em,
            @Value("${showtime.seed.screen-count:10}") int screenCount,
            @Value("${showtime.seed.days:7}") int days) {
        this.em = em;
        this.screenCount = screenCount;
        this.days = days;
    }

    public List<Long> findActiveTheaterIds() {
        return em.createQuery("select t.id from Theater t where t.active = true order by t.id", Long.class)
                .getResultList();
    }

    @Transactional
    public int seedTheater(Long theaterId) {
        Theater theater = em.find(Theater.class, theaterId);
        if (theater == null || !theater.isActive()) return 0;

        List<Movie> movies = em.createQuery(
                        "select m from Movie m where m.active = true and m.releaseDate is not null and m.runningTime > 0",
                        Movie.class)
                .getResultList();
        if (movies.isEmpty()) return 0;

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDate today = now.toLocalDate();
        int created = 0;

        for (int screenNo = 1; screenNo <= screenCount; screenNo++) {
            Screen screen = findOrCreateScreen(theater, screenNo);
            if (screen == null) continue;

            Set<LocalDateTime> existingStarts = new HashSet<>(em.createQuery(
                            "select s.startTime from Showtime s where s.screen.id = :screen and s.startTime >= :from and s.startTime < :to",
                            LocalDateTime.class)
                    .setParameter("screen", screen.getId())
                    .setParameter("from", today.atStartOfDay())
                    .setParameter("to", today.plusDays(days + 1L).atStartOfDay())
                    .getResultList());

            // 첫 회차 시작: 08:00 + 극장·상영관 기준 0~50분
            int startOffset = Math.floorMod((theater.getKakaoPlaceId() + "|" + screenNo).hashCode(), 6) * 10;

            for (int day = 0; day < days; day++) {
                LocalDate date = today.plusDays(day);
                List<Movie> lineup = lineupOf(movies, date);
                if (lineup.isEmpty()) continue;

                int base = Math.floorMod(theater.getKakaoPlaceId().hashCode(), lineup.size());
                Movie movie = lineup.get((base + screenNo - 1) % lineup.size());

                LocalDateTime start = date.atTime(FIRST_START).plusMinutes(startOffset);
                LocalDateTime lastEnd = date.plusDays(1).atTime(LAST_END);
                for (int round = 0; round < ROUNDS; round++) {
                    LocalDateTime end = start.plusMinutes(movie.getRunningTime() + AD_MINUTES);
                    if (end.isAfter(lastEnd)) break;   // 새벽 3시 30분 넘게 끝나면 그날 상영 종료
                    if (start.isAfter(now) && !existingStarts.contains(start)) {
                        createShowtime(screen, movie, start, end, now);
                        created++;
                    }
                    start = roundUpTo5(end.plusMinutes(CLEANING_MINUTES));
                }
            }
        }
        em.flush();
        em.clear();
        return created;
    }

    private List<Movie> lineupOf(List<Movie> movies, LocalDate date) {
        return movies.stream()
                .filter(m -> !m.getReleaseDate().isAfter(date))
                .sorted(Comparator.comparing(Movie::getReleaseDate).reversed()
                        .thenComparing(Movie::getTmdbMovieId))
                .limit(LINEUP_SIZE)
                .sorted(Comparator.comparing(Movie::getTmdbMovieId))
                .toList();
    }

    private LocalDateTime roundUpTo5(LocalDateTime time) {
        return time.plusMinutes(Math.floorMod(-time.getMinute(), 5)).withSecond(0).withNano(0);
    }

    private Screen findOrCreateScreen(Theater theater, int screenNo) {
        String name = screenNo + "관";
        String key = "schedule-v1-" + screenNo;
        List<Screen> matches = em.createQuery(
                        "select s from Screen s where s.theater.id = :theater and (s.seedKey = :key or s.name = :name)",
                        Screen.class)
                .setParameter("theater", theater.getId())
                .setParameter("key", key)
                .setParameter("name", name)
                .getResultList();

        if (matches.isEmpty()) {
            Screen screen = new Screen();
            screen.setTheater(theater);
            screen.setName(name);
            screen.setSeedKey(key);
            em.persist(screen);
            return screen;
        }
        return matches.stream()
                .filter(s -> key.equals(s.getSeedKey()) && s.isActive())
                .findFirst()
                .orElseGet(() -> {
                    log.warn("기존 상영관 보존, 시간표 생성 제외 - theaterId: {}, 상영관: {}", theater.getId(), name);
                    return null;
                });
    }

    private void createShowtime(Screen screen, Movie movie, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        Showtime showtime = new Showtime();
        showtime.setScreen(screen);
        showtime.setMovie(movie);
        showtime.setStartTime(start);
        showtime.setEndTime(end);
        showtime.setPricePerPerson(PRICE);
        showtime.setTotalSeats(PLANNED_SEATS);
        showtime.setAvailableSeats(PLANNED_SEATS);
        showtime.setCreatedAt(now);
        showtime.setUpdatedAt(now);
        em.persist(showtime);
    }
}