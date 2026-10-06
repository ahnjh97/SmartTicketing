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
    @BeforeAll static void start() throws Exception { db=new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { if(db!=null) db.close(); }

    static SmartBookingCandidatesService plans(EntityManager em) {
        var hold=holds(em,CLOCK); var ops=new BookingIdempotency(em);
        var groups=new BookingGroupService(em,hold,ops,Validation.buildDefaultValidatorFactory().getValidator());
        return new SmartBookingCandidatesService(em,groups,hold,service(em,CLOCK),BookingPaymentTests.service(em,CLOCK,true),ops);
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
    static long createThree(Fixture f) {
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

    @Test void threeCandidatesHaveIndependentZoneNumberOneAndCanAllPayThenCancelOne() {
        var f=zoned(); long id=createThree(f);
        var initial=plan(f,id);
        assertThat(initial.candidates()).hasSize(3);
        assertThat(initial.candidates()).extracting(SmartBookingCandidatesService.Candidate::zone).doesNotHaveDuplicates();
        assertThat(initial.candidates()).allSatisfy(c->{
            assertThat(c.waiting().items()).hasSize(1);
            assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(1);
            assertThat(c.waiting().items().getFirst().seatZone()).isEqualTo(c.zone());
        });
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(3);
        var held=plan(f,id);
        assertThat(held.candidates()).allSatisfy(c->assertThat(c.payment().reservation().status()).isEqualTo(ReservationStatus.PENDING));
        var allSeats=held.candidates().stream().flatMap(c->c.payment().reservation().seatIds().stream()).toList();
        assertThat(allSeats).hasSize(6).doesNotHaveDuplicates();
        for(var candidate:held.candidates()) {
            var paid=tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidate.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
            assertThat(paid.status()).isEqualTo(201);
        }
        assertThat(plan(f,id).candidates()).allSatisfy(c->assertThat(c.payment().reservation().status()).isEqualTo(ReservationStatus.CONFIRMED));
        long cancel=held.candidates().getFirst().payment().reservation().id();
        assertThat(tx(em->BookingPaymentTests.service(em,CLOCK,true).cancel(f.user(),cancel,key())).status()).isEqualTo(200);
        assertThat(plan(f,id).candidates()).extracting(c->c.payment().reservation().status())
                .containsExactly(ReservationStatus.CANCELLED,ReservationStatus.CONFIRMED,ReservationStatus.CONFIRMED);
    }

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
        assertThat(candidate.waiting().items().getFirst().status()).isEqualTo(QueueStatus.HOLDING);
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

    @Test void concurrentUsersCannotBothImmediatelyHoldTheLastPreferredPair() throws Exception {
        var first = zoned(); var second = another(first, 2, false);
        var firstRequest = request(first); var secondRequest = request(second);
        tx(em -> {
            inventory(em, first.shows().getFirst()).stream().filter(i -> i.getSeat().getSeatPosition() == SeatPosition.MIDDLE_MIDDLE)
                    .skip(2).forEach(i -> i.setStatus(SeatStatus.RESERVED));
            return null;
        });
        var results = BookingPaymentTests.race(
                () -> tx(em -> plans(em).create(first.user(), key(), firstRequest)),
                () -> tx(em -> plans(em).create(second.user(), key(), secondRequest)));
        assertThat(results).allSatisfy(result -> assertThat(((BookingResult) result).status()).isEqualTo(201));
        var a = tx(em -> plans(em).get(first.user(), null)).candidates();
        var b = tx(em -> plans(em).get(second.user(), null)).candidates();
        assertThat(List.of(a.size(), b.size())).containsExactlyInAnyOrder(1, 3);
        var all = new ArrayList<>(a); all.addAll(b);
        assertThat(all.stream().filter(c -> c.payment() != null)).hasSize(1);
        assertThat(all.stream().filter(c -> c.zone() == SeatPosition.MIDDLE_MIDDLE && c.payment() == null)).hasSize(1);
    }

    @Test void payingSideDoesNotEndCenterWaitAndReleasedCenterCanAlsoBePaid() {
        var f=zoned();
        // Occupy central seats with a real paid reservation owned by another viewer.
        var owner=another(f,6,false);
        long ownerReservation=tx(em->{
            var ids=inventory(em,f.shows().getFirst()).stream().filter(i->i.getSeat().getSeatPosition()==SeatPosition.MIDDLE_MIDDLE)
                    .map(i->i.getSeat().getId()).toList();
            return BookingSmartTests.value(holds(em,CLOCK).hold(owner.user(),owner.group(),key(),BookingHoldService.Source.SMART,
                    new BookingHoldService.Candidate(f.shows().getFirst(),ids)),"id");
        });
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(owner.user(),ownerReservation,key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        long id=create(f); dispatcher(CLOCK).dispatch(f.shows().getFirst());
        var snapshot=plan(f,id);
        var center=snapshot.candidates().stream().filter(c->c.zone()==SeatPosition.MIDDLE_MIDDLE).findFirst().orElseThrow();
        var side=snapshot.candidates().stream().filter(c->c.zone()==SeatPosition.SIDE_MIDDLE).findFirst().orElseThrow();
        assertThat(center.payment()).isNull();
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),side.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        assertThat(plan(f,id).candidates().stream().filter(c->c.groupId().equals(center.groupId())).findFirst().orElseThrow().waiting().items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
        tx(em->BookingPaymentTests.service(em,CLOCK,true).cancel(owner.user(),ownerReservation,key()));
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        var acquired=plan(f,id).candidates().stream().filter(c->c.groupId().equals(center.groupId())).findFirst().orElseThrow();
        assertThat(tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),acquired.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))).status()).isEqualTo(201);
        assertThat(plan(f,id).candidates().stream().filter(c->c.payment()!=null && c.payment().reservation().status()==ReservationStatus.CONFIRMED)).hasSize(2);
    }

    @Test void zoneNumbersAdvanceSeparatelyAndOneCancellationDoesNotCancelSiblings() {
        var first=zoned(); long a=createThree(first);
        var second=another(first,2,false); long b=create(second);
        assertThat(plan(second,b).candidates()).allSatisfy(c->{
            assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(2);
            assertThat(c.waiting().items().getFirst().aheadCount()).isEqualTo(1);
        });
        long cancelled=plan(first,a).candidates().getFirst().groupId();
        tx(em->service(em,CLOCK).cancel(first.user(),cancelled,key()));
        assertThat(plan(first,a).candidates()).extracting(SmartBookingCandidatesService.Candidate::status)
                .containsExactly(BookingGroupStatus.CANCELLED,BookingGroupStatus.ACTIVE,BookingGroupStatus.ACTIVE);
        assertThat(plan(second,b).candidates()).extracting(c->c.waiting().items().getFirst().aheadCount()).containsExactlyInAnyOrder(0L,1L,1L);
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

    @Test void strictZoneCannotBeBypassedAndSameZoneCannotBeRegisteredTwice() {
        var f=zoned(); long id=createThree(f); var candidate=plan(f,id).candidates().getFirst();
        var result=tx(em->{
            var wrong=inventory(em,f.shows().getFirst()).stream().filter(i->i.getSeat().getSeatPosition()!=candidate.zone()).limit(2).map(i->i.getSeat().getId()).toList();
            return holds(em,CLOCK).hold(f.user(),candidate.groupId(),key(),BookingHoldService.Source.WAITING,
                    new BookingHoldService.Candidate(f.shows().getFirst(),wrong));
        });
        assertThat(result.status()).isEqualTo(409);
        var request=request(f);
        assertThat(tx(em->plans(em).create(f.user(),key(),request)).status()).isEqualTo(409);
    }

    @Test void aBusyCenterDoesNotIncreaseSideNumbersAndEarlierZoneRequestGetsFirstSeats() {
        var f=zoned(); var earlier=another(f,6,false);
        tx(em->{ em.createNativeQuery("update booking_request_groups set candidate_zone='MIDDLE_MIDDLE' where id=:id")
                .setParameter("id",earlier.group()).executeUpdate(); return null; });
        assertThat(register(earlier,List.of(f.shows().getFirst()),key()).status()).isEqualTo(201);
        long id=create(f);
        assertThat(plan(f,id).candidates()).allSatisfy(c->{
            assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(c.zone()==SeatPosition.MIDDLE_MIDDLE?2:1);
            assertThat(c.waiting().items().getFirst().aheadCount()).isEqualTo(c.zone()==SeatPosition.MIDDLE_MIDDLE?1:0);
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
        assertThat(plan(next,create(next)).candidates()).allSatisfy(c->assertThat(c.waiting().items().getFirst().queueNumber()).isEqualTo(1));
    }

    @Test void concurrentReplayCreatesOneBundleAndIndependentPaymentsBothSucceed() throws Exception {
        var f=zoned(); var request=request(f); var key=key();
        preferredStatus(f, f.shows().getFirst(), SeatStatus.RESERVED);
        var results=BookingPaymentTests.race(()->tx(em->plans(em).create(f.user(),key,request)),
                ()->tx(em->plans(em).create(f.user(),key,request)));
        assertThat(results.getFirst()).isEqualTo(results.getLast());
        long id=createdId((BookingResult)results.getFirst());
        assertThat(plan(f,id).candidates()).hasSize(3);
        preferredStatus(f, f.shows().getFirst(), SeatStatus.AVAILABLE);
        dispatcher(CLOCK).dispatch(f.shows().getFirst());
        var candidates=plan(f,id).candidates();
        var payments=BookingPaymentTests.race(
                ()->tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidates.get(0).payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))),
                ()->tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidates.get(1).payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false))));
        assertThat(payments).allSatisfy(r->assertThat(((BookingResult)r).status()).isEqualTo(201));
        assertThat(plan(f,id).candidates()).extracting(c->c.payment().reservation().status())
                .containsExactly(ReservationStatus.CONFIRMED,ReservationStatus.CONFIRMED,ReservationStatus.PENDING);
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

    @Test void threeSlotsAreSharedAcrossMethodsAndConcurrentRequestsCannotExceedLimit() throws Exception {
        var f=zoned(); create(f); var show=f.shows().getLast();
        long a=manualGroup(f,show), b=manualGroup(f,show), c=manualGroup(f,show);
        assertThat(tx(em->service(em,CLOCK).register(f.user(),a,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE))).status()).isEqualTo(201);
        var results=BookingPaymentTests.race(
                ()->tx(em->service(em,CLOCK).register(f.user(),b,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_REAR))),
                ()->tx(em->service(em,CLOCK).register(f.user(),c,key(),new WaitingRequest(List.of(show),SeatPosition.SIDE_MIDDLE))));
        assertThat(results.stream().map(r->((BookingResult)r).status()).sorted().toList()).containsExactly(201,409);
        var activity=tx(em->new BookingActivityService(em,holds(em,CLOCK)).active(f.user()));
        assertThat(activity).hasSize(3);
        assertThat(activity.stream().filter(i->i.candidateKind()!=null)).hasSize(1);
        tx(em->service(em,CLOCK).cancel(f.user(),a,key()));
        long rejected=((BookingResult)results.get(0)).status()==409?b:c;
        var zone=rejected==b?SeatPosition.MIDDLE_REAR:SeatPosition.SIDE_MIDDLE;
        assertThat(tx(em->service(em,CLOCK).register(f.user(),rejected,key(),new WaitingRequest(List.of(show),zone))).status()).isEqualTo(201);
    }

    @Test void smartBundleOverLimitIsRejectedWithoutPartialRegistration() {
        var f=zoned(); createThree(f); var r=request(f);
        preferredStatus(f, f.shows().getLast(), SeatStatus.RESERVED);
        var second=new CreateBookingGroupRequest(r.entryPoint(),r.movieId(),r.viewingDate(),r.partySize(),java.time.LocalTime.of(13,0),java.time.LocalTime.of(13,30),null,r.audience());
        var rejected=tx(em->plans(em).create(f.user(),key(),second));
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body()).contains("ACTIVE_BOOKING_LIMIT");
        var activity=tx(em->new BookingActivityService(em,holds(em,CLOCK)).active(f.user()));
        assertThat(activity).hasSize(3);
    }

    @Test void smartRequestsUseOnlyRemainingSlotsForMovieAndDirectBookings() {
        for (boolean theater : List.of(false, true)) for (int used : List.of(1, 2)) {
            var f = zoned(); var show = f.shows().getLast();
            for (int i = 0; i < used; i++) {
                long group = manualGroup(f, show);
                var zone = List.of(SeatPosition.MIDDLE_MIDDLE, SeatPosition.SIDE_MIDDLE).get(i);
                assertThat(tx(em -> service(em, CLOCK).register(f.user(), group, key(), new WaitingRequest(List.of(show), zone))).status()).isEqualTo(201);
            }
            preferredStatus(f, f.shows().getFirst(), SeatStatus.RESERVED);
            var request = request(f);
            var result = theater ? direct(f, f.shows().getFirst(), 2) : tx(em -> plans(em).create(f.user(), key(), request));
            assertThat(result.status()).as(result.body()).isEqualTo(201);
            var candidates = plan(f, createdId(result)).candidates();
            assertThat(candidates).hasSize(3 - used);
            assertThat(candidates).anyMatch(c -> c.kind().equals("PREFERRED"));
            if (used == 1) assertThat(candidates).anyMatch(c -> c.kind().equals("FAST"));
            var active = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(f.user()));
            assertThat(active).hasSize(3);
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

    @Test void fourthDirectHoldIsRejectedAndPaymentFreesCapacity() {
        var f=zoned(); var show=f.shows().getFirst(); var groups=new ArrayList<Long>(); var reservations=new ArrayList<Long>();
        for(int i=0;i<4;i++) groups.add(manualGroup(f,show));
        for(int i=0;i<4;i++) {
            final int index=i;
            var held=tx(em->holds(em,CLOCK).manual(f.user(),groups.get(index),key(),new ManualHoldRequest(inventory(em,show).stream()
                    .skip(index*2).limit(2).map(row->row.getSeat().getId()).toList())));
            assertThat(held.status()).as(held.body()).isEqualTo(i==3?409:201);
            if(i<3) reservations.add(BookingSmartTests.value(held,"id"));
        }
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),reservations.getFirst(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        assertThat(tx(em->holds(em,CLOCK).manual(f.user(),groups.getLast(),key(),new ManualHoldRequest(inventory(em,show).stream()
                .skip(6).limit(2).map(row->row.getSeat().getId()).toList()))).status()).isEqualTo(201);
    }

    static BookingResult direct(Fixture f,long show,int party) {
        var r=request(f);
        return tx(em->plans(em).create(f.user(),key(),new CreateBookingGroupRequest(BookingEntryPoint.THEATER_SMART,
                r.movieId(),r.viewingDate(),party,null,null,show,new AudienceRequest(party,0,null,null))));
    }

    @Test void directShowCreatesThreeCandidatesAndOverviewExcludesManualBookings() {
        var f=zoned();
        preferredStatus(f, f.shows().getLast(), SeatStatus.RESERVED);
        var result=direct(f,f.shows().getLast(),2);
        preferredStatus(f, f.shows().getLast(), SeatStatus.AVAILABLE);
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        long selected=createdId(result);
        long manual=manualGroup(f,f.shows().getFirst());
        tx(em->service(em,CLOCK).register(f.user(),manual,key(),new WaitingRequest(List.of(f.shows().getFirst()),SeatPosition.SIDE_MIDDLE)));
        var all=tx(em->plans(em).get(f.user(),selected));
        assertThat(all.candidates()).hasSize(3).extracting(SmartBookingCandidatesService.Candidate::kind)
                .containsExactlyInAnyOrder("PREFERRED","BALANCED","FAST");
        assertThat(all.candidates()).noneMatch(c->c.groupId().equals(manual));
        assertThatThrownBy(()->tx(em->plans(em).get(f.user(),manual))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tx(em->{
            assertThat(((Number)em.createNativeQuery("select count(*) from information_schema.tables where table_schema=database() and table_name='smart_booking_plans'",Object.class).getSingleResult()).intValue()).isZero();
            return null;
        });
        dispatcher(CLOCK).dispatch(f.shows().getLast());
        var candidate=tx(em->plans(em).get(f.user(),selected)).candidates().stream().filter(c->c.groupId()==selected).findFirst().orElseThrow();
        tx(em->BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),candidate.payment().reservation().id(),key(),new MockPaymentRequest(PaymentMethod.MOCK,false)));
        assertThat(tx(em->plans(em).get(f.user(),null)).candidates()).hasSize(2);
        assertThat(tx(em->plans(em).get(f.user(),selected)).candidates()).hasSize(3);
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

    @Test void directShowUsesShortestZoneQueueWhenEverySeatIsOccupied() {
        var f=zoned(); var show=f.shows().getFirst();
        var first=another(f,2,false); long group=manualGroup(first,show);
        tx(em->service(em,CLOCK).register(first.user(),group,key(),new WaitingRequest(List.of(show),SeatPosition.MIDDLE_MIDDLE)));
        tx(em->{ inventory(em,show).forEach(i->i.setStatus(SeatStatus.RESERVED)); return null; });
        var result=direct(f,show,2); assertThat(result.status()).as(result.body()).isEqualTo(201);
        var chosen=tx(em->plans(em).get(f.user(),createdId(result))).candidates().getFirst();
        assertThat(chosen.zone()).isEqualTo(SeatPosition.MIDDLE_REAR);
        assertThat(chosen.waiting().items().getFirst().aheadCount()).isZero();
    }

}
