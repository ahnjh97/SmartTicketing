package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "theater_route_cache",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_route_grid_theater",
                        columnNames = {"grid_key", "theater_id"}
                )
        },
        indexes = {
                @Index(name = "idx_route_grid", columnList = "grid_key"),
                @Index(name = "idx_route_theater", columnList = "theater_id")
        }
)
@Data
@NoArgsConstructor
public class TheaterRouteCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grid_key", nullable = false, length = 50)
    private String gridKey;

    @Column(name = "origin_latitude", nullable = false)
    private double originLatitude;

    @Column(name = "origin_longitude", nullable = false)
    private double originLongitude;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(name = "transit_distance_meters")
    private Integer transitDistanceMeters;

    @Column(name = "transit_time_minutes")
    private Integer transitTimeMinutes;

    @Column(name = "walk_distance_meters")
    private Integer walkDistanceMeters;

    @Column(name = "walk_time_minutes")
    private Integer walkTimeMinutes;
}
