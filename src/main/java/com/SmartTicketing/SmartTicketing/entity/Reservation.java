package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.ReservationStatus;
import com.SmartTicketing.SmartTicketing.entity.enums.ReservationType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservations",
        indexes = {
                @Index(
                        name = "idx_reservations_user_created",
                        columnList = "user_id, created_at"
                ),
                @Index(
                        name = "idx_reservations_showtime_status",
                        columnList = "showtime_id, status"
                )
        }
)
@Data
@NoArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "waiting_queue_id", unique = true)
    private WaitingQueue waitingQueue;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_type", nullable = false, length = 20)
    private ReservationType reservationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}