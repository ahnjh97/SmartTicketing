package smartticketing.entity;

import smartticketing.entity.enums.QueueStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "waiting_queues",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_waiting_queue_showtime_number",
                        columnNames = {"showtime_id", "queue_number"}
                ),
                @UniqueConstraint(
                        name = "uk_waiting_queue_group_showtime",
                        columnNames = {"request_group_id", "showtime_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_waiting_queues_status_number",
                        columnList = "showtime_id, status, queue_number"
                ),
                @Index(
                        name = "idx_waiting_queues_user_created",
                        columnList = "user_id, created_at"
                )
        }
)
@Data
@NoArgsConstructor
public class WaitingQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;

    // 인원·선호조건은 그룹의 신청 당시 스냅샷을 공유한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_group_id")
    private BookingRequestGroup requestGroup;

    @Column(name = "queue_number", nullable = false)
    private Integer queueNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private QueueStatus status = QueueStatus.WAITING;

    @Column(name = "opportunity_expires_at")
    private LocalDateTime opportunityExpiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
