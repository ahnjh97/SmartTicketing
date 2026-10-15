package smartticketing.entity;

import smartticketing.entity.enums.ReservationStatus;
import smartticketing.entity.enums.ReservationType;
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

    // 기존 예약에는 연결을 강제하지 않는다. 신규 예매 서비스는 반드시 설정한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_group_id")
    private BookingRequestGroup requestGroup;

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
