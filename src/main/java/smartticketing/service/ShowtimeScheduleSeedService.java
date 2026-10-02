package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private static final int ROUNDS = 7;                  // 상영관마다 하루 7회
    private static final int LINEUP_SIZE = 10;            // 그날 상영하는 영화 수
    private static final int AD_MINUTES = 10;             // 광고
    private static final int CLEANING_MINUTES = 20;       // 청소
    private static final LocalTime FIRST_START = LocalTime.of(8, 0);
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
    public Result seedTheater(Long theaterId) {
        Theater theater = em.find(Theater.class, theaterId, LockModeType.PESSIMISTIC_WRITE);
        if (theater == null || !theater.isActive()) return new Result(0, 0);

        List<Movie> movies = em.createQuery(
                        "select m from Movie m where m.active = true and m.releaseDate is not null and m.runningTime > 0",
                        Movie.class)
                .getResultList().stream()
                .filter(m -> fitsSevenRounds(m.getRunningTime()))
                .toList();
        if (movies.isEmpty()) return new Result(0, 0);

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDate today = now.toLocalDate();
        int created = 0;
        int createdScreens = 0;
        var existingScreens = em.createQuery("select s from Screen s where s.theater.id = :id", Screen.class)
                .setParameter("id", theaterId).getResultList();
        var startsByScreen = new java.util.HashMap<Long, Set<LocalDateTime>>();
        em.createQuery("""
                select s.screen.id, s.startTime from Showtime s where s.screen.theater.id = :theater
                and s.startTime >= :from and s.startTime < :to
                """, Object[].class).setParameter("theater", theaterId).setParameter("from", today.atStartOfDay())
                .setParameter("to", today.plusDays(days + 1L).atStartOfDay()).getResultList()
                .forEach(row -> startsByScreen.computeIfAbsent((Long) row[0], ignored -> new HashSet<>())
                        .add((LocalDateTime) row[1]));
        var lineups = new java.util.HashMap<LocalDate, List<Movie>>();
        for (int day = 0; day < days; day++) lineups.put(today.plusDays(day), lineupOf(movies, today.plusDays(day)));
        var pending = new java.util.ArrayList<NewShowtime>();

        for (int screenNo = 1; screenNo <= screenCount; screenNo++) {
            Screen screen = findOrCreateScreen(theater, screenNo, existingScreens);
            if (screen == null) continue;
            if (screen.getId() == null) {
                em.persist(screen);
                createdScreens++;
            }

            Set<LocalDateTime> existingStarts = startsByScreen.getOrDefault(screen.getId(), Set.of());

            // 첫 회차 시작: 08:00 + 극장·상영관 기준 0~50분
            int startOffset = Math.floorMod((theater.getKakaoPlaceId() + "|" + screenNo).hashCode(), 6) * 10;

            for (int day = 0; day < days; day++) {
                LocalDate date = today.plusDays(day);
                List<Movie> lineup = lineups.get(date);
                if (lineup.isEmpty()) continue;

                int base = Math.floorMod(theater.getKakaoPlaceId().hashCode(), lineup.size());
                Movie movie = lineup.get((base + screenNo - 1) % lineup.size());

                LocalDateTime start = date.atTime(FIRST_START).plusMinutes(startOffset);
                for (int round = 0; round < ROUNDS; round++) {
                    LocalDateTime end = start.plusMinutes(movie.getRunningTime() + AD_MINUTES);
                    if (start.isAfter(now) && !existingStarts.contains(start)) {
                        pending.add(new NewShowtime(screen.getId(), movie.getId(), start, end));
                    }
                    start = roundUpTo5(end.plusMinutes(CLEANING_MINUTES));
                }
            }
        }
        em.flush();
        // IDENTITY 개별 INSERT 대신 극장별 최대 500회차씩 한 번에 저장한다.
        for (int offset = 0; offset < pending.size(); offset += 500)
            created += insertShowtimes(pending.subList(offset, Math.min(offset + 500, pending.size())), now);
        em.clear();
        return new Result(createdScreens, created);
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

    private boolean fitsSevenRounds(int runningTime) {
        int interval = roundUpTo5Minutes(runningTime + AD_MINUTES + CLEANING_MINUTES);
        int lastEnd = interval * (ROUNDS - 1) + runningTime + AD_MINUTES;
        return lastEnd + CLEANING_MINUTES <= 24 * 60;
    }

    private int roundUpTo5Minutes(int minutes) {
        return (minutes + 4) / 5 * 5;
    }

    private LocalDateTime roundUpTo5(LocalDateTime time) {
        return time.plusMinutes(Math.floorMod(-time.getMinute(), 5)).withSecond(0).withNano(0);
    }

    private Screen findOrCreateScreen(Theater theater, int screenNo, List<Screen> existingScreens) {
        String name = screenNo + "관";
        String key = "schedule-v1-" + screenNo;
        List<Screen> matches = existingScreens.stream()
                .filter(s -> key.equals(s.getSeedKey()) || name.equals(s.getName())).toList();

        if (matches.isEmpty()) {
            Screen screen = new Screen();
            screen.setTheater(theater);
            screen.setName(name);
            screen.setSeedKey(key);
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

    private int insertShowtimes(List<NewShowtime> shows, LocalDateTime now) {
        var tuples = new java.util.ArrayList<String>();
        for (int i = 0; i < shows.size(); i++)
            tuples.add("(:screen" + i + ",:movie" + i + ",:start" + i + ",:end" + i
                    + ",0,0,:price,'SCHEDULED',:now,:now)");
        var insert = em.createNativeQuery("""
                INSERT INTO showtimes (screen_id,movie_id,start_time,end_time,total_seats,available_seats,
                    price_per_person,status,created_at,updated_at) VALUES
                """ + String.join(",", tuples)).setParameter("price", PRICE).setParameter("now", now);
        for (int i = 0; i < shows.size(); i++) {
            var show = shows.get(i);
            insert.setParameter("screen" + i, show.screen()).setParameter("movie" + i, show.movie())
                    .setParameter("start" + i, show.start()).setParameter("end" + i, show.end());
        }
        return insert.executeUpdate();
    }

    private record NewShowtime(Long screen, Long movie, LocalDateTime start, LocalDateTime end) {}
    public record Result(int createdScreens, int createdShowtimes) {}
}
