package smartticketing.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class NearbyTheaterPerformance {

    private static final Logger log =
            LoggerFactory.getLogger(NearbyTheaterPerformance.class);

    private long totalStartNanos;
    private long apiResponseTimeNanos;
    private long dbSaveTimeNanos;

    private int apiCallCount;
    private int theaterSearchCallCount;
    private int publicTransitCallCount;
    private int walkCallCount;

    private double latitude;
    private double longitude;
    private int theaterCount;

    public void start(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;

        totalStartNanos = System.nanoTime();
        apiResponseTimeNanos = 0;
        dbSaveTimeNanos = 0;

        apiCallCount = 0;
        theaterSearchCallCount = 0;
        publicTransitCallCount = 0;
        walkCallCount = 0;
        theaterCount = 0;
    }

    public <T> T measureTheaterSearch(Supplier<T> action) {
        return measureApi(action, ApiType.THEATER_SEARCH);
    }

    public <T> T measurePublicTransit(Supplier<T> action) {
        return measureApi(action, ApiType.PUBLIC_TRANSIT);
    }

    public <T> T measureWalk(Supplier<T> action) {
        return measureApi(action, ApiType.WALK);
    }

    public <T> T measureDbSave(Supplier<T> action) {
        long start = System.nanoTime();

        try {
            return action.get();
        } finally {
            dbSaveTimeNanos += System.nanoTime() - start;
        }
    }

    public void setTheaterCount(int theaterCount) {
        this.theaterCount = theaterCount;
    }

    public void finish() {
        long totalResponseTimeNanos =
                System.nanoTime() - totalStartNanos;

        log.info(
                """
                [NEARBY_THEATER_PERFORMANCE]
                location=(lat={}, lon={})
                theaterCount={}
                
                Kakao API
                  - theaterSearch={}회
                  - publicTransit={}회
                  - walk={}회
                  - totalApiCalls={}회
                  - apiResponseTime={}ms
                
                DB
                  - dbSaveTime={}ms
                
                TOTAL
                  - totalResponseTime={}ms
                """,
                latitude,
                longitude,
                theaterCount,
                theaterSearchCallCount,
                publicTransitCallCount,
                walkCallCount,
                apiCallCount,
                toMillis(apiResponseTimeNanos),
                toMillis(dbSaveTimeNanos),
                toMillis(totalResponseTimeNanos)
        );
    }

    private <T> T measureApi(
            Supplier<T> action,
            ApiType apiType
    ) {
        long start = System.nanoTime();

        try {
            return action.get();
        } finally {
            apiResponseTimeNanos += System.nanoTime() - start;
            apiCallCount++;

            switch (apiType) {
                case THEATER_SEARCH -> theaterSearchCallCount++;
                case PUBLIC_TRANSIT -> publicTransitCallCount++;
                case WALK -> walkCallCount++;
            }
        }
    }

    private long toMillis(long nanos) {
        return nanos / 1_000_000;
    }

    private enum ApiType {
        THEATER_SEARCH,
        PUBLIC_TRANSIT,
        WALK
    }
}
