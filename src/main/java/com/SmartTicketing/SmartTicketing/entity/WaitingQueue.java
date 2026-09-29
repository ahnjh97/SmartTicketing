package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.QueueStatus;
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

    @Column(name = "queue_number", nullable = false)
    private Integer queueNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueStatus status = QueueStatus.WAITING;

    @Column(name = "opportunity_expires_at")
    private LocalDateTime opportunityExpiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}