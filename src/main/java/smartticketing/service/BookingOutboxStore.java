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
    public record Backlog(long pending, long ready, long processing, long expired, long oldestSeconds) {}
    private final EntityManager em;
    private final TransactionTemplate transaction;

    public BookingOutboxStore(EntityManager em, PlatformTransactionManager manager) {
        this.em=em;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Optional<Delivery> claim(Set<Type> types, LocalDateTime now, Duration lease) {
        return claimBatch(types,now,lease,1).stream().findFirst();
    }

    public List<Delivery> claimBatch(Set<Type> types, LocalDateTime now, Duration lease, int limit) {
        if (types.isEmpty()) return List.of();
        if (lease.isNegative() || lease.isZero() || limit<1 || limit>256)
            throw new IllegalArgumentException("Positive lease and batch size 1..256 required");
        return transaction.execute(status -> {
            // Separate indexed ranges avoid the OR + global ID sort over completed history.
            // Recover abandoned work first so continuous incoming traffic cannot starve it.
            var events=new ArrayList<>(select(types,now,limit,true));
            if(events.size()<limit) events.addAll(select(types,now,limit-events.size(),false));
            if(events.isEmpty()) return List.of();
            String token=UUID.randomUUID().toString();
            var result=events.stream().map(e -> new Delivery(e.getId(),e.getShowtimeId(),e.getAggregateVersion(),e.getSchemaVersion(),
                    e.getEventType(),e.getGroupId(),e.getReason(),e.getCreatedAt(),Math.addExact(e.getAttempts(),1),token)).toList();
            em.createNativeQuery("""
                    update booking_outbox_events set status='PROCESSING',lease_token=:token,
                    lease_until=:until,attempts=attempts+1 where id in (:ids)
                    """).setParameter("token",token).setParameter("until",now.plus(lease))
                    .setParameter("ids",result.stream().map(Delivery::id).toList()).executeUpdate();
            return result;
        });
    }

    @SuppressWarnings("unchecked")
    private List<BookingOutboxEvent> select(Set<Type> types,LocalDateTime now,int limit,boolean expired) {
        String condition=expired ? "status='PROCESSING' and lease_until<=:now order by lease_until,id"
                : "status='PENDING' and available_at<=:now order by available_at,id";
        return em.createNativeQuery("select * from booking_outbox_events where event_type in (:types) and "+condition+
                " limit "+limit+" for update skip locked",BookingOutboxEvent.class)
                .setParameter("types",types.stream().map(Enum::name).toList()).setParameter("now",now).getResultList();
    }

    public boolean complete(Delivery delivery,LocalDateTime now) { return completeBatch(List.of(delivery),now)==1; }
    public int completeBatch(List<Delivery> events,LocalDateTime now) {
        return update(events,"status='COMPLETED',completed_at=:now,last_error=null,lease_token=null,lease_until=null",now,null);
    }
    public boolean retry(Delivery delivery,LocalDateTime now,Throwable failure) { return retryBatch(List.of(delivery),now,failure)==1; }
    public int retryBatch(List<Delivery> events,LocalDateTime now,Throwable failure) {
        // Attempts may differ after lease recovery. Compute each row's delay in SQL.
        String error=failure.getClass().getSimpleName();
        return update(events,"status='PENDING',available_at=timestampadd(SECOND,least(300,pow(2,least(9,attempts))),:now),"+
                "last_error=:error,lease_token=null,lease_until=null",now,error.substring(0,Math.min(160,error.length())));
    }
    /** Unstarted claims do not consume a retry attempt or wait for lease expiry. */
    public int releaseBatch(List<Delivery> events,LocalDateTime now) {
        return update(events,"status='PENDING',available_at=:now,attempts=greatest(0,attempts-1),lease_token=null,lease_until=null",now,null);
    }
    private int update(List<Delivery> events,String assignment,LocalDateTime now,String error) {
        if(events.isEmpty()) return 0;
        var byToken=new LinkedHashMap<String,List<Long>>();
        events.forEach(e -> byToken.computeIfAbsent(e.leaseToken(),ignored -> new ArrayList<>()).add(e.id()));
        return transaction.execute(status -> {
            int changed=0;
            for(var entry:byToken.entrySet()) {
                var query=em.createNativeQuery("update booking_outbox_events set "+assignment+
                        " where status='PROCESSING' and lease_token=:token and id in (:ids)")
                        .setParameter("token",entry.getKey()).setParameter("ids",entry.getValue()).setParameter("now",now);
                if(error!=null) query.setParameter("error",error);
                changed+=query.executeUpdate();
            }
            return changed;
        });
    }

    public long pendingCount(LocalDateTime now) { return backlog(now).ready(); }
    public Backlog backlog(LocalDateTime now) {
        return transaction.execute(status -> {
            var row=(Object[])em.createNativeQuery("""
                    select coalesce(sum(status='PENDING'),0),
                    coalesce(sum(status='PENDING' and available_at<=:now),0),
                    coalesce(sum(status='PROCESSING'),0),
                    coalesce(sum(status='PROCESSING' and lease_until<=:now),0),
                    coalesce(max(timestampdiff(SECOND,created_at,:now)),0)
                    from booking_outbox_events where status in ('PENDING','PROCESSING')
                    """).setParameter("now",now).getSingleResult();
            return new Backlog(((Number)row[0]).longValue(),((Number)row[1]).longValue(),((Number)row[2]).longValue(),
                    ((Number)row[3]).longValue(),Math.max(0,((Number)row[4]).longValue()));
        });
    }
}
