package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Keeps issued numbers even when the last waiter moves to another zone. */
@Entity
@Table(name = "waiting_zone_sequences")
@Getter
@Setter
public class WaitingZoneSequence {
    @Id
    @Column(length = 60)
    private String id;
    @Column(nullable = false)
    private int lastNumber;
}
