package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.entity.*;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.service.BookingCatalogService;
import static org.assertj.core.api.Assertions.*;

class BookingCatalogTests {
    private static TemporaryMysqlDatabase database;
    private EntityManager em;
    private BookingCatalogService service;

    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin(); service = new BookingCatalogService(em, new smartticketing.service.RedisQueryCache(null, false, 2000, "test-disabled"));
    }
    @AfterEach void rollback() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();
    }

    private Movie movie(long tmdbId, boolean active) {
        var m = new Movie(); m.setTmdbMovieId(tmdbId); m.setTitle("영화 " + tmdbId); m.setActive(active);
        m.setPosterUrl("https://example.test/poster.jpg"); em.persist(m); return m;
    }
    private Theater theater(String name, boolean active) {
        var t = new Theater(); t.setName(name); t.setAddress("서울"); t.setBrand(TheaterBrand.CGV);
        t.setKakaoPlaceId(name); t.setActive(active); em.persist(t); return t;
    }

    @Test void moviePagesAreStableAndExcludeInactiveRowsWithoutExternalImports() {
        var first = movie(999001, true); var second = movie(999002, true); movie(999003, false);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var page = service.movies(0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(i -> i.id()).containsExactly(first.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(service.movies(1, 1).items()).extracting(i -> i.id()).containsExactly(second.getId());
        assertThat(service.movies(2, 1).items()).isEmpty();
        assertThat(service.movie(first.getId()).media().type()).isEqualTo("POSTER");
        assertThatThrownBy(() -> service.movie(999001L)).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
    }

    @Test void landscapeFilterRunsBeforePaginationWithoutDeletingMovies() {
        var missing = movie(888001, true);
        var first = movie(888002, true); first.setBackdropUrl("https://example.test/wide.jpg");
        var blank = movie(888003, true); blank.setBackdropUrl(" ");
        var second = movie(888004, true); second.setBackdropUrl("https://example.test/wide2.jpg");
        em.flush(); em.clear();
        assertThat(service.movies(0, 1, true).totalElements()).isEqualTo(2);
        assertThat(service.movies(0, 1, true).items()).extracting(i -> i.id()).containsExactly(first.getId());
        assertThat(service.movies(1, 1, true).items()).extracting(i -> i.id()).containsExactly(second.getId());
        assertThat(service.movies(0, 20).totalElements()).isEqualTo(4);
        assertThat(service.movie(missing.getId())).isNotNull();
    }

    @Test void literalTheaterSearchPreservesUnknownCoordinatesAndExcludesInactive() {
        var target = theater("CGV 100%_!", true); theater("CGV 일반", true); theater("CGV 폐점", false);
        em.flush(); em.clear();
        var result = service.theaters("%_!", 0, 20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).extracting(i -> i.id()).containsExactly(target.getId());
        assertThat(result.items().getFirst().latitude()).isNull();
        assertThat(service.theaters("서울", 0, 20).totalElements()).isEqualTo(2);
        assertThat(service.theaters("없는 극장", 0, 20).items()).isEmpty();
    }

    @Test void brandFilterAppliesBeforePaginationAndCount() {
        theater("CGV 지점", true);
        var first = theater("롯데 A", true); first.setBrand(TheaterBrand.LOTTE_CINEMA);
        var second = theater("롯데 B", true); second.setBrand(TheaterBrand.LOTTE_CINEMA);
        var closed = theater("롯데 C", false); closed.setBrand(TheaterBrand.LOTTE_CINEMA);
        em.flush(); em.clear();
        var page = service.theaters("서울", 0, 1, TheaterBrand.LOTTE_CINEMA);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(i -> i.id()).containsExactly(first.getId());
        assertThat(service.theaters("서울", 1, 1, TheaterBrand.LOTTE_CINEMA).items())
                .extracting(i -> i.id()).containsExactly(second.getId());
        assertThat(service.theaters("", 0, 20, TheaterBrand.MEGABOX).items()).isEmpty();
        assertThat(service.theaters("", 0, 20).totalElements()).isEqualTo(3);
    }

    @Test void emptySearchKeepsStablePagesAndCoordinatesWithoutLoadingEntities() {
        var first = theater("A 극장", true);
        first.setLatitude(new java.math.BigDecimal("37.5665000"));
        first.setLongitude(new java.math.BigDecimal("126.9780000"));
        var second = theater("B 극장", true);
        theater("C 폐점", false);
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var page = service.theaters("  ", 0, 1, TheaterBrand.CGV);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(i -> i.id()).containsExactly(first.getId());
        assertThat(page.items().getFirst().latitude()).isEqualByComparingTo(first.getLatitude());
        assertThat(page.items().getFirst().longitude()).isEqualByComparingTo(first.getLongitude());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(service.theaters(null, 1, 1).items()).extracting(i -> i.id()).containsExactly(second.getId());
        assertThat(service.theaters(null, 2, 1).items()).isEmpty();
    }

    @Test void inactiveAndMissingDetailsAreNotFoundAndInvalidInputIsRejected() {
        var movie = movie(77, false); var theater = theater("폐점", false);
        assertThatThrownBy(() -> service.movie(movie.getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.theater(theater.getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.theater(Long.MAX_VALUE)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.movie(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.movies(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.movies(0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.movies(Integer.MAX_VALUE, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.theaters("a".repeat(101), 0, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
