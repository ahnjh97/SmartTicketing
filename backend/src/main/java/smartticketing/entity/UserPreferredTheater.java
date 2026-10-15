package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "user_preferred_theaters",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_preferred_theater",
                        columnNames = {"user_id", "theater_id"}
                ),
                @UniqueConstraint(
                        name = "uk_user_preferred_theater_priority",
                        columnNames = {"user_id", "priority"}
                )
        }
)
@Data
@NoArgsConstructor
public class UserPreferredTheater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(nullable = false)
    private Integer priority;
}