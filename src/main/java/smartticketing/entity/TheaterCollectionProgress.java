package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

/** A page is acknowledged only in the transaction that saves its theaters. */
@Entity
@Table(name = "theater_collection_progress")
@Getter @Setter @NoArgsConstructor
public class TheaterCollectionProgress {
    @Id
    @Column(length = 100)
    private String id;
    private int nextPage = 1;
    private boolean complete;
    private LocalDateTime checkedAt;
    @Version
    private Long version;

    public TheaterCollectionProgress(String id) { this.id = id; }
}
