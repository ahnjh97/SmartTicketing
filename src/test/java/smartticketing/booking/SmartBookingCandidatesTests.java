package smartticketing.booking;

import jakarta.persistence.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.*;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static smartticketing.booking.BookingWaitingTests.*;

class SmartBookingCandidatesTests {
    private static final List<String> statements=Collections.synchronizedList(new ArrayList<>());
    static long extraShow(Fixture f, int minutes) {
        return tx(em->{
            var original=em.find(Showtime.class,f.shows().getFirst());
            var show=new Showtime(); show.setMovie(original.getMovie()); show.setScreen(original.getScreen());
            show.setStartTime(original.getStartTime().plusMinutes(minutes)); show.setEndTime(original.getEndTime().plusMinutes(minutes));
            show.setPricePerPerson(10000); show.setTotalSeats(18); show.setAvailableSeats(18);
            show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show);
            for(var row:inventory(em,original.getId())) {
                var seat=new ShowtimeSeat(); seat.setShowtime(show); seat.setSeat(row.getSeat()); em.persist(seat);
            }
            return show.getId();
        });
    }

    static List<Long> fillWaits(Fixture f,int count) {
        var shows=new ArrayList<Long>();
        for(int i=0;i<count;i++) {
            long show=extraShow(f,i+1), group=manualGroup(f,show);
            assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
            shows.add(show);
        }
        return shows;
    }

    @Test void threeWaitsAllowManualAndSmartHoldsPaymentAndFurtherWaiting() {
        var f=zoned(); var waitingShows=fillWaits(f,3);
        long normal=manualGroup(f,f.shows().getFirst());
        var hold=tx(em->holds(em,CLOCK).manual(f.user(),normal,key(),new ManualHoldRequest(inventory(em,f.shows().getFirst()).stream().limit(2).map(i->i.getSeat().getId()).toList())));
        assertThat(hold.status()).isEqualTo(201);
        long reservation=BookingSmartTests.value(hold,"id");
        var smart=direct(f,f.shows().getLast(),2); assertThat(smart.status()).isEqualTo(201);
        assertThat(plan(f,createdId(smart)).candidates().getFirst().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),reservation,key(),new MockPaymentRequest(PaymentMethod.MOCK,false))).status()).isEqualTo(201);
        long fourth=extraShow(f,4), group=manualGroup(f,fourth);
        var added=tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(fourth),SeatPosition.MIDDLE_MIDDLE)));
        assertThat(added.status()).isEqualTo(201);
        assertThat(dispatcher(CLOCK).dispatch(waitingShows.getFirst())).isEqualTo(1);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(fourth),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
    }

    @Test void smartRetainsPreferredWaitAndBalancedHoldRegardlessOfExistingWaitCount() {
        for(int used:List.of(2,3)) {
            var f=zoned(); fillWaits(f,used); var r=request(f);
            preferredStatus(f,f.shows().getFirst(),SeatStatus.RESERVED);
            tx(em->{ inventory(em,f.shows().getLast()).stream().filter(i->i.getSeat().getSeatPosition()==SeatPosition.MIDDLE_MIDDLE)
                    .forEach(i->i.setStatus(SeatStatus.BLOCKED)); return null; });
            var request=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),java.time.LocalTime.of(11,0),java.time.LocalTime.of(14,0),null,r.audience());
            var result=tx(em->plans(em).create(f.user(),key(),request)); assertThat(result.status()).isEqualTo(201);
            var candidates=plan(f,createdId(result)).candidates(); assertThat(candidates).hasSize(2);
            var held=candidates.stream().filter(c->c.kind().equals("BALANCED")).findFirst().orElseThrow();
            assertThat(held.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
            // Only PREFERRED adds a wait; the immediate BALANCED hold has no queue number.
            tx(em->{ assertThat(em.createQuery("select count(q) from WaitingQueue q where q.user.id=:user and q.status=:status",Long.class)
                    .setParameter("user",f.user()).setParameter("status",QueueStatus.WAITING).getSingleResult()).isEqualTo(used + 1); return null; });
        }
    }

    @Test void concurrentWaitingRequestsForDifferentShowsCanExceedThree() throws Exception {
        var f=zoned(); fillWaits(f,2);
        long a=extraShow(f,3), b=extraShow(f,4), ga=manualGroup(f,a), gb=manualGroup(f,b);
        var results=BookingPaymentTests.race(
                ()->tx(em->service(em,CLOCK).register(f.user(),ga,key(),new WaitingRequest(List.of(a),SeatPosition.MIDDLE_MIDDLE))),
                ()->tx(em->service(em,CLOCK).register(f.user(),gb,key(),new WaitingRequest(List.of(b),SeatPosition.MIDDLE_MIDDLE))));
        assertThat(results.stream().map(value->((BookingResult)value).status())).containsExactly(201,201);
        tx(em->{ assertThat(em.createQuery("select count(q) from WaitingQueue q where q.user.id=:user and q.status=:status",Long.class)
                .setParameter("user",f.user()).setParameter("status",QueueStatus.WAITING).getSingleResult()).isEqualTo(4); return null; });
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void priorityChainStopsAtFirstAvailableCandidateOrWaitsForAllThree() {
        // 0: preferred free; 1: balanced free; 2: fast free; 3: all sold out.
        for(int firstAvailable=0;firstAvailable<=3;firstAvailable++) {
            var f=zoned(); var r=request(f); final int availableFrom=firstAvailable;
            var shows=tx(em->{
                var original=em.find(Showtime.class,f.shows().getLast());
                var third=new Showtime(); third.setMovie(original.getMovie()); third.setScreen(original.getScreen());
                third.setStartTime(original.getStartTime().plusHours(1)); third.setEndTime(original.getEndTime().plusHours(1));
                third.setPricePerPerson(10000); third.setTotalSeats(18); third.setAvailableSeats(18);
                third.setCreatedAt(NOW); third.setUpdatedAt(NOW); em.persist(third);
                for(var row:inventory(em,original.getId())) {
                    var seat=new ShowtimeSeat(); seat.setShowtime(third); seat.setSeat(row.getSeat()); em.persist(seat);
                }
                var ids=List.of(f.shows().getFirst(),f.shows().getLast(),third.getId());
                var zones=List.of(SeatPosition.MIDDLE_MIDDLE,SeatPosition.MIDDLE_REAR,SeatPosition.SIDE_MIDDLE);
                for(int i=0;i<3;i++) {
                    final int index=i;
                    inventory(em,ids.get(i)).forEach(seat->seat.setStatus(seat.getSeat().getSeatPosition()!=zones.get(index)
                            ?SeatStatus.BLOCKED:index>=availableFrom?SeatStatus.AVAILABLE:SeatStatus.RESERVED));
                }
                return ids;
            });
            var request=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),
                    java.time.LocalTime.of(11,0),java.time.LocalTime.of(15,0),null,r.audience());
            var key=key(); var result=tx(em->plans(em).create(f.user(),key,request));
            assertThat(result.status()).as(result.body()).isEqualTo(201);
            var replay=tx(em->plans(em).create(f.user(),key,request)); assertThat(replay).isEqualTo(result);
            var candidates=plan(f,createdId(result)).candidates();
            int expected=Math.min(firstAvailable+1,3);
            assertThat(candidates).hasSize(expected);
            assertThat(candidates).extracting(SmartBookingCandidatesService.Candidate::kind)
                    .containsExactlyElementsOf(List.of("PREFERRED","BALANCED","FAST").subList(0,expected));
            for(int i=0;i<expected;i++) {
                var candidate=candidates.get(i);
                assertThat(candidate.showtimeId()).isEqualTo(shows.get(i));
                if(i==firstAvailable) {
                    // 즉시 선점 후보는 대기번호를 발급하지 않는다.
                    assertThat(candidate.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
                    assertThat(candidate.waiting().items()).isEmpty();
                } else {
                    assertThat(candidate.payment()).isNull();
                    assertThat(candidate.waiting().items()).hasSize(1);
                    assertThat(candidate.waiting().items().getFirst().queueNumber()).isEqualTo(1);
                    assertThat(candidate.waiting().items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
                }
            }
            db.restartPersistence();
            for(var show:shows) assertThat(dispatcher(CLOCK).dispatch(show)).isZero();
            tx(em->{
                assertThat(em.createQuery("select count(q) from WaitingQueue q where q.user.id=:user",Long.class)
                        .setParameter("user",f.user()).getSingleResult()).isEqualTo(availableFrom == 3 ? expected : expected - 1);
                assertThat(em.createQuery("select count(r) from Reservation r where r.user.id=:user",Long.class)
                        .setParameter("user",f.user()).getSingleResult()).isEqualTo(availableFrom==3?0:1);
                for(int i=expected;i<3;i++) assertThat(inventory(em,shows.get(i))).allMatch(seat->seat.getReservation()==null);
                return null;
            });
        }
    }

    @Test void preferredWaitKeepsOnlyBetterAvailableAlternativeWithoutCreatingDiscardedQueue() {
        for(boolean samePreference:List.of(false,true)) {
            var f=zoned(); var r=request(f);
            var third=tx(em->{
                var original=em.find(Showtime.class,f.shows().getLast());
                var show=new Showtime(); show.setMovie(original.getMovie()); show.setScreen(original.getScreen());
                show.setStartTime(original.getStartTime().plusHours(1)); show.setEndTime(original.getEndTime().plusHours(1));
                show.setPricePerPerson(10000); show.setTotalSeats(18); show.setAvailableSeats(18);
                show.setCreatedAt(NOW); show.setUpdatedAt(NOW); em.persist(show);
                for(var row:inventory(em,original.getId())) {
                    var seat=new ShowtimeSeat(); seat.setShowtime(show); seat.setSeat(row.getSeat());
                    if(row.getSeat().getSeatPosition()==SeatPosition.MIDDLE_MIDDLE) seat.setStatus(SeatStatus.BLOCKED);
                    em.persist(seat);
                }
                inventory(em,f.shows().getFirst()).forEach(i->i.setStatus(SeatStatus.RESERVED));
                inventory(em,f.shows().getLast()).forEach(i->{
                    var zone=i.getSeat().getSeatPosition();
                    if(zone==SeatPosition.MIDDLE_MIDDLE || (!samePreference && zone==SeatPosition.MIDDLE_REAR))
                        i.setStatus(SeatStatus.BLOCKED);
                });
                return show.getId();
            });
            var request=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),
                    java.time.LocalTime.of(11,0),java.time.LocalTime.of(15,0),null,r.audience());
            var key=key(); var result=tx(em->plans(em).create(f.user(),key,request));
            assertThat(result.status()).isEqualTo(201);
            var replay=tx(em->plans(em).create(f.user(),key,request)); assertThat(replay).isEqualTo(result);
            long id=createdId(result);
            var candidates=plan(f,id).candidates(); assertThat(candidates).hasSize(2);
            var preferred=candidates.stream().filter(c->c.kind().equals("PREFERRED")).findFirst().orElseThrow();
            assertThat(preferred.payment()).isNull();
            assertThat(preferred.waiting().items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
            assertThat(preferred.waiting().items().getFirst().queueNumber()).isEqualTo(1);
            var held=candidates.stream().filter(c->c.payment()!=null).findFirst().orElseThrow();
            assertThat(held.kind()).isEqualTo("BALANCED");
            assertThat(held.zone()).isEqualTo(SeatPosition.MIDDLE_REAR);
            assertThat(held.showtimeId()).isEqualTo(samePreference?f.shows().getLast():third);
            assertThat(held.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
            long discarded=samePreference?third:f.shows().getLast();
            db.restartPersistence();
            assertThat(dispatcher(CLOCK).dispatch(discarded)).isZero();
            tx(em->{
                assertThat(em.createQuery("select count(q) from WaitingQueue q where q.user.id=:user and q.showtime.id=:show",Long.class)
                        .setParameter("user",f.user()).setParameter("show",discarded).getSingleResult()).isZero();
                assertThat(em.createQuery("select count(r) from Reservation r where r.user.id=:user",Long.class)
                        .setParameter("user",f.user()).getSingleResult()).isEqualTo(1);
                assertThat(inventory(em,discarded)).allMatch(i->i.getReservation()==null);
                return null;
            });
            preferredStatus(f,f.shows().getFirst(),SeatStatus.AVAILABLE);
            assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        }
    }

    @Test void availableBestFeasibleZoneCreatesOnlyOneHoldAcrossMultipleShows() {
        var f=zoned(); var r=request(f);
        // The first preferred zone exists but cannot fit two people in any show.
        tx(em->{
            for(var show:f.shows()) inventory(em,show).stream()
                    .filter(i->i.getSeat().getSeatPosition()==SeatPosition.MIDDLE_MIDDLE)
                    .skip(1).forEach(i->i.setStatus(SeatStatus.BLOCKED));
            return null;
        });
        var request=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),
                java.time.LocalTime.of(11,0),java.time.LocalTime.of(14,0),null,r.audience());
        var key=key();
        var result=tx(em->plans(em).create(f.user(),key,request));
        assertThat(result.status()).isEqualTo(201);
        var replay=tx(em->plans(em).create(f.user(),key,request));
        assertThat(replay).isEqualTo(result);
        var candidates=plan(f,createdId(result)).candidates();
        assertThat(candidates).hasSize(1);
        var candidate=candidates.getFirst();
        assertThat(candidate.zone()).isEqualTo(SeatPosition.MIDDLE_REAR);
        assertThat(candidate.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        for(var show:f.shows()) assertThat(dispatcher(CLOCK).dispatch(show)).isZero();
        tx(em->{
            assertThat(em.createQuery("select count(q) from WaitingQueue q where q.user.id=:user",Long.class)
                    .setParameter("user",f.user()).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(r) from Reservation r where r.user.id=:user",Long.class)
                    .setParameter("user",f.user()).getSingleResult()).isEqualTo(1);
            assertThat(inventory(em,f.shows().getLast())).allMatch(i->i.getReservation()==null);
            return null;
        });
    }

    @Test void manualAndSmartShareZoneOrderWhileOtherZoneStartsAtOne() {
        var f=zoned(); var show=f.shows().getFirst();
        var first=another(f,2,false); long manual=manualGroup(first,show);
        assertThat(tx(em->service(em,CLOCK).register(first.user(),manual,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
        long smart=create(f);
        var side=another(f,2,false); long sideGroup=manualGroup(side,show);
        assertThat(tx(em->service(em,CLOCK).register(side.user(),sideGroup,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))).status()).isEqualTo(201);
        assertThat(plan(f,smart).candidates().getFirst().waiting().items().getFirst().queueNumber()).isEqualTo(2);
        var sideState=tx(em->service(em,CLOCK).get(side.user(),sideGroup));
        assertThat(sideState.items().getFirst().queueNumber()).isEqualTo(1);
        assertThat(sideState.items().getFirst().aheadCount()).isZero();
        tx(em->{ inventory(em,show).stream().filter(i->i.getSeat().getSeatPosition()==SeatPosition.MIDDLE_MIDDLE).skip(2).forEach(i->i.setStatus(SeatStatus.RESERVED)); return null; });
        assertThat(dispatcher(CLOCK).dispatch(show)).isEqualTo(2);
        assertThat(tx(em->service(em,CLOCK).get(first.user(),manual)).activeReservationId()).isNotNull();
        assertThat(plan(f,smart).candidates().getFirst().payment()).isNull();
        assertThat(tx(em->service(em,CLOCK).get(side.user(),sideGroup)).activeReservationId()).isNotNull();
    }

    @Test void smartZoneChangeJoinsDestinationTailAndAllocatesOnlyNewZone() {
        var f=zoned(); long smart=createWaitingCandidate(f); var show=f.shows().getFirst();
        var other=another(f,2,false); long manual=manualGroup(other,show);
        assertThat(tx(em->service(em,CLOCK).register(other.user(),manual,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))).status()).isEqualTo(201);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),smart,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))).status()).isEqualTo(201);
        var changed=tx(em->service(em,CLOCK).get(f.user(),smart));
        assertThat(changed.items()).hasSize(1);
        assertThat(changed.items().getFirst().queueNumber()).isEqualTo(2);
        assertThat(changed.items().getFirst().aheadCount()).isEqualTo(1);
        assertThat(dispatcher(CLOCK).dispatch(show)).isEqualTo(2);
        tx(em->{ assertThat(inventory(em,show).stream().filter(i->i.getReservation()!=null && i.getReservation().getRequestGroup().getId().equals(smart)))
                .allMatch(i->i.getSeat().getSeatPosition()==SeatPosition.SIDE_MIDDLE); return null; });
    }

    @Test void differentShowsStillCreateIndependentCandidatesAndPayments() throws Exception {
        var f=zoned(); var r=request(f);
        for(var show:f.shows()) {
            preferredStatus(f,show,SeatStatus.RESERVED);
            tx(em->{ inventory(em,show).stream().filter(i->i.getSeat().getSeatPosition()!=SeatPosition.MIDDLE_MIDDLE)
                    .forEach(i->i.setStatus(SeatStatus.BLOCKED)); return null; });
        }
        var request=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),java.time.LocalTime.of(11,0),java.time.LocalTime.of(14,0),null,r.audience());
        var result=tx(em->plans(em).create(f.user(),key(),request));
        assertThat(result.status()).isEqualTo(201);
        long id=createdId(result);
        assertThat(plan(f,id).candidates()).hasSize(2).extracting(SmartBookingCandidatesService.Candidate::showtimeId).doesNotHaveDuplicates();
        for(var show:f.shows()) { preferredStatus(f,show,SeatStatus.AVAILABLE); assertThat(dispatcher(CLOCK).dispatch(show)).isEqualTo(1); }
        var candidates=plan(f,id).candidates();
        var results=BookingPaymentTests.race(
                ()->tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidates.get(0).payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))),
                ()->tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidates.get(1).payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))));
        assertThat(results).allSatisfy(value->assertThat(((BookingResult)value).status()).isEqualTo(201));
    }

    @BeforeAll static void start() throws Exception { db=new TemporaryMysqlDatabase(statements::add); }
    @AfterAll static void stop() throws Exception { if(db!=null) db.close(); }

    static SmartBookingCandidatesService plans(EntityManager em) {
        var hold=holds(em,CLOCK); var ops=new BookingIdempotency(em);
        var groups=new BookingGroupService(em,hold,ops,Validation.buildDefaultValidatorFactory().getValidator());
        return new SmartBookingCandidatesService(em,groups,hold,service(em,CLOCK),BookingPaymentTests.service(em,CLOCK,true),ops);
    }
    static SmartBookingCandidatesService retryPlans() {
        return retryPlans(org.mockito.Mockito.mock(SmartBookingSummaryCache.class,org.mockito.Mockito.RETURNS_SMART_NULLS));
    }
    static SmartBookingCandidatesService retryPlans(SmartBookingSummaryCache cache) {
        var em=SharedEntityManagerCreator.createSharedEntityManager(db.factory());
        var service=plans(em);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"configure",db.transactions(),
                cache);
        return service;
    }
    @Test void staleCacheRollsBackAndRetriesWithFreshInventoryBeforeIssuingAnyCandidate() {
        var f=zoned();var cache=org.mockito.Mockito.mock(SmartBookingSummaryCache.class);
        var zones=Arrays.stream(SeatPosition.values()).map(z->new SmartBookingSummaryCache.Zone(z,true,1,List.<Long>of())).toList();
        var stale=new HashMap<Long,SmartBookingSummaryCache.Snapshot>();
        f.shows().forEach(id->stale.put(id,new SmartBookingSummaryCache.Snapshot(System.currentTimeMillis(),zones)));
        org.mockito.Mockito.when(cache.read(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(stale);
        var result=retryPlans(cache).create(f.user(),key(),request(f));
        assertThat(result.status()).isEqualTo(201);
        long groupCount=tx(em->em.createQuery("select count(g) from BookingRequestGroup g where g.user.id=:u and g.candidateKind is not null",Long.class)
                .setParameter("u",f.user()).getSingleResult());
        assertThat(groupCount).isEqualTo(1);
        assertThat(tx(em->plans(em).get(f.user(),null)).candidates()).hasSize(1);
        org.mockito.Mockito.verify(cache,org.mockito.Mockito.times(1)).read(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void creationIncludesInitialViewAndStatusReadsNeverLockOrWrite() {
        var f=zoned();var result=direct(f,f.shows().getFirst(),2);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var created=json.readValue(result.body(),SmartBookingCandidatesService.Created.class);
        assertThat(created.initial().candidates()).hasSize(1);
        assertThat(created.initial().batches()).contains(created.groupIds());
        statements.clear();
        var view=tx(em->plans(em).get(f.user(),null));
        assertThat(view.candidates()).hasSize(1);
        assertThat(statements).noneMatch(sql->sql.toLowerCase().contains("for update")
                || sql.toLowerCase().startsWith("update ") || sql.toLowerCase().startsWith("insert "));
    }
    static Fixture zoned() {
        var f=fixture(2,18);
        tx(em->{
            for(var show:f.shows()) {
                var rows=inventory(em,show);
                for(int i=0;i<rows.size();i++) {
                    var seat=rows.get(i).getSeat();
                    seat.setSeatPosition(List.of(SeatPosition.MIDDLE_MIDDLE,SeatPosition.MIDDLE_REAR,SeatPosition.SIDE_MIDDLE).get(i/6));
                    seat.setAdjacencySegment("zone"+(i/6)); seat.setPositionInSegment(i%6+1);
                }
            }
            return null;
        });
        return f;
    }
    static CreateBookingGroupRequest request(Fixture f) {
        return tx(em->{ var group=em.find(BookingRequestGroup.class,f.group());
            if(em.createQuery("select count(p) from UserPreferredTheater p where p.user.id=:u",Long.class).setParameter("u",f.user()).getSingleResult()==0) {
                var preference=new UserPreferredTheater(); preference.setUser(group.getUser()); preference.setTheater(group.getTheaterPreferences().getFirst()); preference.setPriority(1); em.persist(preference);
            }
            return new CreateBookingGroupRequest(BookingEntryPoint.MOVIE_SMART,group.getMovie().getId(),group.getViewingDate(),2,
                    java.time.LocalTime.of(11,0),java.time.LocalTime.of(12,30),null,new AudienceRequest(2,0,null,null)); });
    }
    static long create(Fixture f) {
        var request=request(f); var result=tx(em->plans(em).create(f.user(),key(),request));
        assertThat(result.status()).as(result.body()).isEqualTo(201); return createdId(result);
    }
    static void preferredStatus(Fixture f, long show, SeatStatus status) {
        tx(em -> { inventory(em, show).stream().filter(i -> i.getSeat().getSeatPosition() == SeatPosition.MIDDLE_MIDDLE)
                .forEach(i -> i.setStatus(status)); return null; });
    }
    // The best zone is unavailable at registration, then seats are released for later allocation.
    static long createWaitingCandidate(Fixture f) {
        preferredStatus(f, f.shows().getFirst(), SeatStatus.RESERVED);
        long id = create(f);
        preferredStatus(f, f.shows().getFirst(), SeatStatus.AVAILABLE);
        return id;
    }
    static long createdId(BookingResult result) { return tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.body()).get("groupIds").get(0).asLong(); }
    // Tests inspect each independent candidate, including terminal ones, through its own ID.
    static SmartBookingCandidatesService.Candidates plan(Fixture f,long id) {
        var ids=tx(em->em.createQuery("select g.id from BookingRequestGroup g where g.user.id=:u and g.candidateKind is not null order by g.id",Long.class).setParameter("u",f.user()).getResultList());
        return new SmartBookingCandidatesService.Candidates(ids.stream().map(candidate->tx(em->plans(em).get(f.user(),candidate)).candidates().stream()
                .filter(c->c.groupId().equals(candidate)).findFirst().orElseThrow()).toList());
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void sameShowKeepsPreferredWaitAndImmediatelyHoldsAnotherZone() throws Exception {
        var f=zoned(); long id=createWaitingCandidate(f);
        var initial=plan(f,id).candidates();
        assertThat(initial).hasSize(2);
        assertThat(initial.getLast().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(initial.getFirst().zone()).isEqualTo(SeatPosition.MIDDLE_MIDDLE);
        assertThat(initial.getFirst().waiting().items().getFirst().queueNumber()).isEqualTo(1);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        var held=plan(f,id).candidates().getFirst();
        assertThat(held.payment().reservation().seatIds()).hasSize(2);
        assertThat(plan(f,id).candidates().getLast().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isZero();
        tx(em -> {
            var alerts = BookingPaymentTests.notifications(em).list(f.user(), false);
            assertThat(alerts).hasSize(1);
            assertThat(alerts.getFirst().type()).isEqualTo(NotificationType.QUEUE_TURN);
            assertThat(alerts.getFirst().groupId()).isEqualTo(held.groupId());
            return null;
        });
        assertThat(tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),held.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))).status()).isEqualTo(201);
        assertThat(tx(em->BookingPaymentTests.service(em,CLOCK,true).cancel(f.user(),held.payment().reservation().id(),key())).status()).isEqualTo(200);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void availableTopPreferenceCreatesOnlyOneImmediateHoldAndLeavesOtherZonesFree() {
        var f = zoned(); var request = request(f); var key = key();
        var result = tx(em -> plans(em).create(f.user(), key, request));
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        var candidates = plan(f, createdId(result)).candidates();
        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();
        assertThat(candidate.kind()).isEqualTo("PREFERRED");
        assertThat(candidate.zone()).isEqualTo(SeatPosition.MIDDLE_MIDDLE);
        assertThat(candidate.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(candidate.waiting().items()).isEmpty();
        tx(em -> {
            assertThat(BookingPaymentTests.notifications(em).list(f.user(), false))
                    .noneMatch(n -> n.type() == NotificationType.QUEUE_TURN);
            return null;
        });
        var replay = tx(em -> plans(em).create(f.user(), key, request));
        assertThat(replay).isEqualTo(result);
        tx(em -> {
            assertThat(inventory(em, f.shows().getFirst()).stream().filter(i -> i.getSeat().getSeatPosition() != SeatPosition.MIDDLE_MIDDLE))
                    .allMatch(i -> i.getStatus() == SeatStatus.AVAILABLE && i.getReservation() == null);
            return null;
        });
    }

    @Test void aSinglePreferredHoldFitsWhenOnlyOneCapacitySlotRemains() {
        var f = zoned(); var show = f.shows().getLast();
        for (int i = 0; i < 2; i++) {
            long group = manualGroup(f, show); final int offset = i * 2;
            assertThat(tx(em -> holds(em, CLOCK).manual(f.user(), group, key(), new ManualHoldRequest(inventory(em, show).stream()
                    .skip(offset).limit(2).map(row -> row.getSeat().getId()).toList()))).status()).isEqualTo(201);
        }
        var candidates = plan(f, create(f)).candidates();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        var activity = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(f.user()));
        assertThat(activity).hasSize(3);
    }

    @Test void immediateHoldUsesMembersActualFirstPreference() {
        var f = zoned();
        tx(em -> {
            var preference = new UserPreferredSeat(); preference.setUser(em.find(Users.class, f.user()));
            preference.setSeatPosition(SeatPosition.SIDE_MIDDLE); preference.setPriority(1); em.persist(preference);
            return null;
        });
        var result = direct(f, f.shows().getFirst(), 2);
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        var candidates = plan(f, createdId(result)).candidates();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().zone()).isEqualTo(SeatPosition.SIDE_MIDDLE);
        assertThat(candidates.getFirst().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void concurrentUsersCannotBothImmediatelyHoldTheLastPreferredPair() throws Exception {
        var first = zoned(); var second = another(first, 2, false);
        var firstRequest = request(first); var secondRequest = request(second);
        tx(em -> {
            inventory(em, first.shows().getFirst()).stream().filter(i -> i.getSeat().getSeatPosition() == SeatPosition.MIDDLE_MIDDLE)
                    .skip(2).forEach(i -> i.setStatus(SeatStatus.RESERVED));
            return null;
        });
        var results = BookingPaymentTests.race(
                () -> retryPlans().create(first.user(), key(), firstRequest),
                () -> retryPlans().create(second.user(), key(), secondRequest));
        assertThat(results).allSatisfy(result -> assertThat(((BookingResult) result).status()).isEqualTo(201));
        var a = tx(em -> plans(em).get(first.user(), null)).candidates();
        var b = tx(em -> plans(em).get(second.user(), null)).candidates();
        assertThat(List.of(a.size(), b.size())).containsExactlyInAnyOrder(1, 2);
        var all = new ArrayList<>(a); all.addAll(b);
        assertThat(all.stream().filter(c -> c.payment() != null)).hasSize(2);
        assertThat(all.stream().filter(c -> c.zone() == SeatPosition.MIDDLE_MIDDLE && c.payment() == null)).hasSize(1);
    }

    @Test void candidateCreationDoesNotLockUnselectedZoneOrItsInventory() throws Exception {
        var f = zoned();
        long rowId = tx(em -> {
            var counter = new WaitingZoneSequence(); counter.setId(f.shows().getFirst() + "_SIDE_MIDDLE"); em.persist(counter);
            return inventory(em, f.shows().getFirst()).stream()
                    .filter(i -> i.getSeat().getSeatPosition() == SeatPosition.SIDE_MIDDLE).findFirst().orElseThrow().getId();
        });
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker = db.open()) {
            blocker.getTransaction().begin();
            blocker.find(WaitingZoneSequence.class, f.shows().getFirst() + "_SIDE_MIDDLE", LockModeType.PESSIMISTIC_WRITE);
            blocker.find(ShowtimeSeat.class, rowId, LockModeType.PESSIMISTIC_WRITE);
            try {
                var result = pool.submit(() -> retryPlans().create(f.user(), key(), request(f)))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.status()).as(result.body()).isEqualTo(201);
                var candidates = plan(f, createdId(result)).candidates();
                assertThat(candidates).hasSize(1);
                assertThat(candidates.getFirst().zone()).isEqualTo(SeatPosition.MIDDLE_MIDDLE);
                assertThat(candidates.getFirst().payment()).isNotNull();
            } finally { blocker.getTransaction().rollback(); }
        } finally { pool.shutdownNow(); pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); }
    }

    @Test void waitingInOneZoneDoesNotAllocateFreeSeatsInOtherZones() throws Exception {
        var f=zoned(); preferredStatus(f,f.shows().getFirst(),SeatStatus.RESERVED);
        long id=create(f);
        assertThat(plan(f,id).candidates()).hasSize(2);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isZero();
        assertThat(plan(f,id).candidates().getFirst().payment()).isNull();
        preferredStatus(f,f.shows().getFirst(),SeatStatus.AVAILABLE);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        assertThat(plan(f,id).candidates().getFirst().payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test void cancellingEarlierZoneRequestUpdatesFollowingPosition() throws Exception {
        var first=zoned(); long a=createWaitingCandidate(first);
        var second=another(first,2,false); long b=create(second);
        var next=plan(second,b).candidates().getFirst().waiting().items().getFirst();
        assertThat(next.queueNumber()).isEqualTo(2); assertThat(next.aheadCount()).isEqualTo(1);
        tx(em->service(em,CLOCK).cancel(first.user(),a,key()));
        assertThat(plan(first,a).candidates().getFirst().status()).isEqualTo(BookingGroupStatus.CANCELLED);
        assertThat(plan(second,b).candidates().getFirst().waiting().items().getFirst().aheadCount()).isZero();
    }

    @Test void replayOwnershipRecoveryAndRestartPreserveTheBundle() {
        var f=zoned(); var request=request(f); var key=key();
        var created=tx(em->plans(em).create(f.user(),key,request));
        var replay=tx(em->plans(em).create(f.user(),key,request));
        assertThat(replay).isEqualTo(created);
        long id=createdId(created);
        var initial=plan(f,id);
        db.restartPersistence();
        assertThat(plan(f,id).candidates()).extracting(SmartBookingCandidatesService.Candidate::groupId)
                .containsExactlyElementsOf(initial.candidates().stream().map(SmartBookingCandidatesService.Candidate::groupId).toList());
        tx(em->{
            var recovery=new BookingRecoveryService(em,holds(em,CLOCK));
            assertThat(recovery.one(f.user(),initial.candidates().getFirst().groupId()).path()).contains("smart=1","candidate="+id).doesNotContain("plan=");
            return null;
        });
        var other=another(f,2,false);
        assertThatThrownBy(()->tx(em->plans(em).get(other.user(),id))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void allocatedSeatsStayWithinCandidateZoneAndNewRequestsRemainAllowed() {
        var f=zoned(); long id=createWaitingCandidate(f); var candidate=plan(f,id).candidates().getFirst();
        var result=tx(em->{
            var wrong=inventory(em,f.shows().getFirst()).stream().filter(i->i.getSeat().getSeatPosition()!=candidate.zone()).limit(2).map(i->i.getSeat().getId()).toList();
            return holds(em,CLOCK).hold(f.user(),candidate.groupId(),key(),BookingHoldService.Source.WAITING,
                    new BookingHoldService.Candidate(f.shows().getFirst(),wrong));
        });
        assertThat(result.status()).isEqualTo(409);
        var request=request(f);
        assertThat(tx(em->plans(em).create(f.user(),key(),request)).status()).isEqualTo(201);
    }

    @Test void aBusyCenterDoesNotIncreaseSideNumbersAndEarlierZoneRequestGetsFirstSeats() {
        var f=zoned(); var earlier=another(f,6,false);
        tx(em->{ em.createNativeQuery("update booking_request_groups set candidate_zone='MIDDLE_MIDDLE' where id=:id")
                .setParameter("id",earlier.group()).executeUpdate(); return null; });
        assertThat(register(earlier,List.of(f.shows().getFirst()),key()).status()).isEqualTo(201);
        long id=create(f);
        assertThat(plan(f,id).candidates()).allSatisfy(c->{
            if (c.zone() == SeatPosition.MIDDLE_MIDDLE) {
                assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(2);
                assertThat(c.waiting().items().getFirst().aheadCount()).isEqualTo(1);
            } else {
                assertThat(c.waiting().items()).isEmpty();
                assertThat(c.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
            }
        });
        dispatcher(CLOCK).dispatch(f.shows().getFirst());
        assertThat(state(earlier).activeReservationId()).isNotNull();
        assertThat(plan(f,id).candidates()).allSatisfy(c->{
            if(c.zone()==SeatPosition.MIDDLE_MIDDLE) assertThat(c.waiting().items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
            else assertThat(c.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING);
        });
    }

    @Test void singleUsableZoneProducesOneCandidateAndImpossibleCapacityProducesNone() {
        var f=zoned();
        tx(em->{ inventory(em,f.shows().getFirst()).stream().filter(i->i.getSeat().getSeatPosition()!=SeatPosition.MIDDLE_MIDDLE)
                .forEach(i->i.setStatus(SeatStatus.BLOCKED)); return null; });
        assertThat(plan(f,create(f)).candidates()).hasSize(1);
        var other=another(f,6,false);
        tx(em->{ inventory(em,f.shows().getFirst()).forEach(i->i.setStatus(SeatStatus.BLOCKED)); return null; });
        var request=request(other);
        assertThat(tx(em->plans(em).create(other.user(),key(),request)).status()).isEqualTo(409);
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void moviePlanUsesSavedTheatersAndRequestedTimeWindow() {
        var f=zoned();
        var request=tx(em->{
            var g=em.find(BookingRequestGroup.class,f.group());
            var preference=new UserPreferredTheater(); preference.setUser(g.getUser());
            preference.setTheater(g.getTheaterPreferences().getFirst()); preference.setPriority(1); em.persist(preference);
            return new CreateBookingGroupRequest(BookingEntryPoint.MOVIE_SMART,g.getMovie().getId(),g.getViewingDate(),2,
                    java.time.LocalTime.of(11,0),java.time.LocalTime.of(12,30),null,new AudienceRequest(2,0,null,null));
        });
        var created=tx(em->plans(em).create(f.user(),key(),request));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(plan(f,createdId(created)).candidates()).hasSize(1)
                .allSatisfy(c->assertThat(c.showtimeId()).isEqualTo(f.shows().getFirst()));
    }

    @Test void oldQueueNumbersSurviveAdditiveSchemaUpgrade() {
        var f=zoned(); register(f,List.of(f.shows().getFirst()),key());
        tx(em->{ em.createNativeQuery("alter table waiting_queues drop index uk_waiting_queue_zone_number, drop column seat_zone, drop column zone_queue_number").executeUpdate(); return null; });
        db.restartPersistence(); db.restartPersistence();
        assertThat(state(f).items().getFirst().queueNumber()).isEqualTo(1);
        assertThat(state(f).items().getFirst().seatZone()).isNull();
        var next=another(f,2,false);
        assertThat(plan(next,create(next)).candidates()).allSatisfy(c->assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(c.zone()==SeatPosition.MIDDLE_MIDDLE?2:1));
    }

    @Test void concurrentReplayCreatesOnlyOneZoneRequest() throws Exception {
        var f=zoned(); var request=request(f); var key=key();
        preferredStatus(f,f.shows().getFirst(),SeatStatus.RESERVED);
        var results=BookingPaymentTests.race(()->tx(em->plans(em).create(f.user(),key,request)),()->tx(em->plans(em).create(f.user(),key,request)));
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(plan(f,createdId((BookingResult)results.getFirst())).candidates()).hasSize(2);
    }
    static long manualGroup(Fixture f, long show) {
        var r=request(f);
        return tx(em->{ var h=holds(em,CLOCK); var ops=new BookingIdempotency(em);
            var service=new BookingGroupService(em,h,ops,Validation.buildDefaultValidatorFactory().getValidator());
            return BookingSmartTests.value(service.create(f.user(),key(),new CreateBookingGroupRequest(
                    BookingEntryPoint.THEATER_NORMAL,r.movieId(),r.viewingDate(),2,null,null,show,r.audience())),"id"); });
    }

    @Test void manualZoneChangeKeepsOneSlotAndDirectSeatsReplaceWaiting() {
        var f=zoned(); var show=f.shows().getFirst(); long group=manualGroup(f,show);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
        var changed=tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE)));
        assertThat(changed.status()).as(changed.body()).isEqualTo(201);
        var queues=tx(em->service(em,CLOCK).get(f.user(),group));
        assertThat(queues.items()).hasSize(1);
        assertThat(queues.items().getFirst().seatZone()).isEqualTo(SeatPosition.SIDE_MIDDLE);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
        assertThat(tx(em->service(em,CLOCK).get(f.user(),group)).items().getFirst().queueNumber()).isEqualTo(2);
        var held=tx(em->holds(em,CLOCK).manual(f.user(),group,key(),new ManualHoldRequest(inventory(em,show).stream()
                .filter(i->i.getSeat().getSeatPosition()==SeatPosition.MIDDLE_REAR).limit(2).map(i->i.getSeat().getId()).toList())));
        assertThat(held.status()).as(held.body()).isEqualTo(201);
        assertThat(tx(em->service(em,CLOCK).get(f.user(),group)).items().getFirst().status()).isEqualTo(QueueStatus.HOLDING);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(409);
    }

    @Test void sameUserCanWaitInTwoZonesOfSameShowConcurrently() throws Exception {
        var f=zoned(); var show=f.shows().getFirst();
        long a=manualGroup(f,show), b=manualGroup(f,show);
        var results=BookingPaymentTests.race(
                ()->tx(em->service(em,CLOCK).register(f.user(),a,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))),
                ()->tx(em->service(em,CLOCK).register(f.user(),b,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))));
        assertThat(results.stream().map(r->((BookingResult)r).status())).containsExactlyInAnyOrder(201,201);
        assertThat(BookingWaitingTests.<List<BookingActivityService.Item>>tx(em->new BookingActivityService(em,holds(em,CLOCK)).active(f.user()))).hasSize(2);
    }

    @Test void smartCanChooseSameShowDespiteExistingWaiting() {
        var f=zoned(); var show=f.shows().getFirst(); long group=manualGroup(f,show);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))).status()).isEqualTo(201);
        var added=direct(f,show,2);
        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(direct(f,f.shows().getLast(),2).status()).isEqualTo(201);
    }

    @Test void smartCanAddSixthWaitWithoutDroppingExistingWaits() {
        var f=zoned(); fillWaits(f,5);
        preferredStatus(f,f.shows().getFirst(),SeatStatus.RESERVED);
        var r=request(f); var added=tx(em->plans(em).create(f.user(),key(),r));
        assertThat(added.status()).isEqualTo(201);
        assertThat(BookingWaitingTests.<List<BookingActivityService.Item>>tx(em->new BookingActivityService(em,holds(em,CLOCK)).active(f.user()))).hasSize(7);
    }

    @Test void smartRequestsKeepPreferredWaitAndAnotherZoneHold() throws Exception {
        for(boolean theater:List.of(false,true)) for(int used:List.of(1,2)) {
            var f=zoned(); var show=f.shows().getLast();
            for(int i=0;i<used;i++) {
                long group=manualGroup(f,show); final int offset=i*2;
                assertThat(tx(em->holds(em,CLOCK).manual(f.user(),group,key(),new ManualHoldRequest(inventory(em,show).stream().skip(offset).limit(2).map(r->r.getSeat().getId()).toList()))).status()).isEqualTo(201);
            }
            preferredStatus(f,f.shows().getFirst(),SeatStatus.RESERVED);
            var r=request(f); var result=theater?direct(f,f.shows().getFirst(),2):tx(em->plans(em).create(f.user(),key(),r));
            assertThat(result.status()).isEqualTo(201);
            assertThat(plan(f,createdId(result)).candidates()).hasSize(2);
            assertThat(BookingWaitingTests.<List<BookingActivityService.Item>>tx(em->new BookingActivityService(em,holds(em,CLOCK)).active(f.user()))).hasSize(used+2);
        }
    }

    @Test void manualAllocationRespectsChangedZoneAndKeepsNormalBookingType() {
        var f=zoned(); var show=f.shows().getFirst(); long group=manualGroup(f,show);
        tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE)));
        assertThat(dispatcher(CLOCK).dispatch(show)).isEqualTo(1);
        tx(em->{
            var r=em.createQuery("select r from Reservation r where r.requestGroup.id=:g",Reservation.class).setParameter("g",group).getSingleResult();
            assertThat(r.getReservationType()).isEqualTo(ReservationType.NORMAL);
            assertThat(inventory(em,show).stream().filter(i->i.getReservation()!=null).map(i->i.getSeat().getSeatPosition()).toList())
                    .containsOnly(SeatPosition.SIDE_MIDDLE);
            return null;
        });
    }

    @Test void fourDirectHoldsDoNotConsumeAnyWaitingSlots() {
        var f=zoned(); var show=f.shows().getFirst(); var groups=new ArrayList<Long>(); var reservations=new ArrayList<Long>();
        for(int i=0;i<4;i++) groups.add(manualGroup(f,show));
        for(int i=0;i<4;i++) {
            final int index=i;
            var held=tx(em->holds(em,CLOCK).manual(f.user(),groups.get(index),key(),new ManualHoldRequest(inventory(em,show).stream()
                    .skip(index*2).limit(2).map(row->row.getSeat().getId()).toList())));
            assertThat(held.status()).as(held.body()).isEqualTo(201);
            reservations.add(BookingSmartTests.value(held,"id"));
        }
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),reservations.getFirst(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        fillWaits(f,3);
        long fourth=extraShow(f,4), group=manualGroup(f,fourth);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),group,key(),new WaitingRequest(List.of(fourth),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
    }

    static BookingResult direct(Fixture f,long show,int party) {
        var r=request(f);
        return tx(em->plans(em).create(f.user(),key(),new CreateBookingGroupRequest(BookingEntryPoint.THEATER_SMART,
                r.movieId(),r.viewingDate(),party,null,null,show,new AudienceRequest(party,0,null,null))));
    }

    @Test void directShowCreatesIndependentZonesAndOverviewExcludesManualBookings() {
        var f=zoned();
        preferredStatus(f, f.shows().getLast(), SeatStatus.RESERVED);
        var result=direct(f,f.shows().getLast(),2);
        preferredStatus(f, f.shows().getLast(), SeatStatus.AVAILABLE);
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        long selected=createdId(result);
        long manual=manualGroup(f,f.shows().getFirst());
        tx(em->service(em,CLOCK).register(f.user(),manual,key(),new WaitingRequest(List.of(f.shows().getFirst()),SeatPosition.SIDE_MIDDLE)));
        var all=tx(em->plans(em).get(f.user(),selected));
        assertThat(all.candidates()).hasSize(2).extracting(SmartBookingCandidatesService.Candidate::kind)
                .containsExactlyInAnyOrder("PREFERRED", "BALANCED");
        assertThat(all.candidates()).noneMatch(c->c.groupId().equals(manual));
        assertThat(all.batches()).hasSize(1);
        assertThat(all.batches().getFirst()).containsExactlyElementsOf(all.candidates().stream().map(SmartBookingCandidatesService.Candidate::groupId).toList());
        assertThatThrownBy(()->tx(em->plans(em).get(f.user(),manual))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tx(em->{
            assertThat(((Number)em.createNativeQuery("select count(*) from information_schema.tables where table_schema=database() and table_name='smart_booking_plans'",Object.class).getSingleResult()).intValue()).isZero();
            return null;
        });
        dispatcher(CLOCK).dispatch(f.shows().getLast());
        var candidate=tx(em->plans(em).get(f.user(),selected)).candidates().stream().filter(c->c.groupId()==selected).findFirst().orElseThrow();
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidate.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        assertThat(tx(em->plans(em).get(f.user(),null)).candidates()).hasSize(1)
                .allMatch(c->c.kind().equals("BALANCED"));
        assertThat(tx(em->plans(em).get(f.user(),selected)).candidates()).hasSize(2);
    }

    @Test void directShowImmediatelyHoldsOnlyPreferredZoneWithSixSeatsSplitThreePlusThree() {
        var f=zoned(); var show=f.shows().getFirst();
        tx(em->{
            var rows=inventory(em,show);
            for(int i=0;i<12;i++) {
                var seat=rows.get(i).getSeat();
                seat.setAdjacencySegment("split"+(i/3)); seat.setPositionInSegment(i%3+1);
            }
            return null;
        });
        var result=direct(f,show,6); assertThat(result.status()).as(result.body()).isEqualTo(201);
        var chosen=tx(em->plans(em).get(f.user(),createdId(result))).candidates().getFirst();
        assertThat(chosen.kind()).isEqualTo("PREFERRED");
        var all=tx(em->plans(em).get(f.user(),null)).candidates();
        assertThat(all).hasSize(1).allMatch(c->c.showtimeId().equals(show));
        assertThat(chosen.payment().reservation().seatIds()).hasSize(6);
        assertThat(chosen.zone()).isEqualTo(SeatPosition.MIDDLE_MIDDLE);
        assertThat(all).extracting(SmartBookingCandidatesService.Candidate::zone).doesNotHaveDuplicates();
        assertThat(dispatcher(CLOCK).dispatch(show)).isZero();
    }

    @Test void soldOutShowCreatesThreeDistinctZoneWaitsAndReplayDoesNotDuplicateThem() {
        var f=zoned(); var show=f.shows().getFirst();
        tx(em->{ inventory(em,show).forEach(i->i.setStatus(SeatStatus.RESERVED)); return null; });
        var r=request(f);
        var request=new CreateBookingGroupRequest(BookingEntryPoint.THEATER_SMART,r.movieId(),r.viewingDate(),2,
                null,null,show,r.audience());
        var key=key();
        var result=tx(em->plans(em).create(f.user(),key,request));
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        var candidates=plan(f,createdId(result)).candidates();
        assertThat(candidates).hasSize(3).allSatisfy(candidate->{
            assertThat(candidate.showtimeId()).isEqualTo(show);
            assertThat(candidate.waiting().items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
        });
        assertThat(candidates).extracting(SmartBookingCandidatesService.Candidate::zone).doesNotHaveDuplicates();
        var replay=tx(em->plans(em).create(f.user(),key,request));
        assertThat(replay).isEqualTo(result);
        assertThat(plan(f,createdId(result)).candidates()).hasSize(3);
    }

    @Test void directShowKeepsPreferredZoneWhenEverySeatIsOccupied() {
        var f=zoned(); var show=f.shows().getFirst();
        var first=another(f,2,false); long group=manualGroup(first,show);
        tx(em->service(em,CLOCK).register(first.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE)));
        tx(em->{ inventory(em,show).forEach(i->i.setStatus(SeatStatus.RESERVED)); return null; });
        var result=direct(f,show,2); assertThat(result.status()).as(result.body()).isEqualTo(201);
        var chosen=tx(em->plans(em).get(f.user(),createdId(result))).candidates().getFirst();
        assertThat(chosen.zone()).isEqualTo(SeatPosition.MIDDLE_MIDDLE);
        assertThat(chosen.waiting().items().getFirst().aheadCount()).isEqualTo(1);
    }

}
