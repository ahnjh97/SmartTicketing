package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** No catalog FK: versions survive show deletion and cannot restart at one. */
@Entity
@Table(name = "booking_outbox_streams")
@Getter
@NoArgsConstructor
public class BookingOutboxStream {
    @Id
    @Column(name = "showtime_id")
    private Long showtimeId;
    @Column(nullable = false)
    private long revision;
}
