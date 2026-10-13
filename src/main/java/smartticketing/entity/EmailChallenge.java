package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Getter
@Setter
public class EmailChallenge {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false) private String email;
    private String purpose;
    private String codeHash;
    private String grantHash;
    private Instant expiresAt;
    private Instant sentAt;
    private Instant windowStart;
    private int sendCount;
    private int attempts;
}
