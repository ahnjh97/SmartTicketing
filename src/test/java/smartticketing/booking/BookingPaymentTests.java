package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.mock.env.MockEnvironment;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.repository.*;
import smartticketing.service.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class BookingPaymentTests {
    static TemporaryMysqlDatabase db;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final JsonMapper JSON = JsonMapper.builder().build();
    record Fixture(long user, long other, long group, long reservation, long show) {}
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    static String key() { return UUID.randomUUID().toString(); }
    static <T> T tx(Function<EntityManager,T> fn) {
        try (var em = db.open()) {
            em.getTransaction().begin();
            try { var result = fn.apply(em); em.getTransaction().commit(); return result; }
            catch (RuntimeException e) { em.getTransaction().rollback(); throw e; }
        }
    }
    static TicketService tickets(EntityManager em) {
        var repos = new JpaRepositoryFactory(em);
        return new TicketService(repos.getRepository(TicketRepository.class), repos.getRepository(ReservationRepository.class),
                repos.getRepository(ReservationSeatRepository.class), notifications(em),
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class));
    }
    static NotificationService notifications(EntityManager em) {
        var repos = new JpaRepositoryFactory(em);
        return new NotificationService(repos.getRepository(NotificationRepository.class), repos.getRepository(UsersRepository.class));
    }
    static BookingPaymentService service(EntityManager em, Clock clock, boolean allow) {
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        var ops = new BookingIdempotency(em);
        return new BookingPaymentService(em, new BookingHoldService(em, ops, clock), ops, tickets(em), notifications(em), org.mockito.Mockito.mock(BookingWaitingDispatcher.class), env, allow);
    }
    static Fixture fixture() {
        return tx(em -> {
            var now = LocalDateTime.now(CLOCK);
            var user = new Users(); user.setName("관객"); user.setNickname("관객"); user.setBirthDate(LocalDate.of(1990,1,1)); em.persist(user);
            var other = new Users(); other.setName("타인"); other.setNickname("타인"); em.persist(other);
            var movie = new Movie(); movie.setTitle("일반예매 검증"); movie.setTmdbMovieId(System.nanoTime()); movie.setRating("ALL"); em.persist(movie);
            var theater = new Theater(); theater.setName("격리 극장"); theater.setAddress("서울"); theater.setKakaoPlaceId(key()); theater.setBrand(TheaterBrand.CGV); em.persist(theater);
            var screen = new Screen(); screen.setName("1관"); screen.setTheater(theater); em.persist(screen);
            var show = new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(now.plusHours(5)); show.setEndTime(now.plusHours(7));
            show.setTotalSeats(2); show.setAvailableSeats(2); show.setPricePerPerson(10000); show.setCreatedAt(now); show.setUpdatedAt(now); em.persist(show);
            var seats = new ArrayList<Long>();
            for (int i=1;i<=2;i++) {
                var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow("A"); seat.setSeatNumber(i); seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE); em.persist(seat);
                var inventory = new ShowtimeSeat(); inventory.setShowtime(show); inventory.setSeat(seat); em.persist(inventory); seats.add(seat.getId());
            }
            var group = new BookingRequestGroup(); group.setUser(user); group.setMovie(movie); group.setSelectedShowtime(show); group.setViewingDate(now.toLocalDate());
            group.setEntryPoint(BookingEntryPoint.THEATER_NORMAL); group.setPartySize(2); group.setAdultCount(1); group.setYouthCount(1);
            group.setCompanionsEligible(true); group.setGuardianAccompanying(false); group.setRatingSnapshot("ALL"); group.setCreatedAt(now); group.setUpdatedAt(now); em.persist(group);
            var held = new BookingHoldService(em, new BookingIdempotency(em), CLOCK).manual(user.getId(),group.getId(),key(),new ManualHoldRequest(seats));
            assertThat(held.status()).isEqualTo(201);
            return new Fixture(user.getId(),other.getId(),group.getId(),JSON.readTree(held.body()).get("id").asLong(),show.getId());
        });
    }
    static BookingResult pay(Fixture f, String key, boolean fail, Clock clock) {
        return tx(em -> service(em, clock, true).pay(f.user, f.reservation, key, new MockPaymentRequest(PaymentMethod.MOCK,fail)));
    }
    static BookingResult cancel(Fixture f, String key, Clock clock) { return tx(em -> service(em,clock,true).cancel(f.user,f.reservation,key)); }
    static List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        var barrier = new CyclicBarrier(2); var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return a.call(); });
            var second = executor.submit(() -> { barrier.await(10,TimeUnit.SECONDS); return b.call(); });
            return List.of(first.get(40,TimeUnit.SECONDS),second.get(40,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    static void assertState(Fixture f, ReservationStatus reservation, SeatStatus seats, PaymentStatus payment, TicketStatus ticket, long notifications) {
        tx(em -> {
            assertThat(em.find(Reservation.class,f.reservation).getStatus()).isEqualTo(reservation);
            assertThat(em.createQuery("select s.status from ShowtimeSeat s where s.showtime.id=:id",SeatStatus.class).setParameter("id",f.show).getResultList()).containsOnly(seats).hasSize(2);
            var ps = em.createQuery("select p.status from Payment p where p.reservation.id=:id",PaymentStatus.class).setParameter("id",f.reservation).getResultList();
            if (payment == null) assertThat(ps).isEmpty(); else assertThat(ps).containsExactly(payment);
            var ts = em.createQuery("select t.status from Ticket t where t.reservation.id=:id",TicketStatus.class).setParameter("id",f.reservation).getResultList();
            if (ticket == null) assertThat(ts).isEmpty(); else assertThat(ts).containsExactly(ticket);
            assertThat(em.createQuery("select count(n) from Notification n where n.user.id=:id",Long.class).setParameter("id",f.user).getSingleResult()).isEqualTo(notifications);
            assertThat(em.createQuery("from Notification n where n.user.id=:id",Notification.class).setParameter("id",f.user).getResultList())
                    .allSatisfy(n -> { assertThat(n.getBookingGroupId()).isEqualTo(f.group); assertThat(n.getReservationId()).isEqualTo(f.reservation); });
            if (reservation != ReservationStatus.PENDING) assertThat(em.find(BookingGroupHold.class,f.group)).isNull();
            assertThat(em.find(Showtime.class,f.show).getAvailableSeats()).isEqualTo(seats == SeatStatus.AVAILABLE ? 2 : 0);
            return null;
        });
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void failureRetryUsesServerPriceAndOriginalDeadlineAndReplayDoesNotNotifyAgain() {
        var f = fixture(); var failedKey = key(); var successKey = key();
        var failed = pay(f,failedKey,true,CLOCK);
        assertThat(JSON.readTree(failed.body()).get("status").asText()).isEqualTo("FAILED");
        assertThat(pay(f,failedKey,true,CLOCK)).isEqualTo(failed);
        assertState(f,ReservationStatus.PENDING,SeatStatus.HOLDING,PaymentStatus.FAILED,null,1);
        var success = pay(f,successKey,false,Clock.offset(CLOCK,Duration.ofMinutes(4)));
        assertThat(JSON.readTree(success.body()).get("amount").asInt()).isEqualTo(18000);
        assertThat(JSON.readTree(success.body()).get("reservation").get("expiresAt").asText()).isEqualTo("2026-10-01T09:05:00+09:00");
        assertThat(pay(f,successKey,false,CLOCK)).isEqualTo(success);
        assertThat(pay(f,successKey,true,CLOCK).status()).isEqualTo(409);
        assertThat(pay(f,key(),false,CLOCK).status()).isEqualTo(201);
        assertState(f,ReservationStatus.CONFIRMED,SeatStatus.RESERVED,PaymentStatus.SUCCESS,TicketStatus.VALID,1);
    }

    @Test void recoveryDiscoversConfirmedReservationAfterRestartAndPreservesLegacyNotifications() {
        var f = fixture();
        long legacy = tx(em -> notifications(em).create(f.user, NotificationType.PAYMENT_FAILED, "기존 알림 보존").getId());
        pay(f, key(), false, CLOCK);
        db.restartPersistence();
        tx(em -> {
            var recovery = new BookingRecoveryService(em, new BookingHoldService(em, new BookingIdempotency(em), CLOCK));
            var page = recovery.list(f.user, null);
            assertThat(page.items()).hasSize(1);
            assertThat(page.items().getFirst().path()).contains("group=" + f.group, "reservation=" + f.reservation, "entry=THEATER_NORMAL");
            assertThat(recovery.list(f.other, null).items()).isEmpty();
            assertThat(recovery.one(f.user, f.group).status()).isEqualTo(BookingGroupStatus.COMPLETED);
            var old = notifications(em).list(f.user, false).stream().filter(n -> n.id().equals(legacy)).findFirst().orElseThrow();
            assertThat(old.message()).isEqualTo("기존 알림 보존");
            assertThat(old.groupId()).isNull(); assertThat(old.reservationId()).isNull();
            return null;
        });
    }

    @Test void recoveryCursorDoesNotLoseOlderGroupsOrIncludeAnotherUser() {
        var f = fixture();
        tx(em -> {
            var source = em.find(BookingRequestGroup.class, f.group);
            for (int i = 0; i < 22; i++) {
                var g = new BookingRequestGroup(); g.setUser(source.getUser()); g.setMovie(source.getMovie());
                g.setEntryPoint(BookingEntryPoint.MOVIE_SMART); g.setViewingDate(source.getViewingDate());
                g.setStartTimeFrom(LocalTime.of(22,0)); g.setStartTimeTo(LocalTime.of(2,0));
                g.setPartySize(2); g.setCreatedAt(LocalDateTime.now(CLOCK)); g.setUpdatedAt(LocalDateTime.now(CLOCK)); em.persist(g);
            }
            return null;
        });
        tx(em -> {
            var recovery = new BookingRecoveryService(em, new BookingHoldService(em, new BookingIdempotency(em), CLOCK));
            var first = recovery.list(f.user, null); assertThat(first.items()).hasSize(20); assertThat(first.hasMore()).isTrue();
            assertThat(first.items().getFirst().path()).contains("/movies?entry=MOVIE_SMART", "from=22:00", "until=02:00");
            var second = recovery.list(f.user, first.items().getLast().id());
            assertThat(second.items()).hasSize(3); assertThat(second.hasMore()).isFalse();
            assertThat(second.items().stream().map(BookingRecoveryService.Item::id)).doesNotContainAnyElementsOf(first.items().stream().map(BookingRecoveryService.Item::id).toList());
            return null;
        });
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void wholeCancellationRefundsAndReplaysWithoutDuplicateReleaseOrNotification() {
        var f = fixture(); pay(f,key(),false,CLOCK); var key = key();
        var cancelled = cancel(f,key,CLOCK);
        assertThat(cancelled.status()).isEqualTo(200); assertThat(cancel(f,key,CLOCK)).isEqualTo(cancelled);
        assertThat(cancel(f,key(),CLOCK).status()).isEqualTo(200);
        assertThat(pay(f,key(),false,CLOCK).status()).isEqualTo(409);
        assertState(f,ReservationStatus.CANCELLED,SeatStatus.AVAILABLE,PaymentStatus.CANCELLED,TicketStatus.CANCELLED,0);
    }
    @Test void unpaidCancellationDoesNotInventPaymentOrTicket() {
        var f = fixture(); assertThat(cancel(f,key(),CLOCK).status()).isEqualTo(200);
        assertState(f,ReservationStatus.CANCELLED,SeatStatus.AVAILABLE,null,null,0);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void ownershipIsRequiredForPaymentReadPayAndCancel() {
        var f = fixture();
        assertThat(tx(em -> service(em,CLOCK,true).pay(f.other,f.reservation,key(),new MockPaymentRequest(PaymentMethod.MOCK,false))).status()).isEqualTo(404);
        assertThat(tx(em -> service(em,CLOCK,true).cancel(f.other,f.reservation,key())).status()).isEqualTo(404);
        assertThatThrownBy(() -> tx(em -> service(em,CLOCK,true).get(f.other,f.reservation))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertState(f,ReservationStatus.PENDING,SeatStatus.HOLDING,null,null,0);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void exactExpiryAndShowStartAreServerBoundaries() {
        var f = fixture();
        assertThat(pay(f,key(),false,Clock.offset(CLOCK,Duration.ofMinutes(5))).status()).isEqualTo(409);
        assertState(f,ReservationStatus.EXPIRED,SeatStatus.AVAILABLE,null,null,0);
        var g = fixture(); pay(g,key(),false,CLOCK);
        assertThat(cancel(g,key(),Clock.offset(CLOCK,Duration.ofHours(5))).status()).isEqualTo(409);
        assertState(g,ReservationStatus.CONFIRMED,SeatStatus.RESERVED,PaymentStatus.SUCCESS,TicketStatus.VALID,0);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void concurrentSameAndDifferentKeysCreateOnePaymentTicketNotification() throws Exception {
        var f=fixture(); var key=key(); race(() -> pay(f,key,false,CLOCK),() -> pay(f,key,false,CLOCK));
        race(() -> pay(f,key(),false,CLOCK),() -> pay(f,key(),false,CLOCK));
        assertState(f,ReservationStatus.CONFIRMED,SeatStatus.RESERVED,PaymentStatus.SUCCESS,TicketStatus.VALID,0);
    }
    @Test void concurrentPaymentCancellationAndExpiryStayConsistent() throws Exception {
        var f=fixture(); race(() -> pay(f,key(),false,CLOCK),() -> cancel(f,key(),CLOCK));
        tx(em -> {
            assertThat(em.find(Reservation.class,f.reservation).getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            var ps=em.createQuery("select p.status from Payment p where p.reservation.id=:id",PaymentStatus.class).setParameter("id",f.reservation).getResultList();
            assertThat(ps).allMatch(s -> s==PaymentStatus.CANCELLED); return null;
        });
        var g=fixture(); var expired=Clock.offset(CLOCK,Duration.ofMinutes(5));
        race(() -> pay(g,key(),false,expired),() -> tx(em -> new BookingHoldService(em,new BookingIdempotency(em),expired).expire(g.group)));
        assertState(g,ReservationStatus.EXPIRED,SeatStatus.AVAILABLE,null,null,0);
        var h=fixture(); race(() -> cancel(h,key(),expired),() -> tx(em -> new BookingHoldService(em,new BookingIdempotency(em),expired).expire(h.group)));
        assertState(h,ReservationStatus.EXPIRED,SeatStatus.AVAILABLE,null,null,0);
    }
    @Test void legacyTicketIssueRacesWithPaymentWithoutDuplicateNotification() throws Exception {
        var f=fixture(); pay(f,key(),false,CLOCK);
        race(() -> pay(f,key(),false,CLOCK),() -> tx(em -> tickets(em).issue(f.user,f.reservation)));
        assertState(f,ReservationStatus.CONFIRMED,SeatStatus.RESERVED,PaymentStatus.SUCCESS,TicketStatus.VALID,0);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void databaseFailureRollsBackPaymentTicketInventoryAndOperationThenSameKeyCanRetry() {
        var f=fixture(); var key=key();
        assertThatThrownBy(() -> tx(em -> { service(em,CLOCK,true).pay(f.user,f.reservation,key,new MockPaymentRequest(PaymentMethod.MOCK,false));
            em.createNativeQuery("insert into tickets(reservation_id,ticket_number,status,created_at,updated_at) values(:id,'conflict','VALID',now(),now())",Object.class)
                    .setParameter("id",f.reservation).executeUpdate(); return null; })).isInstanceOf(RuntimeException.class);
        assertState(f,ReservationStatus.PENDING,SeatStatus.HOLDING,null,null,0);
        assertThat(pay(f,key,false,CLOCK).status()).isEqualTo(201);
    }
    @Test void failureInjectionRequiresExplicitConfigurationAndPriceCorruptionNeverCharges() {
        var f=fixture();
        assertThat(tx(em -> service(em,CLOCK,false).pay(f.user,f.reservation,key(),new MockPaymentRequest(PaymentMethod.MOCK,true))).status()).isEqualTo(400);
        tx(em -> { em.find(Reservation.class,f.reservation).setTotalAmount(1); return null; });
        assertThatThrownBy(() -> pay(f,key(),false,CLOCK)).isInstanceOf(IllegalStateException.class);
        assertState(f,ReservationStatus.PENDING,SeatStatus.HOLDING,null,null,0);
    }

    @Test void paymentBeforeDeadlineRacingExpiredWorkerHasOnlyOneTerminalOutcome() throws Exception {
        var f=fixture();
        race(() -> pay(f,key(),false,CLOCK),() -> tx(em -> new BookingHoldService(em,new BookingIdempotency(em),
                Clock.offset(CLOCK,Duration.ofMinutes(5))).expire(f.group)));
        var state=tx(em -> em.find(Reservation.class,f.reservation).getStatus());
        if(state==ReservationStatus.CONFIRMED) assertState(f,state,SeatStatus.RESERVED,PaymentStatus.SUCCESS,TicketStatus.VALID,0);
        else assertState(f,ReservationStatus.EXPIRED,SeatStatus.AVAILABLE,null,null,0);
    }
    @Test void distinctConcurrentCancelRequestsDoNotDoubleNotifyOrRelease() throws Exception {
        var f=fixture(); pay(f,key(),false,CLOCK);
        race(() -> cancel(f,key(),CLOCK),() -> cancel(f,key(),CLOCK));
        assertState(f,ReservationStatus.CANCELLED,SeatStatus.AVAILABLE,PaymentStatus.CANCELLED,TicketStatus.CANCELLED,0);
    }
    @Test void staleRepeatableReadSnapshotCannotReturnPendingAfterPaymentCommits() {
        var f=fixture();
        try(var em=db.open()) {
            em.getTransaction().begin();
            assertThat(em.createQuery("select r.status from Reservation r where r.id=:id",ReservationStatus.class).setParameter("id",f.reservation).getSingleResult()).isEqualTo(ReservationStatus.PENDING);
            pay(f,key(),false,CLOCK);
            assertThat(new BookingHoldService(em,new BookingIdempotency(em),CLOCK).reservation(f.user,f.reservation).status()).isEqualTo(ReservationStatus.CONFIRMED);
            em.getTransaction().commit();
        }
    }
    @Test void cancellationPreservesInconsistentInventoryInsteadOfPartialRefund() {
        var f=fixture(); pay(f,key(),false,CLOCK);
        tx(em -> { em.createQuery("select s from ShowtimeSeat s where s.showtime.id=:id order by s.id",ShowtimeSeat.class)
                .setParameter("id",f.show).getResultList().getFirst().setStatus(SeatStatus.BLOCKED); return null; });
        assertThatThrownBy(() -> cancel(f,key(),CLOCK)).isInstanceOf(IllegalStateException.class);
        tx(em -> {
            assertThat(em.find(Reservation.class,f.reservation).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(em.createQuery("select p.status from Payment p where p.reservation.id=:id",PaymentStatus.class).setParameter("id",f.reservation).getSingleResult()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(em.createQuery("select t.status from Ticket t where t.reservation.id=:id",TicketStatus.class).setParameter("id",f.reservation).getSingleResult()).isEqualTo(TicketStatus.VALID);
            return null;
        });
    }
}
