package smartticketing.entity;

import jakarta.persistence.*;

/** Request mutex, separate from the user FK locks taken by seat allocation. */
@Entity
@Table(name = "booking_user_limits")
public class BookingUserLimit {
    @Id
    @Column(name = "user_id")
    private Long userId;
}
