package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** Durable invalidation envelope. Consumers read authoritative DB state, not a partial delta. */
@Entity
@Table(name = "booking_outbox_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_outbox_show_version", columnNames = {"showtime_id", "aggregate_version"})
}, indexes = {
        @Index(name = "idx_outbox_pending", columnList = "status, available_at, id"),
        @Index(name = "idx_outbox_lease", columnList = "status, lease_until, id")
})
@Getter
@Setter
@NoArgsConstructor
public class BookingOutboxEvent {
    public enum Type { WAITING_CHANGED, BOOKING_CHANGED, SHOWTIME_CHANGED }
    public enum Status { PENDING, PROCESSING, COMPLETED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "showtime_id", nullable = false, updatable = false)
    private Long showtimeId;
    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = 1;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private Type eventType;
    @Column(nullable = false, updatable = false, length = 64)
    private String reason;
    @Column(name = "group_id", updatable = false)
    private Long groupId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;
    @Column(name = "lease_token", length = 36)
    private String leaseToken;
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    @Column(name = "last_error", length = 160)
    private String lastError;
}
