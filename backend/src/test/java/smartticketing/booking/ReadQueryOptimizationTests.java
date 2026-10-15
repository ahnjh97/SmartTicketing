package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.repository.*;
import smartticketing.service.*;
import smartticketing.util.CreatedCursor;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class ReadQueryOptimizationTests {
    static TemporaryMysqlDatabase db;
    static final List<String> sql = new ArrayList<>();
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 12, 0);
    record Fixture(long user, long other, List<Long> tickets) {}
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(sql::add); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    static <T> T tx(Function<EntityManager,T> action) {
        try (var em = db.open()) {
            em.getTransaction().begin();
            try { T value = action.apply(em); em.getTransaction().commit(); return value; }
            catch (RuntimeException | AssertionError e) { em.getTransaction().rollback(); throw e; }
        }
    }
    static Users user(EntityManager em) {
        var u = new Users(); u.setName("조회 검증"); u.setNickname("조회"); em.persist(u); return u;
    }
    static Fixture fixture() {
        return tx(em -> {
            var u = user(em); var other = user(em);
            var movie = new Movie(); movie.setTitle("페이지 검증"); movie.setTmdbMovieId(System.nanoTime()); movie.setRating("ALL"); em.persist(movie);
            var theater = new Theater(); theater.setName("극장"); theater.setAddress("서울"); theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId(UUID.randomUUID().toString()); em.persist(theater);
            var screen = new Screen(); screen.setName("1관"); screen.setTheater(theater); em.persist(screen);
            var show = new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(NOW.plusDays(1)); show.setEndTime(NOW.plusDays(1).plusHours(2)); show.setPricePerPerson(10000); show.setTotalSeats(2); show.setAvailableSeats(2); show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show);
            var seats = new ArrayList<Seat>();
            for (int n : List.of(2,1)) { var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow("A"); seat.setSeatNumber(n); seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE); em.persist(seat); seats.add(seat); }
            var ids = new ArrayList<Long>();
            for (int i=0; i<6; i++) {
                var r = new Reservation(); r.setUser(i==5 ? other : u); r.setShowtime(show); r.setReservationType(ReservationType.NORMAL); r.setStatus(ReservationStatus.CONFIRMED); r.setTotalAmount(20000); r.setCreatedAt(NOW); r.setUpdatedAt(NOW); em.persist(r);
                for (var seat : seats) { var rs = new ReservationSeat(); rs.setReservation(r); rs.setSeat(seat); rs.setAudienceType("ADULT"); rs.setPrice(10000); em.persist(rs); }
                var t = new Ticket(); t.setReservation(r); t.setTicketNumber(UUID.randomUUID().toString()); t.setCreatedAt(NOW); t.setUpdatedAt(NOW); em.persist(t); if (i<5) ids.add(t.getId());
            }
            Collections.reverse(ids); return new Fixture(u.getId(), other.getId(), ids);
        });
    }
    @Test void ticketPagesHaveStableCursorOwnershipAndTwoQueries() {
        var f = fixture();
        var ids = new ArrayList<Long>();
        String cursor = null;
        do {
            String before = cursor; sql.clear();
            var page = tx(em -> BookingPaymentTests.tickets(em).page(f.user(), before, 2));
            assertThat(sql.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith("select")).count()).isEqualTo(2);
            assertThat(page.items()).allSatisfy(t -> assertThat(t.seats()).containsExactly("A1", "A2"));
            ids.addAll(page.items().stream().map(t -> t.ticketId()).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).containsExactlyElementsOf(f.tickets());
        sql.clear();
        var legacy = tx(em -> BookingPaymentTests.tickets(em).mine(f.user()));
        assertThat(legacy).hasSize(5);
        assertThat(sql.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith("select")).count()).isEqualTo(2);
        assertThat(tx(em -> BookingPaymentTests.tickets(em).page(f.other(), null, 20)).items()).hasSize(1);
    }
    @Test void notificationsFilterBeforePagingAndBulkUpdateOnlyOwnedVisibleTypes() {
        var f = fixture();
        var ids = tx(em -> {
            var service = BookingPaymentTests.notifications(em); var visible = new ArrayList<Long>();
            for (int i=0; i<3; i++) { var n = service.create(f.user(), NotificationType.QUEUE_TURN, "visible"); n.setCreatedAt(NOW); visible.add(n.getId()); }
            service.create(f.user(), NotificationType.PAYMENT_FAILED, "hidden").setCreatedAt(NOW);
            service.create(f.other(), NotificationType.QUEUE_TURN, "other").setCreatedAt(NOW);
            Collections.reverse(visible); return visible;
        });
        var first = tx(em -> BookingPaymentTests.notifications(em).page(f.user(), true, null, 2));
        var second = tx(em -> BookingPaymentTests.notifications(em).page(f.user(), true, first.nextCursor(), 2));
        assertThat(first.items()).extracting(n -> n.id()).containsExactlyElementsOf(ids.subList(0,2));
        assertThat(second.items()).extracting(n -> n.id()).containsExactly(ids.getLast());
        assertThat(second.nextCursor()).isNull();
        sql.clear();
        tx(em -> { BookingPaymentTests.notifications(em).readAll(f.user()); return null; });
        assertThat(sql).hasSize(1);
        assertThat(sql.getFirst().toLowerCase(Locale.ROOT)).startsWith("update");
        tx(em -> {
            assertThat(BookingPaymentTests.notifications(em).list(f.user(), true)).isEmpty();
            var repo = new JpaRepositoryFactory(em).getRepository(NotificationRepository.class);
            assertThat(repo.findByUserIdOrderByCreatedAtDesc(f.user())).filteredOn(n -> n.getType()==NotificationType.PAYMENT_FAILED).allMatch(n -> !n.isRead());
            assertThat(repo.findByUserIdOrderByCreatedAtDesc(f.other())).allMatch(n -> !n.isRead());
            return null;
        });
    }
    @Test void invalidCursorAndPageSizeAreRejected() {
        assertThatThrownBy(() -> CreatedCursor.parse("broken",20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreatedCursor.parse(NOW+"|0",20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreatedCursor.parse(null,101)).isInstanceOf(IllegalArgumentException.class);
    }
}
