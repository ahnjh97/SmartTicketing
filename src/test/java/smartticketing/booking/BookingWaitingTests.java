package smartticketing.booking;

import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.*;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class BookingWaitingTests {
    @Test void dispatcherAllocatesAllPermittedSplitPatternsAtomically() {
        for (int[] parts : List.of(new int[]{2,2}, new int[]{2,3}, new int[]{2,2,2}, new int[]{3,3}, new int[]{2,4})) {
            int party = Arrays.stream(parts).sum(); var f = fixture(party,party);
            tx(em -> { BookingSmartTests.partition(BookingSmartTests.inventory(em,f.shows.getFirst()),parts); return null; });
            register(f);
            assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isEqualTo(1);
            statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
            tx(em -> {
                var rows = BookingSmartTests.inventory(em,f.shows.getFirst());
                assertThat(rows).hasSize(party).allMatch(i -> i.getStatus() == SeatStatus.HOLDING);
                assertThat(rows.stream().map(i -> i.getReservation().getId()).distinct()).hasSize(1);
                return null;
            });
        }
    }
    static TemporaryMysqlDatabase db;
    static final List<String> readStatements = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Clock CLOCK = BookingSmartTests.CLOCK;
    static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    record Fixture(long user, long group, List<Long> shows) {}
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(readStatements::add); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    static String key() { return UUID.randomUUID().toString(); }
    static <T> T tx(Function<EntityManager,T> fn) {
        try (var em = db.open()) {
            em.getTransaction().begin();
            try { T value = fn.apply(em); em.getTransaction().commit(); return value; }
            catch (RuntimeException ex) { em.getTransaction().rollback(); throw ex; }
        }
    }
    static BookingHoldService holds(EntityManager em, Clock clock) { return new BookingHoldService(em, new BookingIdempotency(em), clock); }
    static BookingWaitingService service(EntityManager em, Clock clock) {
        return new BookingWaitingService(em, holds(em,clock), BookingPaymentTests.service(em,clock,true), new BookingIdempotency(em));
    }
    static BookingWaitingDispatcher dispatcher(Clock clock) {
        var em=SharedEntityManagerCreator.createSharedEntityManager(db.factory());
        return new BookingWaitingDispatcher(em,holds(em,clock),service(em,clock),new JpaTransactionManager(db.factory()));
    }
    static Fixture fixture(int party, int seats) {
        return tx(em -> {
            var user=new Users(); user.setName("대기 관객"); user.setNickname("대기"); user.setBirthDate(LocalDate.of(1990,1,1)); em.persist(user);
            var movie=new Movie(); movie.setTitle("복수 대기 검증"); movie.setRating("ALL"); movie.setTmdbMovieId(System.nanoTime()); em.persist(movie);
            var theater=new Theater(); theater.setName("격리 극장"); theater.setAddress("서울"); theater.setKakaoPlaceId(key()); theater.setBrand(TheaterBrand.CGV); em.persist(theater);
            var shows=new ArrayList<Showtime>();
            for(int n=0;n<2;n++) {
                var screen=new Screen(); screen.setTheater(theater); screen.setName("관"+n); em.persist(screen);
                var show=new Showtime(); show.setMovie(movie); show.setScreen(screen); show.setStartTime(NOW.plusHours(3+n)); show.setEndTime(NOW.plusHours(5+n));
                show.setPricePerPerson(10000); show.setTotalSeats(seats); show.setAvailableSeats(seats); show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show); shows.add(show);
                for(int i=1;i<=seats;i++) {
                    var seat=new Seat(); seat.setScreen(screen); seat.setSeatRow("A"); seat.setSeatNumber(i); seat.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);
                    seat.setAdjacencySegment("center"); seat.setPositionInSegment(i); em.persist(seat);
                    var inventory=new ShowtimeSeat(); inventory.setShowtime(show); inventory.setSeat(seat); em.persist(inventory);
                }
            }
            var group=BookingSmartTests.group(em,user,movie,shows.getFirst(),List.of(theater),true,party);
            return new Fixture(user.getId(),group.getId(),shows.stream().map(Showtime::getId).toList());
        });
    }
    static Fixture another(Fixture f, int party, boolean sameUser) {
        return tx(em -> {
            var original=em.find(BookingRequestGroup.class,f.group);
            Users user;
            if(sameUser) user=original.getUser();
            else { user=new Users(); user.setName("뒤 관객"); user.setNickname("뒤"); user.setBirthDate(LocalDate.of(1990,1,1)); em.persist(user); }
            var group=BookingSmartTests.group(em,user,original.getMovie(),em.find(Showtime.class,f.shows.getFirst()),original.getTheaterPreferences(),true,party);
            return new Fixture(user.getId(),group.getId(),f.shows);
        });
    }
    static BookingResult register(Fixture f,List<Long> ids,String key) { return tx(em -> service(em,CLOCK).register(f.user,f.group,key,new WaitingRequest(ids))); }
    static void register(Fixture f) { assertThat(register(f,f.shows,key()).status()).isEqualTo(201); }
    static WaitingResponse state(Fixture f,Clock clock) { return tx(em -> service(em,clock).get(f.user,f.group)); }
    static WaitingResponse state(Fixture f) { return state(f,CLOCK); }
    static void statuses(Fixture f,QueueStatus... statuses) { assertThat(state(f).items()).extracting(WaitingResponse.Item::status).containsExactly(statuses); }
    static List<ShowtimeSeat> inventory(EntityManager em,Long show) { return BookingSmartTests.inventory(em,show); }

    @Test void activeWaitingReadSkipsHoldLookupAndPreservesOwnership() {
        var f = fixture(2,2); register(f);
        readStatements.clear();
        var response = state(f);
        assertThat(response.items()).hasSize(2);
        assertThat(readStatements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("from booking_group_holds"));
        var other = another(f,2,false);
        assertThatThrownBy(() -> tx(em -> service(em,CLOCK).get(other.user(),f.group())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void smartWaitingReadsBatchRanksAndDoNotAddQueriesForEveryGroup() {
        var f=fixture(2,2); register(f);
        readStatements.clear();
        var first=readCandidates(f);
        int singleQueries=readStatements.size();
        assertThat(first.candidates()).hasSize(1);
        for(int i=0;i<7;i++) register(another(f,2,true));
        readStatements.clear();
        var many=readCandidates(f);
        assertThat(many.candidates()).hasSize(8);
        assertThat(many.candidates().getFirst().waiting().items()).allMatch(q -> q.aheadCount()==0);
        assertThat(many.candidates().getLast().waiting().items()).allMatch(q -> q.aheadCount()==7);
        assertThat(readStatements.size()).isLessThanOrEqualTo(singleQueries+1);
        assertThat(readStatements).noneMatch(sql -> sql.toLowerCase().contains("for update"));
        assertThat(many.candidates().getFirst().waiting().nextPollAfterMs()).isEqualTo(5000);
    }

    private SmartBookingCandidatesService.Candidates readCandidates(Fixture f) {
        return tx(em -> new SmartBookingCandidatesService(em,null,holds(em,CLOCK),service(em,CLOCK),
                BookingPaymentTests.service(em,CLOCK,true),new BookingIdempotency(em)).get(f.user,null));
    }

    @Test void expiryBatchCursorPassesUnremovedRowsWithTheSameTimestamp() {
        var groups=new ArrayList<Long>();
        var cutoff=NOW.minusYears(1);
        for(int i=0;i<3;i++) {
            var f=fixture(1,1); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst()); groups.add(f.group);
            tx(em -> { em.find(BookingGroupHold.class,f.group).setExpiresAt(cutoff); return null; });
        }
        var first=tx(em -> holds(em,CLOCK).expiredBatch(cutoff,null,2));
        assertThat(first).extracting(BookingHoldService.ExpiryCandidate::groupId).containsExactly(groups.get(0),groups.get(1));
        var next=tx(em -> holds(em,CLOCK).expiredBatch(cutoff,first.getLast(),2));
        assertThat(next).extracting(BookingHoldService.ExpiryCandidate::groupId).containsExactly(groups.get(2));
        var backlog=tx(em -> holds(em,CLOCK).expiryBacklog());
        assertThat(backlog.count()).isGreaterThanOrEqualTo(3);
        assertThat(backlog.oldestDelayMs()).isGreaterThanOrEqualTo(Duration.ofDays(365).toMillis());
    }

    @Test void allStatusReadsProjectExpiryWithoutLocksOrWritesAndWorkerStillReleasesSeats() {
        var f=fixture(2,2); register(f);
        dispatcher(CLOCK).dispatch(f.shows.getFirst());
        long reservation=tx(em -> em.find(BookingGroupHold.class,f.group).getReservation().getId());
        var before=Clock.offset(CLOCK,Duration.ofSeconds(299));
        assertThat(state(f,before).groupStatus()).isEqualTo(BookingGroupStatus.HOLDING);
        assertThat(state(f,before).activeReservationId()).isEqualTo(reservation);
        var after=Clock.offset(CLOCK,Duration.ofMinutes(5));
        readStatements.clear();
        tx(em -> {
            var hold=holds(em,after);
            var group=hold.group(f.user,f.group);
            assertThat(group.status()).isEqualTo(BookingGroupStatus.ACTIVE);
            assertThat(group.activeReservationId()).isNull();
            assertThat(hold.reservation(f.user,reservation).status()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(BookingPaymentTests.service(em,after,true).get(f.user,reservation).reservation().status())
                    .isEqualTo(ReservationStatus.EXPIRED);
            var waiting=service(em,after).get(f.user,f.group);
            assertThat(waiting.groupStatus()).isEqualTo(BookingGroupStatus.ACTIVE);
            assertThat(waiting.activeReservationId()).isNull();
            assertThat(waiting.items()).extracting(WaitingResponse.Item::status)
                    .containsExactly(QueueStatus.EXPIRED,QueueStatus.WAITING);
            assertThat(new BookingRecoveryService(em,hold).one(f.user,f.group).status()).isEqualTo(BookingGroupStatus.ACTIVE);
            var candidates=new SmartBookingCandidatesService(em,null,hold,service(em,after),
                    BookingPaymentTests.service(em,after,true),new BookingIdempotency(em)).get(f.user,f.group);
            assertThat(candidates.candidates()).hasSize(1);
            assertThat(candidates.candidates().getFirst().status()).isEqualTo(BookingGroupStatus.ACTIVE);
            em.flush();
            return null;
        });
        assertThat(readStatements).noneMatch(sql -> sql.toLowerCase().contains("for update")
                || sql.toLowerCase().matches("(?s).*\\b(insert into|update|delete from)\\b.*"));
        tx(em -> {
            assertThat(em.find(BookingRequestGroup.class,f.group).getStatus()).isEqualTo(BookingGroupStatus.HOLDING);
            assertThat(em.find(Reservation.class,reservation).getStatus()).isEqualTo(ReservationStatus.PENDING);
            assertThat(inventory(em,f.shows.getFirst())).allMatch(seat -> seat.getStatus()==SeatStatus.HOLDING);
            return null;
        });
        boolean expired=tx(em -> holds(em,after).expire(f.group));
        assertThat(expired).isTrue();
        tx(em -> {
            assertThat(inventory(em,f.shows.getFirst())).allMatch(seat -> seat.getStatus()==SeatStatus.AVAILABLE);
            return null;
        });
    }

    @Test void waitingReadKeepsAdditionalChoicesAndRejectsForeignOwnersWithoutWrites() {
        var f=fixture(2,2); register(f,List.of(f.shows.getFirst()),key());
        var foreign=another(f,2,false);
        readStatements.clear();
        assertThat(state(f).choices()).extracting(WaitingResponse.Choice::showtimeId).contains(f.shows.getLast());
        assertThatThrownBy(() -> tx(em -> service(em,CLOCK).get(foreign.user,f.group)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tx(em -> holds(em,CLOCK).group(foreign.user,f.group)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tx(em -> new BookingRecoveryService(em,holds(em,CLOCK)).one(foreign.user,f.group)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(readStatements).noneMatch(sql -> sql.toLowerCase().contains("for update")
                || sql.toLowerCase().matches("(?s).*\\b(insert into|update|delete from)\\b.*"));
    }

    @Test void registrationReplayAndNumbersAreDistinctFromAheadCount() {
        var f=fixture(2,2); var next=another(f,2,false); String key=key();
        var first=register(f,f.shows,key); assertThat(first.status()).isEqualTo(201);
        assertThat(register(f,f.shows.reversed(),key)).isEqualTo(first);
        register(next); var s=state(next); assertThat(s.items()).allSatisfy(q -> { assertThat(q.queueNumber()).isEqualTo(2); assertThat(q.aheadCount()).isEqualTo(1); });
        tx(em -> service(em,CLOCK).cancel(f.user,f.group,key()));
        assertThat(state(next).items()).allSatisfy(q -> { assertThat(q.queueNumber()).isEqualTo(2); assertThat(q.aheadCount()).isZero(); });
        assertThat(register(f,f.shows,key()).status()).isEqualTo(409);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void simultaneousShowsAllocateOnlyOneHoldAndPauseOtherQueues() throws Exception {
        var f=fixture(2,2); register(f);
        BookingPaymentTests.race(() -> dispatcher(CLOCK).dispatch(f.shows.getFirst()), () -> dispatcher(CLOCK).dispatch(f.shows.getLast()));
        assertThat(state(f).items()).extracting(WaitingResponse.Item::status).containsExactlyInAnyOrder(QueueStatus.HOLDING,QueueStatus.PAUSED);
        tx(em -> { assertThat(em.createQuery("select count(r) from Reservation r where r.requestGroup.id=:g",Long.class).setParameter("g",f.group).getSingleResult()).isEqualTo(1); return null; });
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void pausedDoesNotBlockOthersAndExpiredRestoresOriginalNumberWithoutRetroactiveGuarantee() {
        var f=fixture(2,2); var other=another(f,2,false); register(f); register(other);
        dispatcher(CLOCK).dispatch(f.shows.getFirst()); statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
        dispatcher(CLOCK).dispatch(f.shows.getLast()); statuses(other,QueueStatus.PAUSED,QueueStatus.HOLDING);
        var later=Clock.offset(CLOCK,Duration.ofMinutes(5));
        tx(em -> holds(em,later).expire(f.group));
        statuses(f,QueueStatus.EXPIRED,QueueStatus.WAITING);
        assertThat(state(f).items()).allSatisfy(q -> assertThat(q.queueNumber()).isEqualTo(1));
        assertThat(dispatcher(later).dispatch(f.shows.getLast())).isZero(); // other user's slot has not been released
        assertThat(register(f,List.of(f.shows.getFirst()),key()).status()).isEqualTo(201);
        statuses(f,QueueStatus.EXPIRED,QueueStatus.WAITING);
        var fresh=another(f,2,true); assertThat(register(fresh,List.of(f.shows.getFirst()),key()).status()).isEqualTo(201);
        assertThat(state(fresh).items().getFirst().queueNumber()).isEqualTo(3);
    }
    @Test void eligibleFifoSkipsImpossiblePartyAndUsesSeatPreference() {
        var f=fixture(3,2); var second=another(f,2,false); register(f); register(second);
        assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isEqualTo(1);
        statuses(f,QueueStatus.WAITING,QueueStatus.WAITING); statuses(second,QueueStatus.HOLDING,QueueStatus.PAUSED);
    }
    @org.junit.jupiter.api.Tag("core")
    @Test void paymentCompletesOwnOpportunityAndCancelsPausedAtomically() {
        var f=fixture(2,2); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst()); long r=state(f).activeReservationId();
        var pay=tx(em -> BookingPaymentTests.service(em,CLOCK,true).pay(f.user,r,key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        assertThat(pay.status()).isEqualTo(201); statuses(f,QueueStatus.COMPLETED,QueueStatus.CANCELLED);
        assertThat(state(f).groupStatus()).isEqualTo(BookingGroupStatus.COMPLETED);
        assertThat(tx(em -> service(em,CLOCK).cancel(f.user,f.group,key())).status()).isEqualTo(409);
    }
    @Test void pendingCancellationResumesOthersButGroupCancellationReleasesEverything() {
        var f=fixture(2,2); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst()); long r=state(f).activeReservationId();
        assertThat(tx(em -> BookingPaymentTests.service(em,CLOCK,true).cancel(f.user,r,key())).status()).isEqualTo(200);
        statuses(f,QueueStatus.CANCELLED,QueueStatus.WAITING);
        dispatcher(CLOCK).dispatch(f.shows.getLast());
        String key=key(); var cancelled=tx(em -> service(em,CLOCK).cancel(f.user,f.group,key));
        assertThat(cancelled.status()).isEqualTo(200);
        var replay=tx(em -> service(em,CLOCK).cancel(f.user,f.group,key)); assertThat(replay).isEqualTo(cancelled);
        statuses(f,QueueStatus.CANCELLED,QueueStatus.CANCELLED);
        tx(em -> { assertThat(em.find(BookingGroupHold.class,f.group)).isNull(); assertThat(inventory(em,f.shows.getLast())).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(SeatStatus.AVAILABLE)); return null; });
    }
    @Test void crossGroupRegistrationForSameShowIsAllowedUnderRace() throws Exception {
        var f=fixture(2,2); var duplicate=another(f,2,true);
        var results=BookingPaymentTests.race(() -> register(f,f.shows,key()), () -> register(duplicate,duplicate.shows,key()));
        assertThat(results.stream().map(r -> ((BookingResult)r).status())).containsExactlyInAnyOrder(201,201);
    }
    @Test void duplicateIdsMismatchAndForeignAccessDoNotWrite() {
        var f=fixture(2,2); var foreign=fixture(2,2);
        assertThatThrownBy(() -> register(f,List.of(f.shows.getFirst(),f.shows.getFirst()),key())).isInstanceOf(IllegalArgumentException.class);
        assertThat(register(f,List.of(f.shows.getFirst(),foreign.shows.getFirst()),key()).status()).isEqualTo(400);
        assertThat(state(f).items()).isEmpty();
        assertThat(tx(em -> service(em,CLOCK).register(foreign.user,f.group,key(),new WaitingRequest(f.shows))).status()).isEqualTo(404);
        assertThatThrownBy(() -> tx(em -> service(em,CLOCK).get(foreign.user,f.group))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void legacyQueuesRemainUntouchedAndStartedShowsNeverAllocate() {
        var f=fixture(2,2);
        tx(em -> { var q=new WaitingQueue(); q.setUser(em.find(Users.class,f.user)); q.setShowtime(em.find(Showtime.class,f.shows.getFirst())); q.setQueueNumber(1); q.setCreatedAt(NOW); q.setUpdatedAt(NOW); em.persist(q); return null; });
        assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isZero();
        var other=another(f,2,false); register(other);
        var late=Clock.offset(CLOCK,Duration.ofHours(4)); dispatcher(late).dispatch(f.shows.getFirst()); dispatcher(late).dispatch(f.shows.getLast());
        assertThat(state(other,late).items()).allSatisfy(q -> assertThat(q.status()).isEqualTo(QueueStatus.EXPIRED));
        tx(em -> { assertThat(em.createQuery("select q.status from WaitingQueue q where q.requestGroup is null and q.showtime.id=:s",QueueStatus.class).setParameter("s",f.shows.getFirst()).getSingleResult()).isEqualTo(QueueStatus.WAITING); return null; });
    }
    @Test void paymentExpiryAndParallelDispatchPreserveSingleOutcome() throws Exception {
        var f=fixture(2,2); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst()); long r=state(f).activeReservationId();
        var later=Clock.offset(CLOCK,Duration.ofMinutes(5));
        BookingPaymentTests.race(() -> tx(em -> BookingPaymentTests.service(em,later,true).pay(f.user,r,key(),new MockPaymentRequest(PaymentMethod.MOCK,false))),
                () -> tx(em -> holds(em,later).expire(f.group)));
        statuses(f,QueueStatus.EXPIRED,QueueStatus.WAITING);
        assertThat(dispatcher(later).dispatch(f.shows.getLast())).isEqualTo(1);
        tx(em -> { assertThat(holds(em,later).expire(f.group)).isFalse(); return null; });
        statuses(f,QueueStatus.EXPIRED,QueueStatus.HOLDING);
    }
    @Test void restartFindsCommittedWaitingWithoutAnInMemoryEvent() {
        var f=fixture(2,2); register(f); db.restartPersistence();
        var d=dispatcher(CLOCK); assertThat(d.pendingShows()).containsAll(f.shows);
        assertThat(d.dispatch(f.shows.getFirst())).isEqualTo(1); statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
    }

    @Test void fullShowsSkipWriteLocksAndReturnToRecoveryAfterSeatRelease() {
        var f=fixture(1,1); register(f); long show=f.shows.getFirst();
        tx(em -> { inventory(em,show).forEach(i -> i.setStatus(SeatStatus.BLOCKED)); return null; });
        var d=dispatcher(CLOCK);
        assertThat(d.pendingShows()).doesNotContain(show);
        readStatements.clear();
        assertThat(d.dispatch(show)).isZero();
        assertThat(readStatements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("for update"));
        statuses(f,QueueStatus.WAITING,QueueStatus.WAITING);
        tx(em -> { inventory(em,show).forEach(i -> i.setStatus(SeatStatus.AVAILABLE)); return null; });
        assertThat(d.pendingShows()).contains(show);
        assertThat(d.dispatch(show)).isEqualTo(1);
        statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
    }

    @Test void closedShowsStillExpireWaitingEvenWithoutAvailableSeats() {
        var f=fixture(1,1); register(f); long show=f.shows.getFirst();
        tx(em -> {
            inventory(em,show).forEach(i -> i.setStatus(SeatStatus.BLOCKED));
            em.find(Showtime.class,show).setStatus(ShowtimeStatus.CANCELLED); return null;
        });
        var d=dispatcher(CLOCK);
        assertThat(d.pendingShows()).contains(show);
        assertThat(d.dispatch(show)).isZero();
        statuses(f,QueueStatus.EXPIRED,QueueStatus.WAITING);
    }

    @Test void restartAndRepeatedDispatchRetainOneLinkedOpportunityNotification() {
        var f = fixture(2,2); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst());
        long reservation = state(f).activeReservationId();
        db.restartPersistence();
        dispatcher(CLOCK).dispatch(f.shows.getFirst()); dispatcher(CLOCK).dispatch(f.shows.getLast());
        tx(em -> {
            var list = BookingPaymentTests.notifications(em).list(f.user, false);
            assertThat(list).hasSize(1);
            assertThat(list.getFirst().type()).isEqualTo(NotificationType.QUEUE_TURN);
            assertThat(list.getFirst().groupId()).isEqualTo(f.group);
            assertThat(list.getFirst().reservationId()).isEqualTo(reservation);
            var recovery = new BookingRecoveryService(em, holds(em, CLOCK));
            assertThat(recovery.one(f.user, f.group).path()).contains("entry=MOVIE_SMART", "reservation=" + reservation);
            return null;
        });
        statuses(f, QueueStatus.HOLDING, QueueStatus.PAUSED);
    }
    @Test void regularSmartHoldAlsoUpdatesMatchingWaitingOpportunityAndExpiryResumesOthers() {
        var f=fixture(2,2); register(f);
        tx(em -> { var ids=inventory(em,f.shows.getFirst()).stream().map(i -> i.getSeat().getId()).toList();
            assertThat(holds(em,CLOCK).hold(f.user,f.group,key(),BookingHoldService.Source.SMART,new BookingHoldService.Candidate(f.shows.getFirst(),ids)).status()).isEqualTo(201); return null; });
        statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
        tx(em -> holds(em,Clock.offset(CLOCK,Duration.ofMinutes(5))).expire(f.group));
        statuses(f,QueueStatus.EXPIRED,QueueStatus.WAITING);
    }

    @Test void actualTwoReservationCancellationsThenConcurrentAllocationKeepOneGroupSlot() throws Exception {
        var f=fixture(2,2); var ownerA=another(f,2,false); var ownerB=another(f,2,false);
        List<Long> reservations=new ArrayList<>();
        for(int index=0;index<2;index++) {
            var owner=index==0?ownerA:ownerB; long show=f.shows.get(index);
            long reservation=tx(em -> {
                var seats=inventory(em,show).stream().map(i -> i.getSeat().getId()).toList();
                var held=holds(em,CLOCK).hold(owner.user,owner.group,key(),BookingHoldService.Source.SMART,new BookingHoldService.Candidate(show,seats));
                return BookingSmartTests.value(held,"id");
            });
            reservations.add(reservation);
            tx(em -> BookingPaymentTests.service(em,CLOCK,true).pay(owner.user,reservation,key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        }
        register(f); assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isZero();
        BookingPaymentTests.race(() -> { tx(em -> BookingPaymentTests.service(em,CLOCK,true).cancel(ownerA.user,reservations.getFirst(),key())); return dispatcher(CLOCK).dispatch(f.shows.getFirst()); },
                () -> { tx(em -> BookingPaymentTests.service(em,CLOCK,true).cancel(ownerB.user,reservations.getLast(),key())); return dispatcher(CLOCK).dispatch(f.shows.getLast()); });
        assertThat(state(f).items()).extracting(WaitingResponse.Item::status).containsExactlyInAnyOrder(QueueStatus.HOLDING,QueueStatus.PAUSED);
    }

    @Test void pendingPaymentRacingAnotherShowAllocationNeverCreatesSecondReservation() throws Exception {
        var f=fixture(2,2); register(f); dispatcher(CLOCK).dispatch(f.shows.getFirst()); long r=state(f).activeReservationId();
        BookingPaymentTests.race(() -> tx(em -> BookingPaymentTests.service(em,CLOCK,true).pay(f.user,r,key(),new MockPaymentRequest(PaymentMethod.MOCK,false))),
                () -> dispatcher(CLOCK).dispatch(f.shows.getLast()));
        statuses(f,QueueStatus.COMPLETED,QueueStatus.CANCELLED);
    }

    @Test void snapshotOlderThanRegistrationRollsBackRatherThanLockingShowsOutOfOrder() {
        var f=fixture(2,2);
        assertThatThrownBy(() -> tx(em -> {
            em.createQuery("select count(q) from WaitingQueue q",Long.class).getSingleResult();
            register(f);
            var ids=inventory(em,f.shows.getFirst()).stream().map(i -> i.getSeat().getId()).toList();
            return holds(em,CLOCK).hold(f.user,f.group,key(),BookingHoldService.Source.SMART,new BookingHoldService.Candidate(f.shows.getFirst(),ids));
        })).isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
        assertThat(state(f).activeReservationId()).isNull();
        assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isEqualTo(1);
    }

    @Test void theaterScopeAndChangedAudienceAreRecheckedWithoutPartialRegistration() {
        var f=fixture(2,2);
        tx(em -> { em.find(BookingRequestGroup.class,f.group).getTheaterPreferences().clear(); return null; });
        assertThat(register(f,f.shows,key()).status()).isEqualTo(400); assertThat(state(f).items()).isEmpty();
        var second=fixture(2,2); register(second);
        tx(em -> { em.find(BookingRequestGroup.class,second.group).getMovie().setRating("19"); return null; });
        assertThat(dispatcher(CLOCK).dispatch(second.shows.getFirst())).isZero(); assertThat(state(second).activeReservationId()).isNull();
    }

    @Test void waitingRejectsSingletonSplitAndTerminatedOpportunityCannotBeReacquiredViaSmartPrimitive() {
        var f=fixture(4,4); register(f);
        tx(em -> { var rows=inventory(em,f.shows.getFirst()); rows.get(3).getSeat().setAdjacencySegment("other"); return null; });
        assertThat(dispatcher(CLOCK).dispatch(f.shows.getFirst())).isZero();
        dispatcher(CLOCK).dispatch(f.shows.getLast());
        var later=Clock.offset(CLOCK,Duration.ofMinutes(5)); tx(em -> holds(em,later).expire(f.group));
        var result=tx(em -> holds(em,later).hold(f.user,f.group,key(),BookingHoldService.Source.SMART,
                new BookingHoldService.Candidate(f.shows.getLast(),inventory(em,f.shows.getLast()).stream().map(i -> i.getSeat().getId()).toList())));
        assertThat(result.status()).isEqualTo(409);
    }
}
