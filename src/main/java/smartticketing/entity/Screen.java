package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "screens",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_screen_theater_name",
                        columnNames = {"theater_id", "name"}
                ),
                @UniqueConstraint(
                        name = "uk_screen_theater_seed_key",
                        columnNames = {"theater_id", "seed_key"}
                )
        }
)
@Data
@NoArgsConstructor
public class Screen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false)
    private Theater theater;

    @Column(nullable = false, length = 100)
    private String name;

    // 이름이 같더라도 이 표식이 없는 기존 상영관은 초기화 대상에서 제외한다.
    @Column(name = "seed_key", length = 80)
    private String seedKey;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
