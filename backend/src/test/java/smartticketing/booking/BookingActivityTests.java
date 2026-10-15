package smartticketing.booking;

import org.junit.jupiter.api.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.BookingActivityService;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static smartticketing.booking.BookingWaitingTests.*;

class BookingActivityTests {
    static final List<String> sql = new ArrayList<>();
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(sql::add); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }

    @Test void overviewIsOwnedReadOnlyAndDoesNotGrowWithHistory() {
        var first = fixture(2, 2); register(first);
        var second = another(first, 2, false); register(second);
        for (int i = 0; i < 25; i++) {
            var old = another(second, 2, true);
            tx(em -> { em.find(BookingRequestGroup.class, old.group()).setStatus(BookingGroupStatus.COMPLETED); return null; });
        }
        sql.clear();
        var result = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(second.user()));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(second.group());
        assertThat(result.getFirst().kind()).isEqualTo("waiting");
        assertThat(result.getFirst().queues()).hasSize(2).allMatch(q -> q.aheadCount() == 1);
        assertThat(sql).hasSizeLessThanOrEqualTo(5);
        assertThat(sql).noneMatch(q -> q.toLowerCase().contains("for update") || q.toLowerCase().startsWith("update "));
    }

    @Test void activeHoldIncludesSeatsAndClockAndExpiredHoldIsNotOfferedForPayment() {
        var f = fixture(2, 2); register(f);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        sql.clear();
        var result = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(f.user()));
        assertThat(result).hasSize(1);
        var item = result.getFirst();
        assertThat(item.kind()).isEqualTo("holding");
        assertThat(item.reservation().seatLabels()).containsExactly("A1", "A2");
        assertThat(Duration.between(item.reservation().serverTime(), item.reservation().expiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(item.queues()).isEmpty();
        assertThat(sql).hasSizeLessThanOrEqualTo(5);
        var later = Clock.offset(CLOCK, Duration.ofMinutes(6));
        var expired = tx(em -> new BookingActivityService(em, holds(em, later)).active(f.user()));
        assertThat(expired).noneMatch(i -> i.kind().equals("holding"));
        // Reading the overview never mutates booking state; the lifecycle handles expiry.
        tx(em -> { assertThat(em.find(BookingRequestGroup.class, f.group()).getStatus()).isEqualTo(BookingGroupStatus.HOLDING); return null; });
    }

    @Test void endedShowsAndEmptyRequestsAreNotReturned() {
        var f = fixture(2, 2);
        var empty = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).active(f.user()));
        assertThat(empty).isEmpty();
        register(f);
        var later = Clock.offset(CLOCK, Duration.ofHours(8));
        var ended = tx(em -> new BookingActivityService(em, holds(em, later)).active(f.user()));
        assertThat(ended).isEmpty();
    }

    @Test void historyIncludesCancelledQueuesAndElapsedHoldsWithoutMutatingOrLeakingOtherUsers() {
        var f = fixture(2, 2); register(f);
        var other = another(f, 2, false); register(other);
        tx(em -> service(em, CLOCK).cancel(other.user(), other.group(), key()));
        var cancelled = tx(em -> new BookingActivityService(em, holds(em, CLOCK)).history(other.user(), null));
        assertThat(cancelled.items()).hasSize(2).allMatch(i -> i.groupId().equals(other.group()) && i.status().equals("CANCELLED"));
        assertThat(tx(em -> new BookingActivityService(em, holds(em, CLOCK)).history(f.user(), null)).items()).isEmpty();
        dispatcher(CLOCK).dispatch(f.shows().getFirst());
        var later = Clock.offset(CLOCK, Duration.ofMinutes(6));
        sql.clear();
        var expired = tx(em -> new BookingActivityService(em, holds(em, later)).history(f.user(), null));
        assertThat(expired.items()).hasSize(1).allMatch(i -> i.kind().equals("holding") && i.status().equals("EXPIRED"));
        assertThat(sql).noneMatch(q -> q.toLowerCase().contains("for update") || q.toLowerCase().startsWith("update "));
        assertThat(sql).hasSizeLessThanOrEqualTo(5);
    }

    @Test void endedQueuesArePaginatedWithoutMissingOrRepeatingGroups() {
        var first = fixture(2, 2); register(first);
        tx(em -> service(em, CLOCK).cancel(first.user(), first.group(), key()));
        for (int i = 0; i < 21; i++) {
            var f = another(first, 2, true); register(f);
            tx(em -> service(em, CLOCK).cancel(f.user(), f.group(), key()));
        }
        var later = Clock.offset(CLOCK, Duration.ofHours(8));
        var page = tx(em -> new BookingActivityService(em, holds(em, later)).history(first.user(), null));
        assertThat(page.items()).hasSize(40).allMatch(i -> i.status().equals("CANCELLED"));
        assertThat(page.nextBefore()).isNotNull();
        var next = tx(em -> new BookingActivityService(em, holds(em, later)).history(first.user(), page.nextBefore()));
        assertThat(next.items()).hasSize(4);
        assertThat(next.nextBefore()).isNull();
        assertThat(next.items().stream().map(BookingActivityService.HistoryItem::groupId))
                .doesNotContainAnyElementsOf(page.items().stream().map(BookingActivityService.HistoryItem::groupId).toList());
    }
}
