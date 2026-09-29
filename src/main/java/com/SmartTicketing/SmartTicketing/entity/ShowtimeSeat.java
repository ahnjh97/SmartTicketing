package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "showtime_seats",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_showtime_seat",
                        columnNames = {"showtime_id", "seat_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_showtime_seats_reservation",
                        columnList = "reservation_id"
                ),
                @Index(
                        name = "idx_showtime_seats_status_hold",
                        columnList = "showtime_id, status, hold_expired_at"
                )
        }
)
@Data
@NoArgsConstructor
public class ShowtimeSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "hold_expired_at")
    private LocalDateTime holdExpiredAt;
}