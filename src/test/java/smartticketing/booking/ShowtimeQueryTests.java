package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ShowtimeQueryTests {
    @Test void availabilityRecognizesThreePairsButDoesNotClaimFivePeopleCanFit() {
        var show = showtime("normal");
        var rows = em.createQuery("from ShowtimeSeat where showtime.id=:id", ShowtimeSeat.class)
                .setParameter("id", show.getId()).getResultList();
        rows.forEach(i -> i.setStatus(List.of("A","B","C").contains(i.getSeat().getSeatRow())
                && i.getSeat().getSeatNumber() <= 2 ? SeatStatus.AVAILABLE : SeatStatus.BLOCKED));
        em.flush(); em.clear();
        var item = query.showtimes(movieId,null,LocalDate.of(2026,10,1),null,null).items().stream()
                .filter(i -> i.id().equals(show.getId())).findFirst().orElseThrow();
        assertThat(item.maxContiguousSeats()).isEqualTo(2);
        assertThat(item.bookablePartySizes()).containsExactly(1,2,4,6);
    }
    private static TemporaryMysqlDatabase database;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private EntityManager em;
    private ShowtimeQueryService query;
    private Long theaterId;
    private Long movieId;

    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin();
        var theater = new Theater(); theater.setName("기존 극장"); theater.setBrand(TheaterBrand.CGV);
        theater.setAddress("서울"); theater.setKakaoPlaceId("query-test"); em.persist(theater); theaterId = theater.getId();
        var movie = new Movie(); movie.setTitle("영화"); movie.setTmdbMovieId(9988L); movie.setRunningTime(120);
        em.persist(movie); movieId = movie.getId();
        new BookingSeedService(em, CLOCK).seed(List.of(theaterId));
        em.flush(); em.clear();
        query = new ShowtimeQueryService(em, new BookingCatalogService(em), CLOCK);
    }
    @AfterEach void rollback() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();
    }
    private Showtime showtime(String scenario) {
        return em.createQuery("from Showtime s where s.screen.seedKey = :key order by s.startTime", Showtime.class)
                .setParameter("key", "booking-v1-" + scenario).getResultList().getFirst();
    }

    @Test void dawnShowBelongsToPreviousBookingDayAndKeepsActualTimestamp() {
        var show = showtime("normal");
        show.setStartTime(LocalDateTime.of(2026, 10, 4, 1, 0));
        show.setEndTime(LocalDateTime.of(2026, 10, 4, 3, 0));
        em.flush(); em.clear();
        var date = LocalDate.of(2026, 10, 3);
        assertThat(query.showtimes(movieId, theaterId, date, null, null).items())
                .anySatisfy(item -> { assertThat(item.id()).isEqualTo(show.getId());
                    assertThat(item.startTime().toLocalDate()).isEqualTo(date.plusDays(1)); });
        assertThat(query.showtimes(movieId, theaterId, date.plusDays(1), null, null).items())
                .noneMatch(item -> item.id().equals(show.getId()));
        assertThat(query.showtimes(movieId, theaterId, date, LocalTime.MIDNIGHT, LocalTime.of(2, 0)).items())
                .extracting(item -> item.id()).contains(show.getId());
        assertThat(query.theaterMovies(theaterId, date).items()).extracting(item -> item.movieId()).contains(movieId);
    }

    @Test void overnightRangeIncludesBothStartTimeBoundsWithSeoulDates() {
        var first = showtime("normal"); var second = showtime("sold-out"); var excluded = showtime("fragmented");
        first.setStartTime(LocalDateTime.of(2026, 10, 1, 22, 0));
        first.setEndTime(LocalDateTime.of(2026, 10, 2, 0, 20));
        second.setStartTime(LocalDateTime.of(2026, 10, 2, 1, 59));
        second.setEndTime(LocalDateTime.of(2026, 10, 2, 3, 59));
        excluded.setStartTime(LocalDateTime.of(2026, 10, 2, 2, 0));
        excluded.setEndTime(LocalDateTime.of(2026, 10, 2, 4, 0));
        em.flush(); em.clear();
        var result = query.showtimes(movieId, null, LocalDate.of(2026, 10, 1), LocalTime.of(22, 0), LocalTime.of(2, 0));
        assertThat(result.items()).extracting(i -> i.id()).containsExactly(first.getId(), second.getId(), excluded.getId());
        assertThat(query.showtimes(movieId, null, LocalDate.of(2026, 10, 1), LocalTime.of(22, 0), LocalTime.of(1, 59)).items())
                .extracting(i -> i.id()).containsExactly(first.getId(), second.getId());
        assertThat(result.items().getFirst().endsNextDay()).isTrue();
        assertThat(result.items().getFirst().startTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(result.items().getFirst().pricePerPerson()).isEqualTo(10000);
        assertThat(query.showtimes(null, theaterId, LocalDate.of(2026, 10, 1), null, null).items())
                .extracting(i -> i.id()).containsExactly(first.getId(), second.getId(), excluded.getId());
    }

    @Test void bothPagesReadSameInventoryWithConstantQueryCount() {
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var byMovie = query.showtimes(movieId, null, LocalDate.of(2026, 10, 1), null, null);
        assertThat(byMovie.items()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        em.clear(); statistics.clear();
        var byTheater = query.showtimes(null, theaterId, LocalDate.of(2026, 10, 1), null, null);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(byTheater.items()).isEqualTo(byMovie.items());
        assertThat(byMovie.items()).extracting(i -> i.availableSeats()).containsExactlyInAnyOrder(120L, 0L, 70L);
        assertThat(byMovie.items()).extracting(i -> i.maxContiguousSeats()).containsExactlyInAnyOrder(6, 0, 1);
    }

    @Test void invalidFiltersAndUnavailableShowsAreHandledWithoutFalseAvailability() {
        var date = LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> query.showtimes(null, null, date, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.showtimes(movieId, null, date, LocalTime.NOON, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.showtimes(movieId, null, date, LocalTime.NOON, LocalTime.NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.showtimes(movieId, null, LocalDate.MAX, null, null)).isInstanceOf(IllegalArgumentException.class);
        showtime("normal").setStatus(ShowtimeStatus.CANCELLED);
        showtime("sold-out").setStartTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        showtime("fragmented").getScreen().setActive(false); em.flush(); em.clear();
        assertThat(query.showtimes(movieId, theaterId, date, null, null).items()).isEmpty();
    }

    @Test void seatsDistinguishSoldOutFragmentedAndContiguousWithoutLeakingReservations() {
        Long normal = showtime("normal").getId(), sold = showtime("sold-out").getId(), fragmented = showtime("fragmented").getId();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var result = query.seats(normal);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(result.availableSeats()).isEqualTo(120);
        assertThat(result.maxContiguousSeats()).isEqualTo(6);
        assertThat(result.layoutComplete()).isTrue();
        assertThat(result.serverTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(query.seats(sold).availableSeats()).isZero();
        assertThat(query.seats(sold).maxContiguousSeats()).isZero();
        assertThat(query.seats(fragmented).availableSeats()).isEqualTo(70);
        assertThat(query.seats(fragmented).maxContiguousSeats()).isEqualTo(1);
    }

    @Test void expiredHoldIsNotReleasedByReadAndCachedCountIsNotUsed() {
        var showtime = showtime("normal"); showtime.setAvailableSeats(999);
        var seat = em.createQuery("from ShowtimeSeat where showtime.id = :id order by id", ShowtimeSeat.class)
                .setParameter("id", showtime.getId()).getResultList().getFirst();
        seat.setStatus(SeatStatus.HOLDING); seat.setHoldExpiredAt(LocalDateTime.of(2026, 9, 30, 0, 0));
        Long id = seat.getId(), showtimeId = showtime.getId(); em.flush(); em.clear();
        assertThat(query.seats(showtimeId).availableSeats()).isEqualTo(119);
        assertThat(em.find(ShowtimeSeat.class, id).getStatus()).isEqualTo(SeatStatus.HOLDING);
        assertThat(em.find(Showtime.class, showtimeId).getAvailableSeats()).isEqualTo(999);
    }

    @Test void inactiveSeatAndUnknownLayoutAreConservative() {
        var showtime = showtime("normal");
        var inventory = em.createQuery("from ShowtimeSeat where showtime.id = :id", ShowtimeSeat.class)
                .setParameter("id", showtime.getId()).getResultList();
        inventory.getFirst().getSeat().setActive(false);
        inventory.forEach(i -> i.getSeat().setAdjacencySegment(null)); em.flush(); em.clear();
        var result = query.seats(showtime.getId());
        assertThat(result.totalSeats()).isEqualTo(119);
        assertThat(result.layoutComplete()).isFalse();
        assertThat(result.maxContiguousSeats()).isEqualTo(1);
    }

    @Test void startedCancelledClosedAndInactiveShowsCannotExposeBookableSeatMaps() {
        var showtime = showtime("normal");
        for (var status : List.of(ShowtimeStatus.CANCELLED, ShowtimeStatus.CLOSED, ShowtimeStatus.COMPLETED)) {
            showtime.setStatus(status); em.flush();
            assertThatThrownBy(() -> query.seats(showtime.getId())).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        }
        showtime.setStatus(ShowtimeStatus.SCHEDULED); showtime.setStartTime(LocalDateTime.of(2026, 10, 1, 9, 0)); em.flush();
        assertThatThrownBy(() -> query.seats(showtime.getId())).isInstanceOf(ResponseStatusException.class);
        showtime.getScreen().setActive(false); em.flush();
        assertThatThrownBy(() -> query.seats(showtime.getId())).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test void theaterMoviesAreDistinctAndEmptyDateIsNotMissingTheater() {
        var movies = query.theaterMovies(theaterId, LocalDate.of(2026, 10, 1));
        assertThat(movies.items()).extracting(i -> i.movieId()).containsExactly(movieId);
        assertThat(query.theaterMovies(theaterId, LocalDate.of(2026, 9, 30)).items()).isEmpty();
        assertThatThrownBy(() -> query.theaterMovies(Long.MAX_VALUE, LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ResponseStatusException.class);
        em.createQuery("update Showtime s set s.status = :status").setParameter("status", ShowtimeStatus.CANCELLED).executeUpdate();
        assertThat(query.theaterMovies(theaterId, LocalDate.of(2026, 10, 1)).items()).isEmpty();
    }
}
