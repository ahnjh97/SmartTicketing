package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.Screen;
import smartticketing.entity.Theater;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

@Slf4j
@Service
public class ShowtimeScheduleSeedService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int[] QUOTAS = {12, 10, 7, 7, 7, 3, 3, 3, 3, 3};
    private static final int AD_MINUTES = 10;
    private static final int CLEANING_MINUTES = 20;
    private static final LocalTime FIRST_START = LocalTime.of(8, 0);
    private static final LocalTime LAST_END = LocalTime.of(3, 0); // 다음 날 마지막 상영 종료 시각
    private static final int PRICE = 10_000;

    private final EntityManager em;
    private final int screenCount;
    private final int days;
    private final Map<Long, Long> audienceSeeds;

    public ShowtimeScheduleSeedService(
            EntityManager em,
            @Value("${showtime.seed.screen-count:10}") int screenCount,
            @Value("${showtime.seed.days:3}") int days,
            @Value("${tmdb.audience-seeds:}") String audienceSeeds) {
        this.em = em;
        this.screenCount = screenCount;
        this.days = days;
        // "1368337:11857193,..." 형태를 {TMDB 번호: 누적관객수}로 바꿈
        this.audienceSeeds = Arrays.stream(audienceSeeds.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> Long.valueOf(pair[0].trim()), pair -> Long.valueOf(pair[1].trim())));
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
        var existingStarts = em.createQuery("""
                select s.startTime from Showtime s where s.screen.theater.id = :theater
                and s.startTime >= :from and s.startTime < :to
                """, LocalDateTime.class).setParameter("theater", theaterId).setParameter("from", today.atStartOfDay())
                .setParameter("to", today.plusDays(days + 1L).atStartOfDay()).getResultList();
        var lineups = plan.lineups();
        var pending = new java.util.ArrayList<NewShowtime>();

        var screens = new ArrayList<Screen>();
        for (int screenNo = 1; screenNo <= screenCount; screenNo++) {
            Screen screen = findOrCreateScreen(theater, screenNo, existingScreens);
            if (screen == null) continue;
            if (screen.getId() == null) {
                em.persist(screen);
                createdScreens++;
            }

            screens.add(screen);
        }
        if (screens.isEmpty()) return new Result(createdScreens, 0);

        for (int day = 0; day < days; day++) {
            LocalDate date = today.plusDays(day);
            LocalDateTime dayStart = date.atTime(FIRST_START);
            LocalDateTime dayEnd = date.plusDays(1).atTime(LAST_END);
            // 기존 편성과 인기순 새 편성이 겹치지 않도록 이미 회차가 있는 영업일은 보존한다.
            if (existingStarts.stream().anyMatch(t -> !t.isBefore(dayStart) && t.isBefore(date.plusDays(1).atTime(4, 0)))) continue;
            List<ScheduledMovie> lineup = lineups.get(date);
            if (!lineup.isEmpty()) scheduleDay(theater, screens, lineup, dayStart, dayEnd, now, pending);
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
                .sorted(Comparator.comparing((ScheduledMovie m) -> audienceSeeds.getOrDefault(m.tmdbMovieId(), 0L), Comparator.reverseOrder())
                        .thenComparing(ScheduledMovie::releaseDate, Comparator.reverseOrder())
                        .thenComparing(ScheduledMovie::tmdbMovieId))
                .limit(QUOTAS.length)
                .toList();
    }

    // n위 영화가 n관 첫 회차를 차지한 뒤, 순위 순서대로 3시 전에 끝나는 관 중 가장 빨리 비는 관에 한 회차씩 추가
    private void scheduleDay(Theater theater, List<Screen> screens, List<ScheduledMovie> lineup,
                            LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime now, List<NewShowtime> pending) {
        // 관마다 다음 회차 시작 가능 시각 (첫 회차: 08:00 + 극장·상영관 기준 0~50분)
        LocalDateTime[] next = new LocalDateTime[screens.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = dayStart.plusMinutes(Math.floorMod((theater.getKakaoPlaceId() + "|" + (i + 1)).hashCode(), 6) * 10);
        }

        int[] placed = new int[lineup.size()];   // 영화별 배치한 회차 수
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int rank = 0; rank < lineup.size(); rank++) {
                if (placed[rank] >= QUOTAS[rank]) continue;
                int minutes = lineup.get(rank).runningTime() + AD_MINUTES;

                // 첫 회차는 자기 관(n위 → n관), 이후는 가장 빨리 비는 관 (같으면 번호가 작은 관)
                int screen = (placed[rank] == 0 && rank < next.length) ? rank : -1;
                if (screen < 0) {
                    for (int i = 0; i < next.length; i++) {
                        if (!next[i].plusMinutes(minutes).isAfter(dayEnd) && (screen < 0 || next[i].isBefore(next[screen]))) screen = i;
                    }
                }
                if (screen < 0 || next[screen].plusMinutes(minutes).isAfter(dayEnd)) {
                    placed[rank] = QUOTAS[rank];   // 3시 전에 끝낼 관이 없으면 이 영화는 그만
                    continue;
                }

                LocalDateTime start = next[screen];
                LocalDateTime end = start.plusMinutes(minutes);
                if (start.isAfter(now)) {
                    pending.add(new NewShowtime(screens.get(screen).getId(), lineup.get(rank).id(), start, end));
                }
                next[screen] = roundUpTo5(end.plusMinutes(CLEANING_MINUTES));   // 같은 관은 종료 + 청소 뒤에만
                placed[rank]++;
                progress = true;
            }
        }
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
