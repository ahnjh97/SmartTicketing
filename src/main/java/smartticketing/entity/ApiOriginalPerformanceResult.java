package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "nearby_theater_performance_api_original")
@Getter
@NoArgsConstructor
public class ApiOriginalPerformanceResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String location;

    @Column(nullable = false)
    private int theaterCount;

    @Column(nullable = false)
    private int theaterSearchCalls;

    @Column(nullable = false)
    private int publicTransitCalls;

    @Column(nullable = false)
    private int walkCalls;

    @Column(nullable = false)
    private int totalApiCalls;

    @Column(nullable = false)
    private long apiResponseTimeMs;

    @Column(nullable = false)
    private long dbQueryTimeMs;

    @Column(nullable = false)
    private long dbSaveTimeMs;

    @Column(nullable = false)
    private long totalResponseTimeMs;

    @Column(nullable = false)
    private LocalDateTime measuredAt;

    public ApiOriginalPerformanceResult(
            String location,
            int theaterCount,
            int theaterSearchCalls,
            int publicTransitCalls,
            int walkCalls,
            int totalApiCalls,
            long apiResponseTimeMs,
            long dbQueryTimeMs,
            long dbSaveTimeMs,
            long totalResponseTimeMs
    ) {
        this.location = location;
        this.theaterCount = theaterCount;
        this.theaterSearchCalls = theaterSearchCalls;
        this.publicTransitCalls = publicTransitCalls;
        this.walkCalls = walkCalls;
        this.totalApiCalls = totalApiCalls;
        this.apiResponseTimeMs = apiResponseTimeMs;
        this.dbQueryTimeMs = dbQueryTimeMs;
        this.dbSaveTimeMs = dbSaveTimeMs;
        this.totalResponseTimeMs = totalResponseTimeMs;
        this.measuredAt = LocalDateTime.now();
    }
}
