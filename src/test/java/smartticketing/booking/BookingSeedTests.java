package smartticketing.booking;

import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.BookingSeedService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BookingSeedTests {
    private static TemporaryMysqlDatabase database;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private EntityManager em;
    private Theater theater;
    private Movie movie;

    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin();
        theater = new Theater(); theater.setName("기존 극장"); theater.setBrand(TheaterBrand.CGV);
        theater.setAddress("기존 주소"); theater.setKakaoPlaceId(UUID.randomUUID().toString()); em.persist(theater);
        movie = new Movie(); movie.setTmdbMovieId(1L); movie.setTitle("기존 영화"); movie.setRunningTime(120); em.persist(movie);
    }
    @AfterEach void rollback() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close();
    }
    private BookingSeedService seed(Clock clock) { return new BookingSeedService(em, clock); }
    private List<Showtime> showtimes() { return em.createQuery("from Showtime order by screen.id, startTime", Showtime.class).getResultList(); }
    private List<ShowtimeSeat> inventory(Showtime showtime) {
        return em.createQuery("from ShowtimeSeat where showtime.id = :id order by seat.id", ShowtimeSeat.class).setParameter("id", showtime.getId()).getResultList();
    }

    @Test void generatesSevenDaysWithExactLayoutPriceAndReproducibleScenarios() {
        var result = seed(CLOCK).seed(List.of(theater.getId()));
        assertThat(result.createdScreens()).isEqualTo(3);
        assertThat(result.createdSeats()).isEqualTo(360);
        assertThat(result.createdShowtimes()).isEqualTo(21);
        assertThat(result.createdShowtimeSeats()).isEqualTo(2520);
        assertThat(showtimes().stream().map(s -> s.getStartTime().toLocalDate()).distinct()).hasSize(7);
        for (var showtime : showtimes()) {
            assertThat(showtime.getPricePerPerson()).isEqualTo(10000);
            assertThat(Duration.between(showtime.getStartTime(), showtime.getEndTime()).toMinutes()).isEqualTo(120);
            var inv = inventory(showtime);
            assertThat(inv).hasSize(120);
            assertThat(inv.stream().filter(s -> s.getStatus() == SeatStatus.AVAILABLE).count()).isEqualTo(showtime.getAvailableSeats().longValue());
            if (showtime.getScreen().getSeedKey().endsWith("sold-out")) {
                assertThat(showtime.getAvailableSeats()).isZero();
                assertThat(inv).allMatch(s -> s.getStatus() == SeatStatus.BLOCKED && s.getReservation() == null);
            } else if (showtime.getScreen().getSeedKey().endsWith("fragmented")) {
                assertThat(showtime.getAvailableSeats()).isEqualTo(70);
                var free = inv.stream().filter(s -> s.getStatus() == SeatStatus.AVAILABLE).map(ShowtimeSeat::getSeat).toList();
                assertThat(free).allMatch(a -> free.stream().noneMatch(b -> a.getSeatRow().equals(b.getSeatRow())
                        && a.getAdjacencySegment().equals(b.getAdjacencySegment()) && b.getPositionInSegment() == a.getPositionInSegment() + 1));
            } else assertThat(showtime.getAvailableSeats()).isEqualTo(120);
        }
        var seats = em.createQuery("from Seat where seatRow = 'A'", Seat.class).getResultList();
        assertThat(seats.stream().filter(s -> s.getSeatNumber() <= 3 || s.getSeatNumber() >= 10)).allMatch(s -> s.getSeatPosition() == SeatPosition.SIDE_FRONT);
        assertThat(seats.stream().map(Seat::getAdjacencySegment).distinct()).containsExactlyInAnyOrder("left", "center", "right");
    }

    @Test void repeatPreservesHeldSeatsReservationsAndExistingShowtimeEdits() {
        seed(CLOCK).seed(List.of(theater.getId()));
        var showtime = showtimes().stream().filter(s -> s.getScreen().getSeedKey().endsWith("normal")).findFirst().orElseThrow();
        var user = new Users(); user.setName("기존 회원"); user.setNickname("보존"); em.persist(user);
        var reservation = new Reservation(); reservation.setUser(user); reservation.setShowtime(showtime);
        reservation.setReservationType(ReservationType.NORMAL); reservation.setTotalAmount(10000);
        reservation.setCreatedAt(LocalDateTime.now(CLOCK)); reservation.setUpdatedAt(LocalDateTime.now(CLOCK)); em.persist(reservation);
        var held = inventory(showtime).getFirst(); held.setStatus(SeatStatus.HOLDING); held.setReservation(reservation);
        held.setHoldExpiredAt(LocalDateTime.now(CLOCK).plusMinutes(5));
        showtime.setAvailableSeats(119); showtime.setPricePerPerson(17000); em.flush();
        Long heldId = held.getId(), showtimeId = showtime.getId(), reservationId = reservation.getId();
        var result = seed(CLOCK).seed(List.of(theater.getId(), theater.getId()));
        assertThat(result.createdShowtimes()).isZero(); assertThat(result.createdSeats()).isZero();
        em.clear();
        assertThat(em.find(ShowtimeSeat.class, heldId).getStatus()).isEqualTo(SeatStatus.HOLDING);
        assertThat(em.find(ShowtimeSeat.class, heldId).getReservation().getId()).isEqualTo(reservationId);
        assertThat(em.find(Showtime.class, showtimeId).getPricePerPerson()).isEqualTo(17000);
        assertThat(em.find(Showtime.class, showtimeId).getAvailableSeats()).isEqualTo(119);
    }

    @Test void nextDayOnlyAppendsNewFutureDayAndKeepsOldRows() {
        seed(CLOCK).seed(List.of(theater.getId()));
        var ids = showtimes().stream().map(Showtime::getId).toList();
        var later = seed(Clock.offset(CLOCK, Duration.ofDays(1))).seed(List.of(theater.getId()));
        assertThat(later.createdShowtimes()).isEqualTo(3);
        assertThat(showtimes().stream().map(Showtime::getId)).containsAll(ids).hasSize(24);
    }

    @Test void missingRuntimeIsReportedWithoutInventedSchedules() {
        movie.setRunningTime(null);
        var result = seed(CLOCK).seed(List.of(theater.getId()));
        assertThat(result.missingRuntimeMovieIds()).containsExactly(movie.getId());
        assertThat(result.createdShowtimes()).isZero(); assertThat(result.createdScreens()).isZero();
    }

    @Test void doesNotAdoptSameNamedExistingScreenOrTouchUnselectedTheaters() {
        var existing = new Screen(); existing.setTheater(theater); existing.setName("[테스트] normal"); em.persist(existing);
        var other = new Theater(); other.setName("미선택"); other.setBrand(TheaterBrand.CGV); other.setAddress("주소"); other.setKakaoPlaceId("other"); em.persist(other);
        var result = seed(CLOCK).seed(List.of(theater.getId()));
        assertThat(result.createdScreens()).isEqualTo(2); assertThat(result.warnings()).hasSize(1);
        assertThat(existing.getSeedKey()).isNull();
        assertThat(showtimes()).noneMatch(s -> s.getScreen().getId().equals(existing.getId()) || s.getScreen().getTheater().getId().equals(other.getId()));
    }

    @Test void validatesAllTheaterIdsBeforeWriting() {
        assertThatThrownBy(() -> seed(CLOCK).seed(List.of(theater.getId(), Long.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(showtimes()).isEmpty();
        assertThat(em.createQuery("select count(s) from Screen s", Long.class).getSingleResult()).isZero();
    }

    @Test void changedRuntimeCannotOverlapExistingSchedulesAndPastTimesAreNotCreated() {
        seed(CLOCK).seed(List.of(theater.getId()));
        movie.setRunningTime(180);
        var laterClock = Clock.fixed(Instant.parse("2026-10-01T04:00:00Z"), ZoneId.of("Asia/Seoul"));
        assertThat(seed(laterClock).seed(List.of(theater.getId())).createdShowtimes()).isZero();
        assertThat(showtimes()).allMatch(s -> s.getEndTime().equals(s.getStartTime().plusHours(2)));
    }

    @Test void mutatedSeatLayoutIsPreservedAndNotUsedForNewDates() {
        seed(CLOCK).seed(List.of(theater.getId()));
        var screen = showtimes().getFirst().getScreen();
        var seat = inventory(showtimes().getFirst()).getFirst().getSeat(); seat.setActive(false);
        var result = seed(Clock.offset(CLOCK, Duration.ofDays(1))).seed(List.of(theater.getId()));
        assertThat(result.warnings()).contains("수정된 좌석 배치를 보존하고 생성 제외: " + screen.getId());
        assertThat(result.createdShowtimes()).isEqualTo(2); assertThat(seat.isActive()).isFalse();
    }

    @Test void renamedOwnedScreenIsReusedWithoutChangingItsName() {
        seed(CLOCK).seed(List.of(theater.getId()));
        var screen = showtimes().getFirst().getScreen(); screen.setName("수정한 상영관 이름");
        var result = seed(CLOCK).seed(List.of(theater.getId()));
        assertThat(result.createdScreens()).isZero(); assertThat(result.createdShowtimes()).isZero();
        assertThat(screen.getName()).isEqualTo("수정한 상영관 이름");
    }
}
