package com.SmartTicketing.SmartTicketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name = "user_nearby_theaters",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_nearby_theater",
                        columnNames = {"user_id", "theater_id"}
                ),
                @UniqueConstraint(
                        name = "uk_user_nearby_priority",
                        columnNames = {"user_id", "priority"}
                )
        }
)
@Data
@NoArgsConstructor
public class UserNearbyTheater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(name = "distance_meters", nullable = false, precision = 10, scale = 2)
    private BigDecimal distanceMeters;

    @Column(name = "travel_time_minutes", nullable = false)
    private Integer travelTimeMinutes;

    @Column(nullable = false)
    private Integer priority;
}