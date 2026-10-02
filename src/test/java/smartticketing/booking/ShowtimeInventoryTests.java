package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.ShowtimeInventoryService;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ShowtimeInventoryTests {
    private static TemporaryMysqlDatabase database;
    private static final List<String> statements = new ArrayList<>();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private EntityManager em;
    private Screen screen;
    private Movie movie;
    private ShowtimeInventoryService service;

    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(statements::add); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin();
        var theater = new Theater(); theater.setName("자동 극장"); theater.setAddress("주소");
        theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId(UUID.randomUUID().toString()); em.persist(theater);
        screen = new Screen(); screen.setTheater(theater); screen.setName("1관"); screen.setSeedKey("schedule-v1-1"); em.persist(screen);
        movie = new Movie(); movie.setTmdbMovieId(1L); movie.setTitle("영화"); movie.setRunningTime(120); em.persist(movie);
        service = new ShowtimeInventoryService(em, CLOCK);
    }
    @AfterEach void rollback() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.close();
    }

    private Showtime show(int hours, ShowtimeStatus status) {
        var show = new Showtime(); show.setScreen(screen); show.setMovie(movie);
        show.setStartTime(LocalDateTime.now(CLOCK).plusHours(hours)); show.setEndTime(show.getStartTime().plusHours(2));
        show.setStatus(status); show.setTotalSeats(120); show.setAvailableSeats(120); show.setPricePerPerson(10000);
        show.setCreatedAt(LocalDateTime.now(CLOCK)); show.setUpdatedAt(LocalDateTime.now(CLOCK)); em.persist(show);
        return show;
    }
    private List<ShowtimeSeat> inventory(Long showId) {
        return em.createQuery("from ShowtimeSeat where showtime.id=:id order by seat.seatRow, seat.seatNumber", ShowtimeSeat.class)
                .setParameter("id", showId).getResultList();
    }

    @Test void emptyLayoutCreates120SeatsMatchingPreferencesAndFutureInventory() {
        var first = show(1, ShowtimeStatus.SCHEDULED); var second = show(4, ShowtimeStatus.SCHEDULED);
        var past = show(-3, ShowtimeStatus.SCHEDULED);
        assertThat(service.screenIds()).containsExactly(screen.getId());
        assertThat(service.prepare(screen.getId())).isEqualTo(new ShowtimeInventoryService.Result(120, 240, 0));
        var seats = inventory(first.getId()).stream().map(ShowtimeSeat::getSeat).toList();
        assertThat(seats).hasSize(120);
        assertThat(seats.stream().map(Seat::getSeatRow).distinct()).containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        assertThat(seats).filteredOn(s -> s.getSeatRow().equals("G") && s.getSeatNumber() == 4)
                .allMatch(s -> s.getSeatPosition() == SeatPosition.MIDDLE_MIDDLE && s.getPositionInSegment() == 1);
        assertThat(seats).filteredOn(s -> s.getSeatRow().equals("H") && s.getSeatNumber() == 10)
                .allMatch(s -> s.getSeatPosition() == SeatPosition.SIDE_REAR && s.getPositionInSegment() == 1);
        assertThat(seats.stream().filter(s -> s.getAdjacencySegment().equals("center"))).hasSize(60);
        assertThat(inventory(second.getId())).hasSize(120).allMatch(s -> s.getStatus() == SeatStatus.AVAILABLE);
        assertThat(inventory(past.getId())).isEmpty();
        assertThat(first.getTotalSeats()).isEqualTo(120);
        assertThat(first.getAvailableSeats()).isEqualTo(120);
    }

    @Test void repeatAfterReloadPreservesReservationHoldBlockedAndSeatIds() {
        var show = show(1, ShowtimeStatus.SCHEDULED); service.prepare(screen.getId());
        var rows = inventory(show.getId());
        var user = new Users(); user.setName("회원"); user.setNickname("회원"); em.persist(user);
        var reservation = new Reservation(); reservation.setUser(user); reservation.setShowtime(show);
        reservation.setReservationType(ReservationType.NORMAL); reservation.setTotalAmount(10000);
        reservation.setCreatedAt(LocalDateTime.now(CLOCK)); reservation.setUpdatedAt(LocalDateTime.now(CLOCK)); em.persist(reservation);
        var expiry = LocalDateTime.now(CLOCK).plusMinutes(5);
        rows.get(0).setStatus(SeatStatus.HOLDING); rows.get(0).setReservation(reservation); rows.get(0).setHoldExpiredAt(expiry);
        rows.get(1).setStatus(SeatStatus.RESERVED); rows.get(1).setReservation(reservation);
        rows.get(2).setStatus(SeatStatus.BLOCKED); show.setAvailableSeats(117); show.setPricePerPerson(15000);
        var ids = rows.stream().map(ShowtimeSeat::getId).toList();
        var reservationId = reservation.getId(); var showId = show.getId(); var screenId = screen.getId();
        em.flush(); em.clear();
        assertThat(service.prepare(screenId)).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0));
        var after = inventory(showId);
        assertThat(after.stream().map(ShowtimeSeat::getId)).containsExactlyElementsOf(ids);
        assertThat(after.get(0).getStatus()).isEqualTo(SeatStatus.HOLDING);
        assertThat(after.get(0).getHoldExpiredAt()).isEqualTo(expiry);
        assertThat(after.get(0).getReservation().getId()).isEqualTo(reservationId);
        assertThat(after.get(1).getStatus()).isEqualTo(SeatStatus.RESERVED);
        assertThat(after.get(1).getReservation().getId()).isEqualTo(reservationId);
        assertThat(after.get(2).getStatus()).isEqualTo(SeatStatus.BLOCKED);
        assertThat(em.find(Showtime.class, showId).getAvailableSeats()).isEqualTo(117);
        assertThat(em.find(Showtime.class, showId).getPricePerPerson()).isEqualTo(15000);
    }

    @Test void missingConnectionAndNewShowAreAddedWithoutResettingExistingInventory() {
        var first = show(1, ShowtimeStatus.SCHEDULED); service.prepare(screen.getId());
        var rows = inventory(first.getId()); rows.getFirst().setStatus(SeatStatus.BLOCKED);
        em.remove(rows.getLast()); em.flush();
        var second = show(4, ShowtimeStatus.SCHEDULED);
        assertThat(service.prepare(screen.getId())).isEqualTo(new ShowtimeInventoryService.Result(0, 121, 1));
        assertThat(inventory(first.getId())).hasSize(120);
        assertThat(inventory(first.getId()).getFirst().getStatus()).isEqualTo(SeatStatus.BLOCKED);
        assertThat(first.getAvailableSeats()).isEqualTo(119);
        assertThat(second.getAvailableSeats()).isEqualTo(120);
    }

    @Test void existingCustomLayoutAndInactiveSeatsAreNotOverwritten() {
        var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow("Z"); seat.setSeatNumber(1);
        seat.setSeatPosition(SeatPosition.SIDE_REAR); seat.setAdjacencySegment("custom"); seat.setPositionInSegment(8); em.persist(seat);
        var disabled = new Seat(); disabled.setScreen(screen); disabled.setSeatRow("Z"); disabled.setSeatNumber(2);
        disabled.setSeatPosition(SeatPosition.SIDE_REAR); disabled.setActive(false); em.persist(disabled);
        var show = show(1, ShowtimeStatus.SCHEDULED);
        assertThat(service.prepare(screen.getId())).isEqualTo(new ShowtimeInventoryService.Result(0, 1, 1));
        assertThat(inventory(show.getId()).getFirst().getSeat().getId()).isEqualTo(seat.getId());
        assertThat(seat.getAdjacencySegment()).isEqualTo("custom");
        assertThat(seat.getPositionInSegment()).isEqualTo(8);
        assertThat(disabled.isActive()).isFalse();
        assertThat(show.getTotalSeats()).isEqualTo(1);
    }

    @Test void doesNotCreateDefaultLayoutForUnownedOrInactiveScreens() {
        screen.setSeedKey(null); show(1, ShowtimeStatus.SCHEDULED);
        assertThat(service.prepare(screen.getId())).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0));
        screen.setSeedKey("schedule-v1-1"); screen.setActive(false);
        assertThat(service.screenIds()).isEmpty();
        assertThat(service.prepare(screen.getId())).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0));
        assertThat(em.createQuery("select count(s) from Seat s", Long.class).getSingleResult()).isZero();
    }

    @Test void seatsUseOneInsertAndCompleteInventorySkipsWritesAndShowtimeLocks() {
        show(1, ShowtimeStatus.SCHEDULED);
        statements.clear();
        service.prepare(screen.getId()); em.flush();
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into seats "))).hasSize(1);
        var screenId = screen.getId(); em.clear(); statements.clear();
        assertThat(service.prepare(screenId)).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0));
        em.flush();
        assertThat(statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).matches("(?s).*\\b(insert|update|delete)\\s+(into |from )?(seats|showtimes|showtime_seats)\\b.*"));
        assertThat(statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from showtimes")
                && sql.toLowerCase(Locale.ROOT).contains("for update"));
    }

    private void legacyLayout(Long screenId) {
        em.flush();
        em.createNativeQuery("""
                UPDATE seats SET
                    adjacency_segment=CASE WHEN seat_number<=2 THEN 'left' WHEN seat_number<=10 THEN 'center' ELSE 'right' END,
                    position_in_segment=CASE WHEN seat_number<=2 THEN seat_number WHEN seat_number<=10 THEN seat_number-2 ELSE seat_number-10 END,
                    seat_position=CONCAT(CASE WHEN seat_number BETWEEN 3 AND 10 THEN 'MIDDLE_' ELSE 'SIDE_' END,
                        CASE WHEN seat_row<='C' THEN 'FRONT' WHEN seat_row<='G' THEN 'MIDDLE' ELSE 'REAR' END)
                WHERE screen_id=:screen
                """).setParameter("screen", screenId).executeUpdate();
        em.clear();
    }

    @Test void migratesOnlyLegacyDefaultMetadataOnceAndPreservesHeldInventory() {
        var show = show(1, ShowtimeStatus.SCHEDULED); service.prepare(screen.getId());
        var rows = inventory(show.getId());
        var user = new Users(); user.setName("회원"); user.setNickname("회원"); em.persist(user);
        var reservation = new Reservation(); reservation.setUser(user); reservation.setShowtime(show);
        reservation.setReservationType(ReservationType.NORMAL); reservation.setTotalAmount(10000);
        reservation.setCreatedAt(LocalDateTime.now(CLOCK)); reservation.setUpdatedAt(LocalDateTime.now(CLOCK)); em.persist(reservation);
        var expiry = LocalDateTime.now(CLOCK).plusMinutes(5);
        rows.get(2).setStatus(SeatStatus.HOLDING); rows.get(2).setReservation(reservation); rows.get(2).setHoldExpiredAt(expiry);
        rows.get(9).setStatus(SeatStatus.RESERVED); rows.get(9).setReservation(reservation);
        rows.get(10).setStatus(SeatStatus.BLOCKED);
        var seatIds = rows.stream().map(r -> r.getSeat().getId()).toList();
        var inventoryIds = rows.stream().map(ShowtimeSeat::getId).toList();
        var showId = show.getId(); var screenId = screen.getId(); var reservationId = reservation.getId();
        legacyLayout(screenId);
        assertThat(service.prepare(screenId)).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0, 100));
        em.clear(); var after = inventory(showId);
        assertThat(after.stream().map(r -> r.getSeat().getId())).containsExactlyElementsOf(seatIds);
        assertThat(after.stream().map(ShowtimeSeat::getId)).containsExactlyElementsOf(inventoryIds);
        assertThat(after.get(2).getSeat().getAdjacencySegment()).isEqualTo("left");
        assertThat(after.get(2).getStatus()).isEqualTo(SeatStatus.HOLDING);
        assertThat(after.get(2).getHoldExpiredAt()).isEqualTo(expiry);
        assertThat(after.get(2).getReservation().getId()).isEqualTo(reservationId);
        assertThat(after.get(9).getSeat().getAdjacencySegment()).isEqualTo("right");
        assertThat(after.get(9).getStatus()).isEqualTo(SeatStatus.RESERVED);
        assertThat(after.get(10).getStatus()).isEqualTo(SeatStatus.BLOCKED);
        assertThat(service.prepare(screenId).updatedSeats()).isZero();
    }

    @Test void customEditedLegacyLayoutIsNotMigrated() {
        var show = show(1, ShowtimeStatus.SCHEDULED); service.prepare(screen.getId());
        var screenId = screen.getId(); var showId = show.getId(); legacyLayout(screenId);
        var rows = inventory(showId); rows.getLast().getSeat().setAdjacencySegment("custom"); em.flush(); em.clear();
        assertThat(service.prepare(screenId).updatedSeats()).isZero();
        assertThat(inventory(showId).get(2).getSeat().getAdjacencySegment()).isEqualTo("center");
        assertThat(inventory(showId).getLast().getSeat().getAdjacencySegment()).isEqualTo("custom");
    }

    @Test void measuresInitialAndRepeatPreparationForTwentyScreens() {
        var screenIds = new ArrayList<Long>();
        var theater = screen.getTheater();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                screen = new Screen(); screen.setTheater(theater); screen.setName((i + 1) + "관");
                screen.setSeedKey("schedule-v1-" + (i + 1)); em.persist(screen);
            }
            screenIds.add(screen.getId());
            for (int j = 0; j < 49; j++) show(1 + j * 3, ShowtimeStatus.SCHEDULED);
        }
        em.flush(); em.clear(); statements.clear();
        long started = System.nanoTime();
        int created = 0;
        for (Long id : screenIds) created += service.prepare(id).createdShowtimeSeats();
        em.flush();
        long initialMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(created).isEqualTo(117600);
        assertThat(statements.stream().filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into seats "))).hasSize(20);
        em.clear(); statements.clear(); started = System.nanoTime();
        for (Long id : screenIds) assertThat(service.prepare(id)).isEqualTo(new ShowtimeInventoryService.Result(0, 0, 0));
        em.flush();
        long repeatMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert "));
        System.out.printf("INVENTORY_PERFORMANCE screens=20 shows=980 inventory=117600 initialMs=%d repeatMs=%d repeatInserts=0%n", initialMs, repeatMs);
    }
}
