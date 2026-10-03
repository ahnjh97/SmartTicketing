package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.ShowtimeScheduleSeedService;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ShowtimeScheduleSeedTests {
    private static TemporaryMysqlDatabase database;
    private static final List<String> statements = new ArrayList<>();
    private EntityManager em;
    private Long theaterId;

    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(statements::add); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin();
        var theater = new Theater(); theater.setName("시간표 극장"); theater.setBrand(TheaterBrand.CGV);
        theater.setAddress("서울"); theater.setKakaoPlaceId(UUID.randomUUID().toString()); em.persist(theater);
        theaterId = theater.getId();
        var movie = new Movie(); movie.setTitle("영화"); movie.setTmdbMovieId(123L); movie.setRunningTime(120);
        movie.setReleaseDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1)); em.persist(movie);
        em.flush(); em.clear(); statements.clear();
    }
    @AfterEach void rollback() { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }

    @Test void threeDayPlanIncludesLastNightsShowsAfterMidnight() {
        em.createQuery("update Movie set runningTime=135").executeUpdate();
        // 첫 회차를 08:00으로 고정해 새벽 03:00 종료 한도 안에 00:30 회차가 생기게 한다.
        String place = "midnight-0";
        for (int i = 1; Math.floorMod((place + "|1").hashCode(), 6) != 0; i++) place = "midnight-" + i;
        em.find(Theater.class, theaterId).setKakaoPlaceId(place); em.flush(); em.clear();
        var service = new ShowtimeScheduleSeedService(em, 1, 3, "");
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(service.preparePlan().lineups().keySet()).containsExactlyInAnyOrder(today, today.plusDays(1), today.plusDays(2));
        service.seedTheater(theaterId);
        var starts = em.createQuery("select startTime from Showtime", LocalDateTime.class).getResultList();
        assertThat(starts).isNotEmpty().allMatch(start -> !start.toLocalDate().isBefore(today) && start.isBefore(today.plusDays(3).atTime(4, 0)));
        assertThat(starts.stream().map(LocalDateTime::toLocalDate).distinct()).contains(today.plusDays(1), today.plusDays(2), today.plusDays(3));
    }

    @Test void batchesHundredsOfShowsAndRepeatUsesFourReadsWithoutInserts() {
        for (int i = 1; i < 10; i++) {
            var movie = new Movie(); movie.setTitle("영화 " + i); movie.setTmdbMovieId(123L + i);
            movie.setRunningTime(120); movie.setReleaseDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
            em.persist(movie);
        }
        em.flush(); em.clear(); statements.clear();
        var service = new ShowtimeScheduleSeedService(em, 10, 7, "");
        long started = System.nanoTime();
        var first = service.seedTheater(theaterId);
        long firstMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(first.createdScreens()).isEqualTo(10);
        assertThat(first.createdShowtimes()).isBetween(348, 406);
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into showtimes "))).hasSize(1);
        statements.clear(); started = System.nanoTime();
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
        long repeatMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(statements).hasSize(4);
        assertThat(statements).allMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("select "));
        System.out.printf("SCHEDULE_PERFORMANCE screens=10 shows=%d initialMs=%d repeatMs=%d repeatQueries=%d%n",
                first.createdShowtimes(), firstMs, repeatMs, statements.size());
    }

    @Test void popularityQuotasPreserveExistingDayAndKeepInventoryUnprepared() {
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var popular = new Movie(); popular.setTitle("인기 영화"); popular.setTmdbMovieId(456L);
        popular.setRunningTime(120); popular.setReleaseDate(today.minusDays(2)); em.persist(popular);
        em.flush(); em.clear();
        var service = new ShowtimeScheduleSeedService(em, 10, 3, "123:1,456:100");
        assertThat(service.preparePlan().lineups().get(today).getFirst().tmdbMovieId()).isEqualTo(456L);
        service.seedTheater(theaterId);
        var tomorrow = today.plusDays(1);
        var shows = em.createQuery("from Showtime where startTime >= :start and startTime < :end order by screen.id, startTime", Showtime.class)
                .setParameter("start", tomorrow.atTime(8, 0)).setParameter("end", tomorrow.plusDays(1).atTime(4, 0)).getResultList();
        assertThat(shows.stream().filter(show -> show.getMovie().getTmdbMovieId().equals(456L))).hasSize(12);
        assertThat(shows.stream().filter(show -> show.getMovie().getTmdbMovieId().equals(123L))).hasSize(10);
        assertThat(shows).allMatch(show -> show.getTotalSeats() == 0 && show.getAvailableSeats() == 0);
        for (int i = 1; i < shows.size(); i++) {
            var previous = shows.get(i - 1); var current = shows.get(i);
            if (previous.getScreen().getId().equals(current.getScreen().getId()))
                assertThat(current.getStartTime()).isAfterOrEqualTo(previous.getEndTime().plusMinutes(20));
        }
        var removed = shows.getFirst(); em.remove(removed); em.flush(); em.clear();
        // 기존 시간표 일부가 있으면 새 알고리즘으로 해당 날짜를 덮어쓰거나 섞지 않는다.
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
    }

    @Test void longMoviesGetFewerRoundsWithinNextDayClosingTime() {
        var movie = em.createQuery("from Movie", Movie.class).getSingleResult();
        movie.setRunningTime(181); em.flush(); em.clear();
        var service = new ShowtimeScheduleSeedService(em, 1, 2, "");
        assertThat(service.seedTheater(theaterId).createdShowtimes()).isPositive();
        var tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        var shows = em.createQuery("from Showtime where startTime >= :start and startTime < :end order by startTime", Showtime.class)
                .setParameter("start", tomorrow.atTime(8, 0))
                .setParameter("end", tomorrow.plusDays(1).atTime(8, 0)).getResultList();
        assertThat(shows).hasSize(5);
        assertThat(shows).allMatch(show -> !show.getEndTime().isAfter(tomorrow.plusDays(1).atTime(3, 0)));
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
    }

    @Test void sharesLineupAcrossTheatersAndReloadsMoviesOnNextRun() {
        var second = new Theater(); second.setName("두 번째 극장"); second.setBrand(TheaterBrand.CGV);
        second.setAddress("서울"); second.setKakaoPlaceId(UUID.randomUUID().toString()); em.persist(second);
        var secondId = second.getId(); em.flush(); em.clear();
        var service = new ShowtimeScheduleSeedService(em, 1, 2, "");
        statements.clear();
        var plan = service.preparePlan();
        em.clear(); // 실제 호출처럼 편성 조회 트랜잭션의 엔티티를 재사용하지 않는다.
        assertThat(service.seedTheater(theaterId, plan).createdShowtimes()).isPositive();
        assertThat(service.seedTheater(secondId, plan).createdShowtimes()).isPositive();
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).contains("from movies"))).hasSize(1);
        var movie = em.createQuery("from Movie", Movie.class).getSingleResult();
        movie.setActive(false); em.flush(); em.clear();
        assertThat(service.preparePlan().noMovies()).isTrue();
    }

    @Test void preservesEditedShowsAndDoesNotAdoptSameNamedScreen() {
        var screen = new Screen(); screen.setTheater(em.find(Theater.class, theaterId)); screen.setName("1관"); em.persist(screen);
        var service = new ShowtimeScheduleSeedService(em, 2, 7, "");
        assertThat(service.seedTheater(theaterId).createdScreens()).isEqualTo(1);
        var shows = em.createQuery("from Showtime", Showtime.class).getResultList();
        assertThat(shows).allMatch(s -> s.getScreen().getSeedKey().equals("schedule-v1-2"));
        var edited = shows.getFirst(); edited.setPricePerPerson(17000); edited.setAvailableSeats(3);
        edited.setStatus(ShowtimeStatus.CANCELLED); var id = edited.getId(); em.flush(); em.clear();
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
        edited = em.find(Showtime.class, id);
        assertThat(edited.getPricePerPerson()).isEqualTo(17000);
        assertThat(edited.getAvailableSeats()).isEqualTo(3);
        assertThat(edited.getStatus()).isEqualTo(ShowtimeStatus.CANCELLED);
    }
}
