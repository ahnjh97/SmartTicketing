package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

/** Bounded history maintenance. Never remove unacknowledged events or reusable idempotency keys. */
@Component
public class BookingHistoryRetention {
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final AdminMaintenanceGate gate;
    private final Clock clock;
    private long eventAfter,operationAfter;
    @Value("${booking.history.event-days:7}") private long eventDays=7;
    @Value("${booking.history.response-days:30}") private long responseDays=30;
    public BookingHistoryRetention(EntityManager em,PlatformTransactionManager manager,AdminMaintenanceGate gate,Clock clock) {
        this.em=em;this.tx=new TransactionTemplate(manager);this.gate=gate;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${booking.history.delay-ms:60000}")
    public synchronized void clean() {
        gate.background(() -> tx.executeWithoutResult(status -> {
            var now=LocalDateTime.now(clock);
            var events=em.createQuery("select e.id from BookingOutboxEvent e where e.id>:after order by e.id",Long.class)
                    .setParameter("after",eventAfter).setMaxResults(500).getResultList();
            if(!events.isEmpty()) em.createQuery("delete from BookingOutboxEvent e where e.id in :ids and e.status=:completed and e.completedAt<:cutoff")
                    .setParameter("completed",smartticketing.entity.BookingOutboxEvent.Status.COMPLETED)
                    .setParameter("ids",events).setParameter("cutoff",now.minusDays(Math.max(1,eventDays))).executeUpdate();
            var operations=em.createQuery("select o.id from BookingOperation o where o.id>:after order by o.id",Long.class)
                    .setParameter("after",operationAfter).setMaxResults(500).getResultList();
            if(!operations.isEmpty()) em.createQuery("update BookingOperation o set o.responseBody=null where o.id in :ids and o.status<>smartticketing.entity.enums.BookingOperationStatus.PROCESSING and o.updatedAt<:cutoff and o.responseBody is not null")
                    .setParameter("ids",operations).setParameter("cutoff",now.minusDays(Math.max(1,responseDays))).executeUpdate();
            // Set cursors only after the transaction actually commits.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    eventAfter=events.size()<500 ? 0 : events.getLast();
                    operationAfter=operations.size()<500 ? 0 : operations.getLast();
                }
            });
        }));
    }
}
