package smartticketing.entity;

import smartticketing.entity.enums.ShowtimeStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "showtimes",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_showtime_screen_start",
                        columnNames = {"screen_id", "start_time"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_showtimes_movie_start",
                        columnList = "movie_id, start_time"
                ),
                @Index(
                        name = "idx_showtimes_status_start",
                        columnList = "status, start_time"
                )
        }
)
@Data
@NoArgsConstructor
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false)
    private Screen screen;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    // 기존 회차의 가격을 임의로 채우지 않는다. 테스트 생성 회차만 10,000원이다.
    @Column(name = "price_per_person")
    private Integer pricePerPerson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShowtimeStatus status = ShowtimeStatus.SCHEDULED;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
