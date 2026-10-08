package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import smartticketing.entity.BookingOutboxEvent;
import smartticketing.entity.BookingOutboxEvent.Type;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Append using the caller's existing transaction, after its domain locks. Never REQUIRES_NEW. */
public final class BookingOutbox {
    private BookingOutbox() {}
    private static final String NEXT_VERSION = """
            insert into booking_outbox_streams (showtime_id,revision) values (:show,1)
            on duplicate key update revision=revision+1
            """;
    private static final String VERSION = "select revision from booking_outbox_streams where showtime_id=:show for update";

    public static void append(EntityManager em, Long show, Type type, Long group, String reason, LocalDateTime now) {
        if (!em.isJoinedToTransaction()) throw new IllegalStateException("Outbox requires the domain transaction");
        validate(show,type,reason,now);
        em.createNativeQuery(NEXT_VERSION).setParameter("show",show).executeUpdate();
        long version = ((Number)em.createNativeQuery(VERSION).setParameter("show",show).getSingleResult()).longValue();
        var event = new BookingOutboxEvent();
        event.setShowtimeId(show); event.setAggregateVersion(version); event.setEventType(type);
        event.setGroupId(group); event.setReason(reason); event.setCreatedAt(now); event.setAvailableAt(now);
        em.persist(event);
    }

    /** JDBC maintenance uses the same DataSource transaction as its destructive SQL. */
    static void append(NamedParameterJdbcTemplate db, Long show, Type type, String reason, LocalDateTime now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox requires the maintenance transaction");
        validate(show,type,reason,now);
        Map<String,Object> p = new HashMap<>();
        p.put("show",show); p.put("type",type.name()); p.put("reason",reason); p.put("now",now);
        db.update(NEXT_VERSION,p);
        p.put("version",db.queryForObject(VERSION,p,Long.class));
        db.update("""
                insert into booking_outbox_events
                (showtime_id,aggregate_version,schema_version,event_type,reason,status,attempts,created_at,available_at)
                values (:show,:version,1,:type,:reason,'PENDING',0,:now,:now)
                """,p);
    }

    private static void validate(Long show, Type type, String reason, LocalDateTime now) {
        if (show == null || show < 1 || type == null || reason == null || reason.isBlank() || reason.length()>64 || now == null)
            throw new IllegalArgumentException("Invalid outbox event");
    }
}
