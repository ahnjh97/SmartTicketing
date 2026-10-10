package smartticketing.entity;

import smartticketing.entity.enums.TheaterBrand;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name = "theaters",
        indexes = {
                @Index(name = "idx_theaters_active_name", columnList = "is_active, name, id"),
                @Index(name = "idx_theaters_active_brand_name", columnList = "is_active, brand, name, id"),
                @Index(
                        name = "idx_theaters_brand_name",
                        columnList = "brand, name"
                )
        }
)
@Data
@NoArgsConstructor
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TheaterBrand brand;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(name = "kakao_place_id", nullable = false, unique = true, length = 255)
    private String kakaoPlaceId;

    // 기존 극장은 좌표를 알 수 없으므로 null로 유지한다.
    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
