package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "user_preferred_seats",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_preferred_seat_priority",
                        columnNames = {"user_id", "priority"}
                )
        }
)
@Data
@NoArgsConstructor
public class UserPreferredSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_position", nullable = false, length = 30)
    private SeatPosition seatPosition;

    @Column(nullable = false)
    private Integer priority;
}