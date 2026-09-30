package com.SmartTicketing.SmartTicketing.entity;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "seats",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_seat_screen_row_number",
                        columnNames = {"screen_id", "seat_row", "seat_number"}
                )
        }
)
@Data
@NoArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screen_id", nullable = false)
    private Screen screen;

    @Column(name = "seat_row", nullable = false, length = 10)
    private String seatRow;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_position", nullable = false, length = 30)
    private SeatPosition seatPosition;

    // 동일 행에서도 통로가 다르면 다른 구간이다. null은 배치 미확인 상태다.
    @Column(name = "adjacency_segment", length = 40)
    private String adjacencySegment;

    @Column(name = "position_in_segment")
    private Integer positionInSegment;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
