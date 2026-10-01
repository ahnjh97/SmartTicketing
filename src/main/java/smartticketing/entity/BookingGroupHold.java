package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 그룹 PK로 활성 선점 슬롯을 하나로 제한한다. 만료/결제 시 서비스가 삭제한다. */
@Entity
@Table(name = "booking_group_holds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_booking_group_hold_reservation", columnNames = "reservation_id")
})
@Getter
@Setter
@NoArgsConstructor
public class BookingGroupHold {
    @Id
    @Column(name = "group_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private BookingRequestGroup requestGroup;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
