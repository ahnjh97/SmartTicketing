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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 모든 활성 극장에 1관~N관과 오늘 포함 N일의 상영 회차를 만든다. (좌석은 만들지 않음)
 * 인기 순위는 설정값(tmdb.audience-seeds)으로 정하므로 누가 실행해도 같은 극장·날짜면 같은 시간표가 나온다.
 */
@Slf4j
@Service
public class ShowtimeScheduleSeedService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int[] QUOTAS = {12, 10, 7, 7, 7, 3, 3, 3, 3, 3}; // 순위별 하루 최대 회차 (1위~10위)
    private static final int AD_MINUTES = 10;                         // 광고
    private static final int CLEANING_MINUTES = 20;                   // 청소
    private static final LocalTime FIRST_START = LocalTime.of(8, 0);  // 첫 회차 기준 시각
    private static final LocalTime LAST_END = LocalTime.of(3, 0);     // 마지막 회차 종료 한도 (다음 날 03:00)
    private static final int PLANNED_SEATS = 120;                     // 좌석 120석 기준
    private static final int PRICE = 10_000;

    private final EntityManager em;
    private final int screenCount;
    private final int days;
    private final Map<Long, Long> audienceSeeds;
    public record SeedResult(int createdScreens, int createdShowtimes) {}

    public ShowtimeScheduleSeedService(
            EntityManager em,
            @Value("${showtime.seed.screen-count:10}") int screenCount,
            @Value("${showtime.seed.days:7}") int days,
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

    // 극장 하나씩 저장하고 비워서 메모리를 아낀다
    @Transactional
    public SeedResult seedTheater(Long theaterId) {
        Theater theater = em.find(Theater.class, theaterId);
        if (theater == null || !theater.isActive()) return new SeedResult(0, 0);

        List<Movie> movies = em.createQuery(
                        "select m from Movie m where m.active = true and m.releaseDate is not null and m.runningTime > 0",
                        Movie.class)
                .getResultList();
        if (movies.isEmpty()) return new SeedResult(0, 0);

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDate today = now.toLocalDate();

        // 1관~N관 준비 (만들기 전 개수와 비교해 새로 만든 상영관 수 계산)
        long screensBefore = em.createQuery(
                        "select count(s) from Screen s where s.theater.id = :theater and s.seedKey like 'schedule-v1-%'", Long.class)
                .setParameter("theater", theaterId)
                .getSingleResult();
        List<Screen> screens = new ArrayList<>();
        for (int screenNo = 1; screenNo <= screenCount; screenNo++) {
            Screen screen = findOrCreateScreen(theater, screenNo);
            if (screen != null) screens.add(screen);
        }
        int createdScreens = (int) Math.max(0, screens.size() - screensBefore);
        if (screens.isEmpty()) return new SeedResult(createdScreens, 0);

        // 이 극장에 이미 만든 회차 시작 시각
        List<LocalDateTime> existingStarts = em.createQuery(
                        "select s.startTime from Showtime s where s.screen.id in :screens and s.startTime >= :from and s.startTime < :to",
                        LocalDateTime.class)
                .setParameter("screens", screens.stream().map(Screen::getId).toList())
                .setParameter("from", today.atStartOfDay())
                .setParameter("to", today.plusDays(days + 1L).atStartOfDay())
                .getResultList();

        int created = 0;
        for (int day = 0; day < days; day++) {
            LocalDate date = today.plusDays(day);
            LocalDateTime dayStart = date.atTime(FIRST_START);
            LocalDateTime dayEnd = date.plusDays(1).atTime(LAST_END);

            // 이미 시간표가 있는 날은 건너뜀 (다시 만들면 기존 회차와 겹칠 수 있음)
            if (existingStarts.stream().anyMatch(t -> !t.isBefore(dayStart) && t.isBefore(dayEnd))) continue;

            List<Movie> lineup = lineupOf(movies, date);
            if (lineup.isEmpty()) continue;

            created += scheduleDay(theater, screens, lineup, dayStart, dayEnd, now);
        }
        em.flush();
        em.clear();
        return new SeedResult(createdScreens, created);
    }

    // 그날까지 개봉한 영화 중 인기순 10편 (설정값 누적관객수 → 개봉일 최신 → TMDB 번호 순)
    private List<Movie> lineupOf(List<Movie> movies, LocalDate date) {
        return movies.stream()
                .filter(m -> !m.getReleaseDate().isAfter(date))
                .sorted(Comparator.comparing((Movie m) -> popularity(m), Comparator.reverseOrder())
                        .thenComparing(Movie::getReleaseDate, Comparator.reverseOrder())
                        .thenComparing(Movie::getTmdbMovieId))
                .limit(QUOTAS.length)
                .toList();
    }

    private Long popularity(Movie movie) {
        return audienceSeeds.getOrDefault(movie.getTmdbMovieId(), 0L);
    }

    // n위 영화가 n관 첫 회차를 차지한 뒤, 순위 순서대로 3시 전에 끝나는 관 중 가장 빨리 비는 관에 한 회차씩 추가
    private int scheduleDay(Theater theater, List<Screen> screens, List<Movie> lineup,
                            LocalDateTime dayStart, LocalDateTime dayEnd, LocalDateTime now) {
        // 관마다 다음 회차 시작 가능 시각 (첫 회차: 08:00 + 극장·상영관 기준 0~50분)
        LocalDateTime[] next = new LocalDateTime[screens.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = dayStart.plusMinutes(Math.floorMod((theater.getKakaoPlaceId() + "|" + (i + 1)).hashCode(), 6) * 10);
        }

        int[] placed = new int[lineup.size()];   // 영화별 배치한 회차 수
        int created = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int rank = 0; rank < lineup.size(); rank++) {
                if (placed[rank] >= QUOTAS[rank]) continue;
                int minutes = lineup.get(rank).getRunningTime() + AD_MINUTES;

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
                    createShowtime(screens.get(screen), lineup.get(rank), start, end, now);
                    created++;
                }
                next[screen] = roundUpTo5(end.plusMinutes(CLEANING_MINUTES));   // 같은 관은 종료 + 청소 뒤에만
                placed[rank]++;
                progress = true;
            }
        }
        return created;
    }

    // 5분 단위로 올림 (예: 11:23 → 11:25)
    private LocalDateTime roundUpTo5(LocalDateTime time) {
        return time.plusMinutes(Math.floorMod(-time.getMinute(), 5)).withSecond(0).withNano(0);
    }

    // 1관~N관: 표식(seedKey)으로 찾고, 같은 이름의 기존 상영관은 건드리지 않음
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