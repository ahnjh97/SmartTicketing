package smartticketing.booking;

import org.junit.jupiter.api.*;
import smartticketing.dto.booking.*;
import smartticketing.entity.enums.*;
import smartticketing.service.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static smartticketing.booking.BookingWaitingTests.*;
import static smartticketing.booking.SmartBookingCandidatesTests.manualGroup;

class ManualSeatWaitingTests {
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }

    static Fixture manual(Fixture f) { return new Fixture(f.user(), manualGroup(f, f.shows().getFirst()), f.shows()); }
    static List<Long> seats(Fixture f) { return tx(em -> inventory(em, f.shows().getFirst()).stream().map(i -> i.getSeat().getId()).toList()); }
    static BookingResult waitFor(Fixture f, List<Long> ids, String key) {
        return tx(em -> service(em, CLOCK).register(f.user(), f.group(), key, new WaitingRequest(List.of(f.shows().getFirst()), null, ids)));
    }

    @Test void occupiedExactSeatsWaitTogetherThenAllocateOnlyThoseSeatsAfterPaidCancellation() {
        var f = manual(fixture(2, 5));
        var owner = manual(another(f, 2, false));
        var all = seats(f); var chosen = List.of(all.get(0), all.get(3));
        // Cross-zone, non-adjacent manual selection must remain exact, even when other pairs are free.
        tx(em -> { inventory(em, f.shows().getFirst()).get(3).getSeat().setSeatPosition(SeatPosition.SIDE_MIDDLE); return null; });
        var held = tx(em -> holds(em, CLOCK).manual(owner.user(), owner.group(), key(), new ManualHoldRequest(chosen)));
        long reservation = BookingSmartTests.value(held, "id");
        assertThat(tx(em -> BookingPaymentTests.service(em, CLOCK, true).pay(owner.user(), reservation, key(), new MockPaymentRequest(PaymentMethod.MOCK, false))).status()).isEqualTo(201);
        assertThat(waitFor(f, chosen, key()).status()).isEqualTo(201);
        assertThat(state(f).items().getFirst().seatLabels()).containsExactly("A1", "A4");
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isZero();
        assertThat(state(f).activeReservationId()).isNull();
        db.restartPersistence();
        assertThat(tx(em -> BookingPaymentTests.service(em, CLOCK, true).cancel(owner.user(), reservation, key())).status()).isEqualTo(200);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        long assignedId = state(f).activeReservationId();
        var assigned = tx(em -> holds(em, CLOCK).reservation(f.user(), assignedId));
        assertThat(assigned.seatIds()).containsExactlyElementsOf(chosen);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isZero();
    }

    @Test void mixedSeatsNeverPartiallyHoldAndDifferentFreeSeatsDoNotReplaceRequestedSeats() {
        var f = manual(fixture(2, 5)); var all = seats(f); var chosen = List.of(all.get(0), all.get(3));
        tx(em -> { inventory(em, f.shows().getFirst()).get(3).setStatus(SeatStatus.HOLDING); return null; });
        assertThat(waitFor(f, chosen, key()).status()).isEqualTo(201);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isZero();
        tx(em -> { assertThat(inventory(em, f.shows().getFirst()).getFirst().getStatus()).isEqualTo(SeatStatus.AVAILABLE); return null; });
        tx(em -> { inventory(em, f.shows().getFirst()).get(3).setStatus(SeatStatus.AVAILABLE); return null; });
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
    }

    @Test void changedSeatsGetNewNumberSameSeatsAndRetryKeepOneQueue() {
        var f = manual(fixture(2, 6)); var all = seats(f); String requestKey = key();
        var original = waitFor(f, all.subList(0, 2), requestKey);
        assertThat(waitFor(f, all.subList(0, 2).reversed(), requestKey)).isEqualTo(original);
        var before = state(f).items().getFirst();
        assertThat(waitFor(f, all.subList(0, 2), key()).status()).isEqualTo(201);
        assertThat(state(f).items().getFirst().queueNumber()).isEqualTo(before.queueNumber());
        assertThat(waitFor(f, all.subList(2, 4), key()).status()).isEqualTo(201);
        assertThat(state(f).items()).hasSize(1);
        var after = state(f).items().getFirst();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.queueNumber()).isGreaterThan(before.queueNumber());
        assertThat(after.seatIds()).containsExactlyElementsOf(all.subList(2, 4));
    }

    @Test void onlyOverlappingSeatsCountAheadAndManualCannotStealEarlierWaitingSeats() {
        var first = manual(fixture(2, 6)); var other = manual(another(first, 2, false)); var last = manual(another(first, 2, false));
        var all = seats(first);
        assertThat(waitFor(first, all.subList(0, 2), key()).status()).isEqualTo(201);
        assertThat(waitFor(other, all.subList(2, 4), key()).status()).isEqualTo(201);
        assertThat(waitFor(last, all.subList(0, 2), key()).status()).isEqualTo(201);
        assertThat(state(other).items().getFirst().aheadCount()).isZero();
        assertThat(state(last).items().getFirst().aheadCount()).isEqualTo(1);
        var overview = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(last.user()));
        assertThat(overview.getFirst().queues().getFirst().seatLabels()).containsExactly("A1", "A2");
        assertThat(overview.getFirst().queues().getFirst().aheadCount()).isEqualTo(1);
        var stolen = tx(em -> holds(em, CLOCK).manual(last.user(), last.group(), key(), new ManualHoldRequest(all.subList(0, 2))));
        assertThat(stolen.status()).isEqualTo(409); assertThat(stolen.body()).contains("WAITING_PRIORITY");
        assertThat(tx(em -> holds(em, CLOCK).manual(first.user(), first.group(), key(), new ManualHoldRequest(all.subList(0, 2)))).status()).isEqualTo(201);
        assertThat(dispatcher(CLOCK).dispatch(first.shows().getFirst())).isEqualTo(1);
        assertThat(state(last).items().getFirst().status()).isEqualTo(QueueStatus.WAITING);
    }

    @Test void blockedForeignOrWrongPartySeatsCannotCreateQueue() {
        var f = manual(fixture(2, 4)); var all = seats(f);
        tx(em -> { inventory(em, f.shows().getFirst()).getFirst().setStatus(SeatStatus.BLOCKED); return null; });
        assertThat(waitFor(f, all.subList(0, 2), key()).status()).isEqualTo(400);
        assertThat(waitFor(f, all.subList(1, 2), key()).status()).isEqualTo(400);
        var foreign = seats(manual(fixture(2, 2)));
        assertThat(waitFor(f, foreign, key()).status()).isEqualTo(400);
        assertThat(state(f).items()).isEmpty();
    }

    @Test void laterEligibleManualWaiterCanPassEarlierIncompleteSelection() {
        var first = manual(fixture(2, 6)); var later = manual(another(first, 2, false));
        var unrelated = manual(another(first, 2, false)); var all = seats(first);
        tx(em -> { inventory(em, first.shows().getFirst()).get(1).setStatus(SeatStatus.RESERVED); return null; });
        assertThat(waitFor(first, all.subList(0, 2), key()).status()).isEqualTo(201);
        assertThat(waitFor(later, List.of(all.get(0), all.get(2)), key()).status()).isEqualTo(201);
        assertThat(waitFor(unrelated, all.subList(4, 6), key()).status()).isEqualTo(201);
        assertThat(dispatcher(CLOCK).dispatch(first.shows().getFirst())).isEqualTo(2);
        assertThat(state(first).activeReservationId()).isNull();
        assertThat(state(later).activeReservationId()).isNotNull();
        assertThat(state(unrelated).activeReservationId()).isNotNull();
        tx(em -> { inventory(em, first.shows().getFirst()).get(1).setStatus(SeatStatus.AVAILABLE); return null; });
        assertThat(dispatcher(CLOCK).dispatch(first.shows().getFirst())).isZero();
        assertThat(tx(em -> service(em, CLOCK).cancel(later.user(), later.group(), key())).status()).isEqualTo(200);
        assertThat(dispatcher(CLOCK).dispatch(first.shows().getFirst())).isEqualTo(1);
        assertThat(state(first).activeReservationId()).isNotNull();
    }

    @Test void newManualHoldCanPassIncompleteWaitButCannotPassNextEligibleWaiter() {
        var first = manual(fixture(2, 4)); var next = manual(another(first, 2, false));
        var newcomer = manual(another(first, 2, false)); var all = seats(first);
        tx(em -> { inventory(em, first.shows().getFirst()).get(1).setStatus(SeatStatus.RESERVED); return null; });
        assertThat(waitFor(first, all.subList(0, 2), key()).status()).isEqualTo(201);
        var chosen = List.of(all.get(0), all.get(2));
        assertThat(waitFor(next, chosen, key()).status()).isEqualTo(201);
        assertThat(tx(em -> holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(chosen))).status()).isEqualTo(409);
        assertThat(tx(em -> service(em, CLOCK).cancel(next.user(), next.group(), key())).status()).isEqualTo(200);
        assertThat(tx(em -> holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(chosen))).status()).isEqualTo(201);
        assertThat(state(first).activeReservationId()).isNull();
    }

    @Test void newManualHoldRacingDispatcherCannotOvertakeEarlierWaiter() throws Exception {
        var first = manual(fixture(2, 2)); var newcomer = manual(another(first, 2, false)); var all = seats(first);
        assertThat(waitFor(first, all, key()).status()).isEqualTo(201);
        var results = BookingPaymentTests.race(() -> dispatcher(CLOCK).dispatch(first.shows().getFirst()),
                () -> tx(em -> holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(all))));
        assertThat((Integer) results.getFirst()).isEqualTo(1);
        assertThat(((BookingResult) results.getLast()).status()).isEqualTo(409);
        assertThat(state(first).activeReservationId()).isNotNull();
        assertThat(state(newcomer).activeReservationId()).isNull();
    }

    @Test void smartZoneWaitBlocksNewManualHoldUntilCancelled() {
        var smart = SmartBookingCandidatesTests.zoned(); var newcomer = manual(another(smart, 2, false)); var all = seats(smart);
        // Use a real smart candidate's zone queue while the preferred zone is occupied.
        SmartBookingCandidatesTests.preferredStatus(smart, smart.shows().getFirst(), SeatStatus.RESERVED);
        SmartBookingCandidatesTests.create(smart);
        SmartBookingCandidatesTests.preferredStatus(smart, smart.shows().getFirst(), SeatStatus.AVAILABLE);
        var blocked = tx(em -> holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(all.subList(0, 2))));
        assertThat(blocked.status()).isEqualTo(409); assertThat(blocked.body()).contains("WAITING_PRIORITY");
        var queues = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(smart.user()));
        for (var item : queues) assertThat(tx(em -> service(em, CLOCK).cancel(smart.user(), item.id(), key())).status()).isEqualTo(200);
        assertThat(tx(em -> holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(all.subList(0, 2)))).status()).isEqualTo(201);
    }

    @Test void queueSeatChangeAfterReadSnapshotStillBlocksNewManualHold() {
        var first = manual(fixture(2, 4)); var newcomer = manual(another(first, 2, false)); var all = seats(first);
        assertThat(waitFor(first, all.subList(0, 2), key()).status()).isEqualTo(201);
        var result = tx(em -> {
            em.createQuery("select count(q) from WaitingQueue q", Long.class).getSingleResult();
            // Another transaction moves the queue after this transaction's snapshot began.
            assertThat(waitFor(first, all.subList(2, 4), key()).status()).isEqualTo(201);
            return holds(em, CLOCK).manual(newcomer.user(), newcomer.group(), key(), new ManualHoldRequest(all.subList(2, 4)));
        });
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).contains("WAITING_PRIORITY");
        assertThat(state(newcomer).activeReservationId()).isNull();
    }

    @Test void exactWaitingUsesOneOfThreeSlotsAndChangingAtLimitStillWorks() {
        var seed = fixture(2, 8); var all = seats(seed); var groups = new ArrayList<Fixture>();
        for (int i = 0; i < 4; i++) groups.add(manual(seed));
        for (int i = 0; i < 3; i++) assertThat(waitFor(groups.get(i), all.subList(i * 2, i * 2 + 2), key()).status()).isEqualTo(201);
        assertThat(waitFor(groups.get(3), all.subList(6, 8), key()).status()).isEqualTo(409);
        assertThat(waitFor(groups.getFirst(), all.subList(6, 8), key()).status()).isEqualTo(201);
        var active = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(seed.user()));
        assertThat(active).hasSize(3);
    }
}
