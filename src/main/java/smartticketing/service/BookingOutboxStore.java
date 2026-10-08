package smartticketing.service;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.entity.BookingOutboxEvent;
import smartticketing.entity.BookingOutboxEvent.*;
import java.time.*;
import java.util.*;

/** Short claim/ack transactions. Handler work never holds an outbox row lock. */
@Service
public class BookingOutboxStore {
    public record Delivery(Long id, Long showtimeId, long aggregateVersion, int schemaVersion,
                           Type type, Long groupId, String reason, LocalDateTime createdAt,
                           int attempt, String leaseToken) {}
    private final EntityManager em;
    private final TransactionTemplate transaction;

    public BookingOutboxStore(EntityManager em, PlatformTransactionManager manager) {
        this.em=em;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Optional<Delivery> claim(Set<Type> types, LocalDateTime now, Duration lease) {
        if (types.isEmpty()) return Optional.empty();
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Positive lease required");
        return transaction.execute(status -> {
            var ids=em.createNativeQuery("""
                    select id from booking_outbox_events where event_type in (:types)
                    and ((status='PENDING' and available_at<=:now) or (status='PROCESSING' and lease_until<=:now))
                    order by id limit 1 for update skip locked
                    """).setParameter("types",types.stream().map(Enum::name).toList()).setParameter("now",now).getResultList();
            if (ids.isEmpty()) return Optional.empty();
            var event=em.find(BookingOutboxEvent.class,((Number)ids.getFirst()).longValue());
            event.setStatus(Status.PROCESSING); event.setLeaseToken(UUID.randomUUID().toString());
            event.setLeaseUntil(now.plus(lease)); event.setAttempts(Math.addExact(event.getAttempts(),1));
            return Optional.of(new Delivery(event.getId(),event.getShowtimeId(),event.getAggregateVersion(),event.getSchemaVersion(),
                    event.getEventType(),event.getGroupId(),event.getReason(),event.getCreatedAt(),event.getAttempts(),event.getLeaseToken()));
        });
    }

    public boolean complete(Delivery delivery, LocalDateTime now) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var event=owned(delivery);
            if (event==null) return false;
            event.setStatus(Status.COMPLETED); event.setCompletedAt(now); event.setLastError(null);
            event.setLeaseToken(null); event.setLeaseUntil(null);
            return true;
        }));
    }

    public boolean retry(Delivery delivery, LocalDateTime now, Throwable failure) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var event=owned(delivery);
            if (event==null) return false;
            long seconds=Math.min(300, 1L << Math.min(9,event.getAttempts()));
            event.setStatus(Status.PENDING); event.setAvailableAt(now.plusSeconds(seconds));
            event.setLeaseToken(null); event.setLeaseUntil(null);
            // Persist only the type, never SQL text, credentials or arbitrary exception messages.
            String error=failure.getClass().getSimpleName();
            event.setLastError(error.substring(0,Math.min(160,error.length())));
            return true;
        }));
    }

    private BookingOutboxEvent owned(Delivery delivery) {
        var event=em.find(BookingOutboxEvent.class,delivery.id(),LockModeType.PESSIMISTIC_WRITE);
        return event!=null && event.getStatus()==Status.PROCESSING && Objects.equals(event.getLeaseToken(),delivery.leaseToken()) ? event : null;
    }
}
