package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private static final LocalTime LAST_END = LocalTime.of(3, 30); // 다음 날 마지막 상영 종료 시각
    private static final int PRICE = 10_000;

    private final EntityManager em;
    private final int screenCount;
    private final int days;

    public ShowtimeScheduleSeedService(
            EntityManager em,
            @Value("${showtime.seed.screen-count:10}") int screenCount,
            @Value("${showtime.seed.days:3}") int days) {
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
        return seedTheater(theaterId, null);
    }

    /** 실행 중에만 공유하는 편성 스냅샷. 다음 실행에서는 영화 변경을 다시 반영한다. */
    @Transactional(readOnly = true)
    public SchedulePlan preparePlan() {
        List<ScheduledMovie> movies = em.createQuery("""
                        select m.id, m.tmdbMovieId, m.releaseDate, m.runningTime from Movie m
                        where m.active = true and m.releaseDate is not null and m.runningTime > 0
                        """, Object[].class).getResultList().stream()
                .map(row -> new ScheduledMovie((Long) row[0], (Long) row[1], (LocalDate) row[2], (Integer) row[3]))
                .toList();
        var now = LocalDateTime.now(SEOUL);
        var lineups = new java.util.HashMap<LocalDate, List<ScheduledMovie>>();
        for (int day = 0; day < days; day++) {
            var date = now.toLocalDate().plusDays(day);
            lineups.put(date, lineupOf(movies, date));
        }
        return new SchedulePlan(now, java.util.Map.copyOf(lineups), movies.isEmpty());
    }

    public record ScheduledMovie(Long id, Long tmdbMovieId, LocalDate releaseDate, int runningTime) {}
    public record SchedulePlan(LocalDateTime now, java.util.Map<LocalDate, List<ScheduledMovie>> lineups, boolean noMovies) {}

    @Transactional
    public Result seedTheater(Long theaterId, SchedulePlan plan) {
        Theater theater = em.find(Theater.class, theaterId, LockModeType.PESSIMISTIC_WRITE);
        if (theater == null || !theater.isActive()) return new Result(0, 0);

        // 단독 호출도 극장 잠금 후 스냅샷을 읽어 동시 생성 시 기존 회차를 볼 수 있게 한다.
        if (plan == null || !plan.now().toLocalDate().equals(LocalDate.now(SEOUL))) plan = preparePlan();
        if (plan.noMovies()) return new Result(0, 0);

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDate today = plan.now().toLocalDate();
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
        var lineups = plan.lineups();
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
                List<ScheduledMovie> lineup = lineups.get(date);
                if (lineup.isEmpty()) continue;

                int base = Math.floorMod(theater.getKakaoPlaceId().hashCode(), lineup.size());
                ScheduledMovie movie = lineup.get((base + screenNo - 1) % lineup.size());

                LocalDateTime start = date.atTime(FIRST_START).plusMinutes(startOffset);
                LocalDateTime lastEnd = date.plusDays(1).atTime(LAST_END);
                for (int round = 0; round < ROUNDS; round++) {
                    LocalDateTime end = start.plusMinutes(movie.runningTime() + AD_MINUTES);
                    if (end.isAfter(lastEnd)) break;   // 새벽 3시 30분 넘게 끝나면 그날 상영 종료
                    if (start.isAfter(now) && !existingStarts.contains(start)) {
                        pending.add(new NewShowtime(screen.getId(), movie.id(), start, end));
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

    private List<ScheduledMovie> lineupOf(List<ScheduledMovie> movies, LocalDate date) {
        return movies.stream()
                .filter(m -> !m.releaseDate().isAfter(date))
                .sorted(Comparator.comparing(ScheduledMovie::releaseDate).reversed()
                        .thenComparing(ScheduledMovie::tmdbMovieId))
                .limit(LINEUP_SIZE)
                .sorted(Comparator.comparing(ScheduledMovie::tmdbMovieId))
                .toList();
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
