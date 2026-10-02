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

    @Test void batchesHundredsOfShowsAndRepeatUsesFourReadsWithoutInserts() {
        var service = new ShowtimeScheduleSeedService(em, 10, 7);
        long started = System.nanoTime();
        var first = service.seedTheater(theaterId);
        long firstMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(first.createdScreens()).isEqualTo(10);
        assertThat(first.createdShowtimes()).isBetween(420, 490);
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into showtimes "))).hasSize(1);
        statements.clear(); started = System.nanoTime();
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
        long repeatMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(statements).hasSize(4);
        assertThat(statements).allMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("select "));
        System.out.printf("SCHEDULE_PERFORMANCE screens=10 shows=%d initialMs=%d repeatMs=%d repeatQueries=%d%n",
                first.createdShowtimes(), firstMs, repeatMs, statements.size());
    }

    @Test void longMoviesGetFewerRoundsWithinNextDayClosingTime() {
        var movie = em.createQuery("from Movie", Movie.class).getSingleResult();
        movie.setRunningTime(181); em.flush(); em.clear();
        var service = new ShowtimeScheduleSeedService(em, 1, 2);
        assertThat(service.seedTheater(theaterId).createdShowtimes()).isPositive();
        var tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
        var shows = em.createQuery("from Showtime where startTime >= :start and startTime < :end order by startTime", Showtime.class)
                .setParameter("start", tomorrow.atTime(8, 0))
                .setParameter("end", tomorrow.plusDays(1).atTime(8, 0)).getResultList();
        assertThat(shows).hasSize(5);
        assertThat(shows).allMatch(show -> !show.getEndTime().isAfter(tomorrow.plusDays(1).atTime(3, 30)));
        assertThat(service.seedTheater(theaterId)).isEqualTo(new ShowtimeScheduleSeedService.Result(0, 0));
    }

    @Test void preservesEditedShowsAndDoesNotAdoptSameNamedScreen() {
        var screen = new Screen(); screen.setTheater(em.find(Theater.class, theaterId)); screen.setName("1관"); em.persist(screen);
        var service = new ShowtimeScheduleSeedService(em, 2, 7);
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
