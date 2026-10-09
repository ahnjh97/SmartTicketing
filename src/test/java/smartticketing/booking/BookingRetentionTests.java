package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import smartticketing.entity.*;
import smartticketing.service.*;
import java.time.*;
import static smartticketing.booking.BookingWaitingTests.*;
import static org.assertj.core.api.Assertions.*;

@Tag("core")
class BookingRetentionTests {
    @BeforeAll static void start() throws Exception { db=new TemporaryMysqlDatabase(); }
    @AfterAll static void stop() throws Exception { db.close(); }
    @Test void retentionKeepsPendingEventsAndRequestKeysWithoutReexecutingExpiredResponses() {
        var f=fixture(1,1);var requestKey=key();
        assertThat(register(f,f.shows(),requestKey).status()).isEqualTo(201);
        tx(em -> {
            var events=em.createQuery("from BookingOutboxEvent",BookingOutboxEvent.class).getResultList();
            events.getFirst().setStatus(BookingOutboxEvent.Status.COMPLETED);
            events.getFirst().setCompletedAt(NOW.minusDays(10));
            em.createQuery("update BookingOperation o set o.updatedAt=:old").setParameter("old",NOW.minusDays(40)).executeUpdate();
            return null;
        });
        var retention=new BookingHistoryRetention(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),db.transactions(),new AdminMaintenanceGate(),CLOCK);
        retention.clean();retention.clean();
        tx(em -> {
            assertThat(em.createQuery("from BookingOutboxEvent",BookingOutboxEvent.class).getResultList()).hasSize(1)
                    .allMatch(e -> e.getStatus()==BookingOutboxEvent.Status.PENDING);
            assertThat(em.createQuery("from BookingOperation",BookingOperation.class).getResultList()).hasSize(1)
                    .allMatch(o -> o.getResponseBody()==null);
            return null;
        });
        assertThat(register(f,f.shows(),requestKey).status()).isEqualTo(409);
        assertThat(state(f).items()).hasSize(2);
        tx(em -> { assertThat(em.createQuery("select count(e) from BookingOutboxEvent e",Long.class).getSingleResult()).isEqualTo(1);return null; });
    }
}
