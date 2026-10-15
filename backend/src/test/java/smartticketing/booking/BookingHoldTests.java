package smartticketing.booking;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class BookingHoldTests {
    private static TemporaryMysqlDatabase database;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private record Fixture(Long user, Long otherUser, Long group, Long otherGroup, Long show, List<Long> seats) {}

    @BeforeAll static void start() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { if (database != null) database.close(); }

    private static <T> T tx(Function<EntityManager,T> action) {
        try (var em = database.open()) {
            em.getTransaction().begin();
            try { T result = action.apply(em); em.getTransaction().commit(); return result; }
            catch (RuntimeException e) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw e; }
        }
    }
    private static BookingHoldService service(EntityManager em, Clock clock) {
        return new BookingHoldService(em, new BookingIdempotency(em), clock);
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static long id(BookingResult response) { return JSON.readTree(response.body()).get("id").asLong(); }
    private Fixture fixture(int party, int count) {
        return tx(em -> {
            var user = new Users(); user.setName("관객"); user.setNickname("관객"); user.setBirthDate(LocalDate.of(1990, 1, 1)); em.persist(user);
            var other = new Users(); other.setName("다른 관객"); other.setNickname("다른 관객"); other.setBirthDate(LocalDate.of(1990, 1, 1)); em.persist(other);
            var movie = new Movie(); movie.setTitle("선점 검증"); movie.setTmdbMovieId(System.nanoTime()); movie.setRating("ALL"); em.persist(movie);
            var theater = new Theater(); theater.setName("검증 극장"); theater.setAddress("검증"); theater.setBrand(TheaterBrand.CGV);
            theater.setKakaoPlaceId(key()); em.persist(theater);
            var screen = new Screen(); screen.setName("검증 관"); screen.setTheater(theater); em.persist(screen);
            var show = new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(NOW.plusHours(5));
            show.setEndTime(NOW.plusHours(7)); show.setTotalSeats(count); show.setAvailableSeats(count); show.setPricePerPerson(10000);
            show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show);
            var ids = new ArrayList<Long>();
            for (int n = 1; n <= count; n++) {
                var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow("A"); seat.setSeatNumber(n);
                seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE); seat.setAdjacencySegment("center"); seat.setPositionInSegment(n); em.persist(seat);
                var inventory = new ShowtimeSeat(); inventory.setShowtime(show); inventory.setSeat(seat); em.persist(inventory); ids.add(seat.getId());
            }
            var g = group(em, user, show, party); var g2 = group(em, other, show, party);
            return new Fixture(user.getId(), other.getId(), g.getId(), g2.getId(), show.getId(), ids);
        });
    }
    private static BookingRequestGroup group(EntityManager em, Users user, Showtime show, int party) {
        var g = new BookingRequestGroup(); g.setUser(user); g.setMovie(show.getMovie()); g.setSelectedShowtime(show);
        g.setEntryPoint(BookingEntryPoint.THEATER_NORMAL); g.setPartySize(party); g.setViewingDate(NOW.toLocalDate());
        g.setAdultCount(party); g.setYouthCount(0); g.setCompanionsEligible(true); g.setGuardianAccompanying(false); g.setRatingSnapshot("ALL");
        g.setCreatedAt(NOW); g.setUpdatedAt(NOW); em.persist(g); return g;
    }
    private BookingResult manual(Fixture f, Long user, Long group, String key, List<Long> seats) {
        return tx(em -> service(em, CLOCK).manual(user, group, key, new ManualHoldRequest(seats)));
    }
    private List<BookingResult> race(Callable<BookingResult> first, Callable<BookingResult> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var b = pool.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void lastSeatCompetitionHasExactlyOneWinner() throws Exception {
        var f = fixture(1, 1);
        var results = race(() -> manual(f, f.user, f.group, key(), f.seats),
                () -> manual(f, f.otherUser, f.otherGroup, key(), f.seats));
        assertThat(results).extracting(BookingResult::status).containsExactlyInAnyOrder(201, 409);
        assertInventory(f, 1, 1, 0);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void conflictingSeatRollsBackEntireMultiSeatIntentAndStoresFailure() {
        var f = fixture(2, 3);
        tx(em -> { var row = inventory(em, f).getLast(); row.setStatus(SeatStatus.BLOCKED); return null; });
        String key = key();
        var rejected = manual(f, f.user, f.group, key, List.of(f.seats.getFirst(), f.seats.getLast()));
        assertThat(rejected.status()).isEqualTo(409); assertInventory(f, 0, 0, 2);
        var retry = manual(f, f.user, f.group, key, List.of(f.seats.getLast(), f.seats.getFirst()));
        assertThat(retry).isEqualTo(rejected);
        tx(em -> { assertThat(em.createQuery("select o.status from BookingOperation o where o.requestKey=:key", BookingOperationStatus.class)
                .setParameter("key", key).getSingleResult()).isEqualTo(BookingOperationStatus.FAILED); return null; });
    }

    @Test void sameGroupCompetingRequestsCannotReplaceTheSlot() throws Exception {
        var f = fixture(1, 2);
        var results = race(() -> manual(f, f.user, f.group, key(), List.of(f.seats.getFirst())),
                () -> manual(f, f.user, f.group, key(), List.of(f.seats.getLast())));
        assertThat(results).extracting(BookingResult::status).containsExactlyInAnyOrder(201, 409);
        assertInventory(f, 1, 1, 1);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void identicalConcurrentRequestReplaysExactlyAndChangedBodyConflicts() throws Exception {
        var f = fixture(1, 2); String key = key();
        var results = race(() -> manual(f, f.user, f.group, key, List.of(f.seats.getFirst())),
                () -> manual(f, f.user, f.group, key, List.of(f.seats.getFirst())));
        assertThat(results.getFirst().status()).isEqualTo(201); assertThat(results.getLast()).isEqualTo(results.getFirst());
        assertThat(manual(f, f.user, f.group, key, List.of(f.seats.getLast())).status()).isEqualTo(409);
        assertInventory(f, 1, 1, 1);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void otherUserCannotHoldOrReadGroupAndReservation() {
        var f = fixture(1, 1);
        assertThat(manual(f, f.otherUser, f.group, key(), f.seats).status()).isEqualTo(404);
        var held = manual(f, f.user, f.group, key(), f.seats);
        assertThatThrownBy(() -> tx(em -> service(em, CLOCK).group(f.otherUser, f.group))).hasMessageContaining("404");
        assertThatThrownBy(() -> tx(em -> service(em, CLOCK).reservation(f.otherUser, id(held)))).hasMessageContaining("404");
        assertInventory(f, 1, 1, 0);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void freshServiceRecoversPersistedExpiryAndStaleRecoveryCannotRemoveNewSlot() {
        var f = fixture(1, 1); String key = key();
        var held = manual(f, f.user, f.group, key, f.seats);
        var before = Clock.offset(CLOCK, Duration.ofSeconds(299));
        assertThat(BookingHoldTests.<Boolean>tx(em -> service(em, before).expire(f.group))).isFalse();
        var after = Clock.offset(CLOCK, Duration.ofMinutes(5));
        assertThat(BookingHoldTests.<List<Long>>tx(em -> service(em, after).expiredGroupIds(100))).contains(f.group);
        assertThat(BookingHoldTests.<Boolean>tx(em -> service(em, after).expire(f.group))).isTrue();
        assertThat(tx(em -> service(em, after).reservation(f.user, id(held))).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertInventory(f, 1, 0, 1);
        var next = tx(em -> service(em, after).manual(f.user, f.group, key(), new ManualHoldRequest(f.seats)));
        assertThat(next.status()).isEqualTo(201);
        assertThat(BookingHoldTests.<Boolean>tx(em -> service(em, after).expire(f.group))).isFalse();
        assertThat(manual(f, f.user, f.group, key, f.seats)).isEqualTo(held);
        assertInventory(f, 2, 1, 0);
    }

    @Test void expiryAndNewAcquisitionOnAnotherGroupNeverDoubleOwnSeat() throws Exception {
        var f = fixture(1, 1); manual(f, f.user, f.group, key(), f.seats);
        var after = Clock.offset(CLOCK, Duration.ofMinutes(5));
        race(() -> { tx(em -> service(em, after).expire(f.group)); return new BookingResult(200, ""); },
                () -> tx(em -> service(em, after).manual(f.otherUser, f.otherGroup, key(), new ManualHoldRequest(f.seats))));
        tx(em -> {
            var rows = inventory(em, f);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getStatus()).isIn(SeatStatus.AVAILABLE, SeatStatus.HOLDING);
            assertThat(em.createQuery("select count(r) from Reservation r where r.showtime.id=:id and r.status=:status", Long.class)
                    .setParameter("id", f.show).setParameter("status", ReservationStatus.PENDING).getSingleResult()).isLessThanOrEqualTo(1);
            return null;
        });
    }

    @Test void unexpectedFailureRollsBackOperationAndAllDomainWritesAllowingRetry() {
        var f = fixture(1, 1); String key = key();
        assertThatThrownBy(() -> tx(em -> {
            service(em, CLOCK).manual(f.user, f.group, key, new ManualHoldRequest(f.seats));
            throw new IllegalStateException("simulated transaction failure");
        })).hasMessageContaining("simulated");
        assertInventory(f, 0, 0, 1);
        tx(em -> { assertThat(em.createQuery("select count(o) from BookingOperation o where o.requestKey=:key", Long.class)
                .setParameter("key", key).getSingleResult()).isZero(); return null; });
        assertThat(manual(f, f.user, f.group, key, f.seats).status()).isEqualTo(201);
    }

    @Test void mysqlConstraintFailureRollsBackPreviouslyFlushedHoldAndLedger() {
        var f = fixture(1, 1); String key = key();
        assertThatThrownBy(() -> tx(em -> {
            var held = service(em, CLOCK).manual(f.user, f.group, key, new ManualHoldRequest(f.seats));
            var duplicate = new ReservationSeat(); duplicate.setReservation(em.find(Reservation.class, id(held)));
            duplicate.setSeat(em.find(Seat.class, f.seats.getFirst())); duplicate.setPrice(10000);
            em.persist(duplicate); em.flush(); return null;
        })).isInstanceOf(jakarta.persistence.PersistenceException.class);
        assertInventory(f, 0, 0, 1);
        assertThat(manual(f, f.user, f.group, key, f.seats).status()).isEqualTo(201);
    }

    @Test void corruptedExpiryDoesNotReleaseBlockedOrForeignInventory() {
        var f = fixture(1, 1); manual(f, f.user, f.group, key(), f.seats);
        tx(em -> { inventory(em, f).getFirst().setStatus(SeatStatus.BLOCKED); return null; });
        assertThatThrownBy(() -> tx(em -> service(em, Clock.offset(CLOCK, Duration.ofMinutes(6))).expire(f.group)))
                .hasMessageContaining("좌석 연결");
        tx(em -> { assertThat(inventory(em, f).getFirst().getStatus()).isEqualTo(SeatStatus.BLOCKED);
            assertThat(em.find(BookingGroupHold.class, f.group)).isNotNull(); return null; });
    }

    @Test void validatesStartedShowPriceBlockedInventoryAndMalformedKeys() {
        var f = fixture(1, 1);
        assertThatThrownBy(() -> manual(f, f.user, f.group, "UPPERCASE-KEY-0001", f.seats)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manual(f, f.user, f.group, key(), List.of(f.seats.getFirst(), f.seats.getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
        tx(em -> { em.find(Showtime.class, f.show).setPricePerPerson(null); return null; });
        assertThat(manual(f, f.user, f.group, key(), f.seats).status()).isEqualTo(409);
        tx(em -> { var show = em.find(Showtime.class, f.show); show.setPricePerPerson(10000); show.setStartTime(NOW); return null; });
        assertThat(manual(f, f.user, f.group, key(), f.seats).status()).isEqualTo(409);
        assertInventory(f, 0, 0, 1);
    }

    @Test void groupCreationSnapshotsPreferencesAndReplaysWithoutCopyingLaterChanges() {
        var f = fixture(2, 2);
        tx(em -> {
            for (int n = 0; n < 2; n++) {
                var p = new UserPreferredSeat(); p.setUser(em.find(Users.class, f.user)); p.setPriority(n + 1);
                p.setSeatPosition(SeatPosition.MIDDLE_MIDDLE); em.persist(p);
            }
            var p = new UserPreferredTheater(); p.setUser(em.find(Users.class, f.user)); p.setPriority(1);
            p.setTheater(em.find(Showtime.class, f.show).getScreen().getTheater()); em.persist(p); return null;
        });
        var request = tx(em -> new CreateBookingGroupRequest(BookingEntryPoint.THEATER_NORMAL,
                em.find(Showtime.class, f.show).getMovie().getId(), NOW.toLocalDate(), 2, null, null, f.show,
                new AudienceRequest(1, 1, true, true)));
        String key = key(); var created = create(f.user, key, request);
        assertThat(created.status()).isEqualTo(201);
        tx(em -> { em.createQuery("delete from UserPreferredSeat p where p.user.id=:id").setParameter("id", f.user).executeUpdate(); return null; });
        assertThat(create(f.user, key, request)).isEqualTo(created);
        var snapshot = tx(em -> service(em, CLOCK).group(f.user, id(created)));
        assertThat(snapshot.seatPreferences()).containsExactly(SeatPosition.MIDDLE_MIDDLE, SeatPosition.MIDDLE_MIDDLE);
        assertThat(snapshot.theaterPreferences()).hasSize(1);
        var held = manual(f, f.user, id(created), key(), f.seats);
        assertThat(held.status()).isEqualTo(201);
        assertThat(JSON.readTree(held.body()).get("totalAmount").asInt()).isEqualTo(18000);
        tx(em -> { assertThat(em.createQuery("select s.price from ReservationSeat s where s.reservation.id=:id order by s.seat.id", Integer.class)
                .setParameter("id", id(held)).getResultList()).containsExactly(10000, 8000); return null; });
    }

    private BookingResult create(Long user, String key, CreateBookingGroupRequest request) {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            return tx(em -> new BookingGroupService(em, service(em, CLOCK), new BookingIdempotency(em), validation.getValidator())
                    .create(user, key, request));
        }
    }

    @Test void groupCreationConcurrencyAndPathReuseHaveNoDuplicateSideEffects() throws Exception {
        var f = fixture(1, 1);
        var request = tx(em -> new CreateBookingGroupRequest(BookingEntryPoint.THEATER_NORMAL,
                em.find(Showtime.class, f.show).getMovie().getId(), NOW.toLocalDate(), 1, null, null, f.show,
                new AudienceRequest(1, 0, true, false)));
        String key = key();
        var results = race(() -> create(f.user, key, request), () -> create(f.user, key, request));
        assertThat(results.getFirst().status()).isEqualTo(201); assertThat(results.getLast()).isEqualTo(results.getFirst());
        String holdKey = key(); var first = manual(f, f.user, f.group, holdKey, f.seats);
        assertThat(first.status()).isEqualTo(201);
        assertThat(manual(f, f.user, id(results.getFirst()), holdKey, f.seats).status()).isEqualTo(409);
        assertInventory(f, 1, 1, 0);
    }

    @Test void smartCandidatesRejectUnknownConnectionsButAllowTwoPairs() {
        var f = fixture(4, 4);
        tx(em -> {
            em.find(BookingRequestGroup.class, f.group).setEntryPoint(BookingEntryPoint.THEATER_SMART);
            // immutable group fields are persisted at creation; change in DB for this test fixture only.
            em.createNativeQuery("update booking_request_groups set entry_point='THEATER_SMART' where id=:id", Object.class)
                    .setParameter("id", f.group).executeUpdate();
            var rows = inventory(em, f);
            rows.get(2).getSeat().setAdjacencySegment(null);
            return null;
        });
        var candidate = new BookingHoldService.Candidate(f.show, f.seats);
        assertThat(tx(em -> service(em, CLOCK).hold(f.user, f.group, key(), BookingHoldService.Source.SMART, candidate)).status()).isEqualTo(409);
        tx(em -> {
            var rows = inventory(em, f);
            rows.get(2).getSeat().setAdjacencySegment("right"); rows.get(2).getSeat().setPositionInSegment(1);
            rows.get(3).getSeat().setAdjacencySegment("right"); rows.get(3).getSeat().setPositionInSegment(2);
            return null;
        });
        assertThat(tx(em -> service(em, CLOCK).hold(f.user, f.group, key(), BookingHoldService.Source.SMART, candidate)).status()).isEqualTo(201);
        assertInventory(f, 1, 1, 0);
    }

    @Test void groupReadProjectsExpiryWithoutReleasingSeatsAndRatingChangeRequiresNewDeclaration() {
        var f = fixture(1, 1);
        tx(em -> { em.find(Showtime.class, f.show).getMovie().setRating("19"); return null; });
        assertThat(manual(f, f.user, f.group, key(), f.seats).status()).isEqualTo(409);
        tx(em -> { em.find(Showtime.class, f.show).getMovie().setRating("ALL"); return null; });
        manual(f, f.user, f.group, key(), f.seats);
        var restored = tx(em -> service(em, Clock.offset(CLOCK, Duration.ofMinutes(6))).group(f.user, f.group));
        assertThat(restored.status()).isEqualTo(BookingGroupStatus.ACTIVE); assertThat(restored.activeReservationId()).isNull();
        assertInventory(f, 1, 1, 0);
        tx(em -> service(em, Clock.offset(CLOCK, Duration.ofMinutes(6))).expire(f.group));
        assertInventory(f, 1, 0, 1);
    }

    @Test void movieRangeCrossesMidnightAndWaitingSharesTheSameActiveSlot() {
        var f = fixture(1, 1);
        tx(em -> { var show = em.find(Showtime.class, f.show); show.setStartTime(NOW.toLocalDate().plusDays(1).atTime(1, 0));
            show.setEndTime(show.getStartTime().plusHours(2)); return null; });
        var request = tx(em -> new CreateBookingGroupRequest(BookingEntryPoint.MOVIE_SMART,
                em.find(Showtime.class, f.show).getMovie().getId(), NOW.toLocalDate(), 1,
                LocalTime.of(22, 0), LocalTime.of(2, 0), null, new AudienceRequest(1, 0, true, false)));
        long group = id(create(f.user, key(), request));
        var candidate = new BookingHoldService.Candidate(f.show, f.seats);
        assertThat(tx(em -> service(em, CLOCK).hold(f.user, group, key(), BookingHoldService.Source.WAITING, candidate)).status()).isEqualTo(201);
        assertThat(tx(em -> service(em, CLOCK).hold(f.user, group, key(), BookingHoldService.Source.SMART, candidate)).status()).isEqualTo(409);
        assertInventory(f, 1, 1, 0);
        tx(em -> { em.find(Showtime.class, f.show).setStartTime(NOW.toLocalDate().plusDays(1).atTime(2, 1)); return null; });
        long otherGroup = id(create(f.otherUser, key(), request));
        assertThat(tx(em -> service(em, CLOCK).hold(f.otherUser, otherGroup, key(), BookingHoldService.Source.SMART, candidate)).status()).isEqualTo(400);
    }

    @Test void readSnapshotCannotResurrectExpiredSlotAndNextCommandCanAcquire() {
        var f = fixture(1, 1); manual(f, f.user, f.group, key(), f.seats);
        var after = Clock.offset(CLOCK, Duration.ofMinutes(6));
        tx(em -> {
            // 실제 MySQL RR snapshot을 먼저 연다. 같은 엔티티를 미리 로딩하지 않는다.
            em.find(Users.class, f.user);
            tx(other -> service(other, after).expire(f.group));
            var fresh = service(em, after).group(f.user, f.group);
            assertThat(fresh.activeReservationId()).isNull();
            return null;
        });
        assertInventory(f, 1, 0, 1);
        // GET and POST have separate transactions/persistence contexts in the API.
        var held = tx(em -> service(em, after).manual(f.user, f.group, key(), new ManualHoldRequest(f.seats)));
        assertThat(held.status()).isEqualTo(201);
        assertInventory(f, 2, 1, 0);
    }

    private static List<ShowtimeSeat> inventory(EntityManager em, Fixture f) {
        return em.createQuery("select s from ShowtimeSeat s where s.showtime.id=:id order by s.id", ShowtimeSeat.class)
                .setParameter("id", f.show).getResultList();
    }
    private void assertInventory(Fixture f, long reservations, long holds, long available) {
        tx(em -> {
            assertThat(em.createQuery("select count(r) from Reservation r where r.showtime.id=:id", Long.class)
                    .setParameter("id", f.show).getSingleResult()).isEqualTo(reservations);
            assertThat(em.createQuery("select count(h) from BookingGroupHold h where h.reservation.showtime.id=:id", Long.class)
                    .setParameter("id", f.show).getSingleResult()).isEqualTo(holds);
            assertThat(inventory(em, f).stream().filter(s -> s.getStatus() == SeatStatus.AVAILABLE).count()).isEqualTo(available);
            return null;
        });
    }
}
