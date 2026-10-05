package smartticketing.booking;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class BookingSmartTests {
    static TemporaryMysqlDatabase db;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    static final JsonMapper JSON = JsonMapper.builder().build();
    record Fixture(long user, long other, long group, long otherGroup, long movie, List<Long> shows, List<Long> theaters) {}
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    static String key() { return UUID.randomUUID().toString(); }
    static <T> T tx(Function<EntityManager,T> fn) {
        try (var em = db.open()) {
            em.getTransaction().begin();
            try { T value = fn.apply(em); em.getTransaction().commit(); return value; }
            catch (RuntimeException ex) { em.getTransaction().rollback(); throw ex; }
        }
    }
    static BookingSmartService service(Runnable afterSearch) {
        var em = SharedEntityManagerCreator.createSharedEntityManager(db.factory());
        var ops = new BookingIdempotency(em); var holds = new BookingHoldService(em, ops, CLOCK);
        var delegate = new JpaTransactionManager(db.factory());
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition d) { return delegate.getTransaction(d); }
            public void commit(TransactionStatus s) {
                if (TransactionSynchronizationManager.isCurrentTransactionReadOnly() && afterSearch != null) afterSearch.run();
                delegate.commit(s);
            }
            public void rollback(TransactionStatus s) { delegate.rollback(s); }
        };
        return new BookingSmartService(em, holds, ops, manager);
    }
    static long value(BookingResult r, String field) { return JSON.readTree(r.body()).get(field).asLong(); }
    static List<Long> seatIds(BookingResult r) {
        var ids = new ArrayList<Long>(); JSON.readTree(r.body()).get("seatIds").forEach(n -> ids.add(n.asLong())); return ids;
    }
    static Fixture fixture(boolean movieMode, int party, int count) {
        return tx(em -> {
            var user = new Users(); user.setName("스마트 관객"); user.setNickname("스마트"); user.setBirthDate(LocalDate.of(1990,1,1)); em.persist(user);
            var other = new Users(); other.setName("경쟁 관객"); other.setNickname("경쟁"); other.setBirthDate(LocalDate.of(1990,1,1)); em.persist(other);
            var movie = new Movie(); movie.setTitle("스마트 검증"); movie.setRating("ALL"); movie.setTmdbMovieId(System.nanoTime()); em.persist(movie);
            var shows = new ArrayList<Showtime>(); var theaters = new ArrayList<Theater>();
            for (int t=0;t<2;t++) {
                var theater = new Theater(); theater.setName("극장"+t); theater.setAddress("서울"); theater.setKakaoPlaceId(key()); theater.setBrand(TheaterBrand.CGV); em.persist(theater); theaters.add(theater);
                var screen = new Screen(); screen.setTheater(theater); screen.setName("1관"); em.persist(screen);
                var show = new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(NOW.plusHours(4-t)); show.setEndTime(show.getStartTime().plusHours(2));
                show.setTotalSeats(count); show.setAvailableSeats(count); show.setPricePerPerson(10000); show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show); shows.add(show);
                for (int i=1;i<=count;i++) {
                    var seat = new Seat(); seat.setScreen(screen); seat.setSeatRow("A"); seat.setSeatNumber(i); seat.setAdjacencySegment("center"); seat.setPositionInSegment(i);
                    seat.setSeatPosition(t==0 ? SeatPosition.MIDDLE_FRONT : SeatPosition.MIDDLE_MIDDLE); em.persist(seat);
                    var row = new ShowtimeSeat(); row.setShowtime(show); row.setSeat(seat); em.persist(row);
                }
            }
            var a = group(em,user,movie,shows.getFirst(),theaters,movieMode,party);
            var b = group(em,other,movie,shows.getFirst(),theaters,movieMode,party);
            return new Fixture(user.getId(),other.getId(),a.getId(),b.getId(),movie.getId(),shows.stream().map(Showtime::getId).toList(),theaters.stream().map(Theater::getId).toList());
        });
    }
    static BookingRequestGroup group(EntityManager em, Users user, Movie movie, Showtime show, List<Theater> theaters, boolean mode, int party) {
        var g = new BookingRequestGroup(); g.setUser(user); g.setMovie(movie); g.setEntryPoint(mode?BookingEntryPoint.MOVIE_SMART:BookingEntryPoint.THEATER_SMART);
        g.setViewingDate(NOW.toLocalDate()); g.setPartySize(party); g.setAdultCount(party); g.setYouthCount(0); g.setRatingSnapshot("ALL"); g.setCompanionsEligible(true); g.setGuardianAccompanying(false);
        if (mode) { g.setStartTimeFrom(LocalTime.of(10,0)); g.setStartTimeTo(LocalTime.of(22,0)); } else g.setSelectedShowtime(show);
        g.getTheaterPreferences().addAll(theaters); g.getSeatPreferences().addAll(List.of(SeatPosition.MIDDLE_MIDDLE,SeatPosition.MIDDLE_FRONT));
        g.setCreatedAt(NOW); g.setUpdatedAt(NOW); em.persist(g); return g;
    }
    static List<ShowtimeSeat> inventory(EntityManager em, long show) {
        return em.createQuery("select i from ShowtimeSeat i where i.showtime.id=:id order by i.id",ShowtimeSeat.class).setParameter("id",show).getResultList();
    }
    static BookingResult hold(Fixture f) { return service(null).hold(f.user,f.group,key()); }
    static void code(BookingResult r, String code) { assertThat(r.status()).isEqualTo(409); assertThat(JSON.readTree(r.body()).get("code").asText()).isEqualTo(code); }
    static void noBooking(Fixture f) {
        tx(em -> { assertThat(em.createQuery("select count(r) from Reservation r where r.requestGroup.id=:g",Long.class).setParameter("g",f.group).getSingleResult()).isZero();
            assertThat(em.find(BookingGroupHold.class,f.group)).isNull();
            assertThat(em.createQuery("select count(w) from WaitingQueue w where w.requestGroup.id=:g",Long.class).setParameter("g",f.group).getSingleResult()).isZero(); return null; });
    }

    @Test void movieRangeIncludesMaximumStartEvenWhenMovieEndsLater() {
        var f = fixture(true, 2, 4);
        tx(em -> {
            var group = em.find(BookingRequestGroup.class, f.group);
            group.setStartTimeFrom(LocalTime.of(11, 30));
            group.setStartTimeTo(LocalTime.of(12, 0));
            var query = new ShowtimeQueryService(em, new BookingCatalogService(em), CLOCK);
            var shows = query.showtimes(f.movie, null, NOW.toLocalDate(), LocalTime.of(11, 30), LocalTime.of(12, 0));
            assertThat(shows.items()).extracting(ShowtimeResponse.ShowtimeItem::id).containsExactly(f.shows.getLast());
            assertThat(shows.items().getFirst().endTime().toLocalTime()).isEqualTo(LocalTime.of(14, 0));
            var outside = query.showtimes(f.movie, null, NOW.toLocalDate(), LocalTime.of(11, 0), LocalTime.of(11, 30));
            assertThat(outside.items()).isEmpty();
            return null;
        });
        var result = hold(f);
        assertThat(result.status()).isEqualTo(201);
        assertThat(value(result, "showtimeId")).isEqualTo(f.shows.getLast());
    }

    @Test void seatPriorityPrecedesTheaterAndUsesSnapshot() {
        var f=fixture(true,2,4);
        tx(em -> { var live = new UserPreferredSeat(); live.setUser(em.find(Users.class,f.user)); live.setPriority(1); live.setSeatPosition(SeatPosition.SIDE_REAR); em.persist(live); return null; });
        var result=hold(f); assertThat(result.status()).isEqualTo(201); assertThat(value(result,"showtimeId")).isEqualTo(f.shows.getLast());
        assertThat(JSON.readTree(result.body()).get("expiresAt").asText()).isEqualTo("2026-10-02T09:05:00+09:00");
    }
    @Test void theaterPriorityPrecedesTimeAndSeatsPreferCenter() {
        var f=fixture(true,2,4);
        tx(em -> { inventory(em,f.shows.getFirst()).forEach(i -> i.getSeat().setSeatPosition(SeatPosition.MIDDLE_MIDDLE)); return null; });
        var r=hold(f); assertThat(value(r,"showtimeId")).isEqualTo(f.shows.getFirst());
        assertThat(JSON.readTree(r.body()).get("seatLabels").toString()).isEqualTo("[\"A2\",\"A3\"]");
    }
    @Test void theaterModeStaysInSelectedShowAndFallsBackToUnpreferredSeats() {
        var f=fixture(false,2,4);
        tx(em -> { inventory(em,f.shows.getFirst()).forEach(i -> i.getSeat().setSeatPosition(SeatPosition.SIDE_REAR)); return null; });
        var r=hold(f); assertThat(r.status()).isEqualTo(201); assertThat(value(r,"showtimeId")).isEqualTo(f.shows.getFirst());
    }
    @Test void missingSeatPreferencesUseDeterministicOrderButMissingTheatersNeverExpands() {
        var f=fixture(false,2,4); tx(em -> { em.find(BookingRequestGroup.class,f.group).getSeatPreferences().clear(); return null; });
        assertThat(hold(f).status()).isEqualTo(201);
        var m=fixture(true,2,4); tx(em -> { em.find(BookingRequestGroup.class,m.group).getTheaterPreferences().clear(); return null; });
        code(hold(m),"NO_THEATER_SCOPE"); noBooking(m);
    }
    @Test void soldOutFailureReplaysAndNeverRegistersWaiting() {
        var f=fixture(false,2,4); tx(em -> { inventory(em,f.shows.getFirst()).forEach(i -> i.setStatus(SeatStatus.BLOCKED)); return null; });
        var s=service(null); String key=key(); var failed=s.hold(f.user,f.group,key); code(failed,"SOLD_OUT");
        tx(em -> { inventory(em,f.shows.getFirst()).forEach(i -> i.setStatus(SeatStatus.AVAILABLE)); return null; });
        assertThat(s.hold(f.user,f.group,key)).isEqualTo(failed); noBooking(f);
        assertThat(s.hold(f.user,f.group,key()).status()).isEqualTo(201);
    }
    @Test void fourPeopleCanUseTwoPairsAcrossAislesGapsOrRows() {
        for (String boundary:List.of("aisle","gap","row")) {
            var f=fixture(false,4,4); tx(em -> { var rows=inventory(em,f.shows.getFirst());
                for(int i=2;i<4;i++) { var seat=rows.get(i).getSeat();
                    if(boundary.equals("aisle")) seat.setAdjacencySegment("right");
                    if(boundary.equals("row")) seat.setSeatRow("B");
                    if(boundary.equals("gap")) seat.setPositionInSegment(i+3);
                } return null; });
            var result = hold(f); assertThat(result.status()).isEqualTo(201);
            assertThat(seatIds(result)).hasSize(4);
        }
    }

    static void partition(List<ShowtimeSeat> rows, int... parts) {
        int index = 0;
        for (int block = 0; block < parts.length; block++)
            for (int offset = 0; offset < parts[block]; offset++) {
                var seat = rows.get(index++).getSeat();
                seat.setSeatRow(String.valueOf((char) ('A' + block)));
                seat.setAdjacencySegment(block % 2 == 0 ? "left" : "right");
                seat.setPositionInSegment(offset + 1);
            }
    }

    @Test void allApprovedSplitPatternsAcquireEveryoneInOneReservation() {
        for (int[] parts : List.of(new int[]{2,2}, new int[]{2,3}, new int[]{2,2,2}, new int[]{3,3}, new int[]{2,4})) {
            int party = java.util.Arrays.stream(parts).sum(); var f = fixture(false,party,party);
            tx(em -> { partition(inventory(em,f.shows.getFirst()),parts); return null; });
            var result = hold(f); assertThat(result.status()).isEqualTo(201);
            assertThat(seatIds(result)).hasSize(party);
            tx(em -> {
                assertThat(inventory(em,f.shows.getFirst())).allMatch(i -> i.getStatus() == SeatStatus.HOLDING);
                assertThat(inventory(em,f.shows.getFirst()).stream().map(i -> i.getReservation().getId()).distinct()).hasSize(1);
                return null;
            });
        }
    }

    @Test void singlePersonFragmentsAndSplitsForTwoOrThreeAreRejected() {
        for (int[] parts : List.of(new int[]{1,1}, new int[]{1,2}, new int[]{1,3}, new int[]{1,4}, new int[]{1,5}, new int[]{1,2,3})) {
            int party = java.util.Arrays.stream(parts).sum(); var f = fixture(false,party,party);
            tx(em -> { partition(inventory(em,f.shows.getFirst()),parts); return null; });
            code(hold(f),"NO_CONTIGUOUS_SEATS"); noBooking(f);
        }
    }

    @Test void wholeBlockPrecedesPreferredSplitSeatsInAnotherTheater() {
        var f = fixture(true,4,4);
        tx(em -> { partition(inventory(em,f.shows.getLast()),2,2); return null; });
        assertThat(value(hold(f),"showtimeId")).isEqualTo(f.shows.getFirst());
    }

    @Test void competingSplitRequestsNeverDivideTheReservation() throws Exception {
        var f = fixture(false,6,6);
        tx(em -> { partition(inventory(em,f.shows.getFirst()),2,4); return null; });
        var s = service(firstSearchBarrier());
        var results = race(() -> s.hold(f.user,f.group,key()), () -> s.hold(f.other,f.otherGroup,key()));
        assertThat(results).extracting(BookingResult::status).containsExactlyInAnyOrder(201,409);
        tx(em -> {
            assertThat(inventory(em,f.shows.getFirst())).allMatch(i -> i.getStatus() == SeatStatus.HOLDING);
            assertThat(inventory(em,f.shows.getFirst()).stream().map(i -> i.getReservation().getId()).distinct()).hasSize(1);
            return null;
        });
    }
    @Test void unknownAndDuplicateLayoutsAreRejectedEvenWithRemainingSeats() {
        for(boolean duplicate:List.of(false,true)) {
            var f=fixture(false,2,4); tx(em -> { inventory(em,f.shows.getFirst()).getLast().getSeat().setPositionInSegment(duplicate?1:null); return null; });
            code(hold(f),"LAYOUT_UNVERIFIED"); noBooking(f);
        }
    }
    @Test void midnightIncludesBothStartTimeBounds() {
        var f=fixture(true,2,4); tx(em -> {
            em.createNativeQuery("update booking_request_groups set start_time_from='22:00', start_time_to='02:00' where id=:id",Object.class).setParameter("id",f.group).executeUpdate();
            em.find(Showtime.class,f.shows.getFirst()).setStartTime(NOW.toLocalDate().atTime(22,0));
            em.find(Showtime.class,f.shows.getLast()).setStartTime(NOW.toLocalDate().plusDays(1).atTime(2,0)); return null;
        });
        assertThat(value(hold(f),"showtimeId")).isEqualTo(f.shows.getLast());
        var g=fixture(true,2,4); tx(em -> {
            em.createNativeQuery("update booking_request_groups set start_time_from='22:00', start_time_to='02:00' where id=:id",Object.class).setParameter("id",g.group).executeUpdate();
            em.find(Showtime.class,g.shows.getFirst()).setStartTime(NOW.toLocalDate().atTime(22,0));
            em.find(Showtime.class,g.shows.getLast()).setStartTime(NOW.toLocalDate().plusDays(1).atTime(2,1)); return null;
        });
        assertThat(value(hold(g),"showtimeId")).isEqualTo(g.shows.getFirst());
    }
    static List<BookingResult> race(Callable<BookingResult> a, Callable<BookingResult> b) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try { var first=pool.submit(a); var second=pool.submit(b); return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)); }
        finally { pool.shutdownNow(); }
    }
    static Runnable firstSearchBarrier() {
        var barrier=new CyclicBarrier(2); var calls=new AtomicInteger();
        return () -> { if(calls.incrementAndGet()<=2) try { barrier.await(15,TimeUnit.SECONDS); } catch(Exception ex) { throw new RuntimeException(ex); } };
    }
    @Test void lastSeatsCompetitionHasOneWinnerAndNoPartialBooking() throws Exception {
        var f=fixture(false,2,2); var s=service(firstSearchBarrier());
        var results=race(() -> s.hold(f.user,f.group,key()),()->s.hold(f.other,f.otherGroup,key()));
        assertThat(results).extracting(BookingResult::status).containsExactlyInAnyOrder(201,409);
    }
    @Test void contentionRequeriesAndAcquiresADifferentWholeBlock() throws Exception {
        // The central pair must leave another contiguous pair available for the retry.
        var f=fixture(false,2,6); var s=service(firstSearchBarrier());
        var results=race(() -> s.hold(f.user,f.group,key()),()->s.hold(f.other,f.otherGroup,key()));
        assertThat(results).extracting(BookingResult::status).containsOnly(201);
        assertThat(seatIds(results.getFirst())).doesNotContainAnyElementsOf(seatIds(results.getLast()));
    }
    @Test void sameKeyReplaysExactlyAndDifferentKeysCannotCreateTwoReservations() throws Exception {
        var f=fixture(false,2,4); var s=service(firstSearchBarrier()); String key=key();
        var results=race(() -> s.hold(f.user,f.group,key),()->s.hold(f.user,f.group,key));
        assertThat(results.getFirst().status()).isEqualTo(201); assertThat(results.getLast()).isEqualTo(results.getFirst());
        assertThat(s.hold(f.user,f.otherGroup,key).status()).isEqualTo(409);
        code(s.hold(f.user,f.group,key()),"GROUP_UNAVAILABLE");
        assertThat(s.hold(f.other,f.group,key()).status()).isEqualTo(404);
    }
    @Test void retriesAreBoundedAtThreeAndDoNotLeaveProcessingOperations() {
        var f=fixture(false,1,5); var attempts=new AtomicInteger();
        var s=service(() -> { attempts.incrementAndGet(); tx(em -> { inventory(em,f.shows.getFirst()).stream()
                .filter(i->i.getStatus()==SeatStatus.AVAILABLE)
                .min(Comparator.comparingInt((ShowtimeSeat i) -> Math.abs(i.getSeat().getSeatNumber() - 3))
                        .thenComparingInt(i -> i.getSeat().getSeatNumber()))
                .orElseThrow().setStatus(SeatStatus.BLOCKED); return null; }); });
        code(s.hold(f.user,f.group,key()),"RETRY_EXHAUSTED"); assertThat(attempts.get()).isEqualTo(3); noBooking(f);
        tx(em -> { assertThat(em.createQuery("select count(o) from BookingOperation o where o.user.id=:user and o.status=:status",Long.class)
                .setParameter("user",f.user).setParameter("status",BookingOperationStatus.PROCESSING).getSingleResult()).isZero(); return null; });
    }
}
