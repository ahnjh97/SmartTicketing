package smartticketing.performace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import smartticketing.entity.ApiOriginalPerformanceResult;
import smartticketing.repository.ApiOriginalPerformanceResultRepository;

import java.util.function.Supplier;

@Component
public class NearbyTheaterPerformance {

    private static final Logger log =
            LoggerFactory.getLogger(NearbyTheaterPerformance.class);

    private static final String STEP = "STEP1_API_ORIGINAL";

    private final ThreadLocal<Metrics> metrics = new ThreadLocal<>();
    private final ApiOriginalPerformanceResultRepository resultRepository;

    public NearbyTheaterPerformance(
            ApiOriginalPerformanceResultRepository resultRepository
    ) {
        this.resultRepository = resultRepository;
    }

    public void start(String address) {
        metrics.set(new Metrics(address));
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
        Metrics current = currentMetrics();
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            current.dbSaveTimeNanos += System.nanoTime() - start;
        }
    }

    public void setTheaterCount(int theaterCount) {
        currentMetrics().theaterCount = theaterCount;
    }

    public void finish() {
        Metrics current = currentMetrics();
        long totalResponseTimeNanos =
                System.nanoTime() - current.totalStartNanos;

        try {
            long apiResponseTimeMs = toMillis(current.apiResponseTimeNanos);
            long dbQueryTimeMs = toMillis(current.dbQueryTimeNanos);
            long dbSaveTimeMs = toMillis(current.dbSaveTimeNanos);
            long totalResponseTimeMs = toMillis(totalResponseTimeNanos);

            log.info(
                    "[NEARBY_THEATER_PERFORMANCE] step={} location={} theaterCount={} theaterSearch={}회 publicTransit={}회 walk={}회 totalApiCalls={}회 apiResponseTime={}ms dbQueryTime={}ms dbSaveTime={}ms totalResponseTime={}ms",
                    STEP,
                    current.address,
                    current.theaterCount,
                    current.theaterSearchCallCount,
                    current.publicTransitCallCount,
                    current.walkCallCount,
                    current.apiCallCount,
                    apiResponseTimeMs,
                    dbQueryTimeMs,
                    dbSaveTimeMs,
                    totalResponseTimeMs
            );

            resultRepository.save(
                    new ApiOriginalPerformanceResult(
                            current.address,
                            current.theaterCount,
                            current.theaterSearchCallCount,
                            current.publicTransitCallCount,
                            current.walkCallCount,
                            current.apiCallCount,
                            apiResponseTimeMs,
                            dbQueryTimeMs,
                            dbSaveTimeMs,
                            totalResponseTimeMs
                    )
            );
        } finally {
            metrics.remove();
        }
    }

    private <T> T measureApi(
            Supplier<T> action,
            ApiType apiType
    ) {
        Metrics current = currentMetrics();
        long start = System.nanoTime();

        try {
            return action.get();
        } finally {
            current.apiResponseTimeNanos +=
                    System.nanoTime() - start;
            current.apiCallCount++;

            switch (apiType) {
                case THEATER_SEARCH -> current.theaterSearchCallCount++;
                case PUBLIC_TRANSIT -> current.publicTransitCallCount++;
                case WALK -> current.walkCallCount++;
            }
        }
    }

    private Metrics currentMetrics() {
        Metrics current = metrics.get();

        if (current == null) {
            throw new IllegalStateException(
                    "성능 측정이 시작되지 않았습니다."
            );
        }

        return current;
    }

    private long toMillis(long nanos) {
        return nanos / 1_000_000;
    }

    private enum ApiType {
        THEATER_SEARCH,
        PUBLIC_TRANSIT,
        WALK
    }

    private static class Metrics {

        private final long totalStartNanos = System.nanoTime();
        private final String address;

        private long apiResponseTimeNanos;
        private long dbQueryTimeNanos;
        private long dbSaveTimeNanos;

        private int apiCallCount;
        private int theaterSearchCallCount;
        private int publicTransitCallCount;
        private int walkCallCount;
        private int theaterCount;

        private Metrics(String address) {
            this.address = address;
        }
    }
}
