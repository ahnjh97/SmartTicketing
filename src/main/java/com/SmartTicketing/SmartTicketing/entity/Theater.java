package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.TheaterBrand;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "theaters",
        indexes = {
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

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}