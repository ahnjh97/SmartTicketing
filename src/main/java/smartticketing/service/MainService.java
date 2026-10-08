package smartticketing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.dto.movie.MainChartResponseDto;
import smartticketing.dto.movie.MovieChartResponseDto;
import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Transactional(readOnly = true)
public class MainService {

    private static final Logger log = LoggerFactory.getLogger(MainService.class);
    private static final long SLOW_REQUEST_MS = 50L;

    private final MovieRepository movieRepository;
    private final String referenceDate;
    private final RedisQueryCache queryCache;
    private final ConcurrentHashMap<String, CompletableFuture<MainChartResponseDto>> loadingCache =
            new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong loaderCount = new AtomicLong();
    private final AtomicLong joinCount = new AtomicLong();

    public MainService(
            MovieRepository movieRepository,
            @Value("${app.reference-date:}") String referenceDate,
            RedisQueryCache queryCache) {
        this.movieRepository = movieRepository;
        this.referenceDate = referenceDate;
        this.queryCache = queryCache;
    }

    public MainChartResponseDto getMainChart() {
        long requestStart = System.nanoTime();
        LocalDate baseDate = getBaseDate();
        String cacheKey = baseDate.toString();

        MainChartResponseDto cached = queryCache.get("main", cacheKey, MainChartResponseDto.class);
        if (cached != null) {
            hitCount.incrementAndGet();
            logProgress();
            logSlowRequest("HIT", requestStart, 0L, 0L);
            return cached;
        }

        missCount.incrementAndGet();

        CompletableFuture<MainChartResponseDto> newFuture = new CompletableFuture<>();
        CompletableFuture<MainChartResponseDto> existingFuture =
                loadingCache.putIfAbsent(cacheKey, newFuture);

        if (existingFuture != null) {
            joinCount.incrementAndGet();
            long joinStart = System.nanoTime();
            MainChartResponseDto result = existingFuture.join();
            long joinMs = elapsedMs(joinStart);
            logSlowJoin(joinMs);
            logProgress();
            logSlowRequest("JOIN", requestStart, joinMs, 0L);
            return result;
        }

        loaderCount.incrementAndGet();
        try {
            // Another request may have populated Redis between the initial GET and
            // becoming the loader for this cache key.
            long secondGetStart = System.nanoTime();
            cached = queryCache.get("main", cacheKey, MainChartResponseDto.class);
            long secondGetMs = elapsedMs(secondGetStart);
            if (cached != null) {
                hitCount.incrementAndGet();
                newFuture.complete(cached);
                logProgress();
                logSlowRequest("LOADER_SECOND_HIT", requestStart, 0L, secondGetMs);
                return cached;
            }

            long dbStart = System.nanoTime();
            long totalAudience = movieRepository.sumActiveAudienceCount();

            List<MovieChartResponseDto> nowShowing = toChart(
                    movieRepository.findTop10ByActiveTrueAndReleaseDateLessThanEqualOrderByAudienceCountDescReleaseDateDesc(baseDate),
                    totalAudience);
            List<MovieChartResponseDto> comingSoon = toChart(
                    movieRepository.findTop10ByActiveTrueAndReleaseDateAfterOrderByReleaseDateAscTitleAsc(baseDate),
                    totalAudience);
            long dbMs = elapsedMs(dbStart);

            MainChartResponseDto response = new MainChartResponseDto(nowShowing, comingSoon);

            long putStart = System.nanoTime();
            queryCache.put("main", cacheKey, response);
            long putMs = elapsedMs(putStart);

            newFuture.complete(response);

            logLoaderTiming(dbMs, putMs, secondGetMs);
            logProgress();
            logSlowRequest("LOAD", requestStart, 0L, dbMs + putMs);
            return response;
        } catch (RuntimeException e) {
            newFuture.completeExceptionally(e);
            throw e;
        } finally {
            loadingCache.remove(cacheKey, newFuture);
        }
    }

    private void logLoaderTiming(long dbMs, long putMs, long secondGetMs) {
        if (dbMs >= SLOW_REQUEST_MS || putMs >= SLOW_REQUEST_MS || secondGetMs >= SLOW_REQUEST_MS) {
            log.warn("MAIN_CACHE_LOADER db={}ms redisSecondGet={}ms redisPut={}ms", dbMs, secondGetMs, putMs);
        }
    }

    private void logSlowJoin(long joinMs) {
        if (joinMs >= SLOW_REQUEST_MS) {
            log.warn("MAIN_CACHE_JOIN_WAIT {}ms", joinMs);
        }
    }

    private void logSlowRequest(String path, long requestStart, long joinMs, long backendMs) {
        long totalMs = elapsedMs(requestStart);
        if (totalMs >= SLOW_REQUEST_MS) {
            log.warn("MAIN_REQUEST path={} total={}ms join={}ms backend={}ms", path, totalMs, joinMs, backendMs);
        }
    }

    private void logProgress() {
        long count = requestCount.incrementAndGet();
        if (count % 100 == 0) {
            log.info("MAIN_CACHE_STATS requests={} hits={} misses={} loaders={} joins={}",
                    count, hitCount.get(), missCount.get(), loaderCount.get(), joinCount.get());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // 설정된 기준일이 있으면 그 날짜, 없으면 오늘(한국 시간)
    private LocalDate getBaseDate() {
        return referenceDate.isBlank()
                ? LocalDate.now(ZoneId.of("Asia/Seoul"))
                : LocalDate.parse(referenceDate);
    }

    private List<MovieChartResponseDto> toChart(List<Movie> movies, long totalAudience) {
        return movies.stream()
                .map(movie -> MovieChartResponseDto.from(movie, calculateBookingRate(movie.getAudienceCount(), totalAudience)))
                .toList();
    }

    // 예매율 = 이 영화 누적관객수 ÷ 전체 누적관객수 × 100 (소수점 첫째 자리)
    private double calculateBookingRate(long audienceCount, long totalAudience) {
        if (totalAudience == 0) {
            return 0.0;
        }
        return Math.round(audienceCount * 1000.0 / totalAudience) / 10.0;
    }
}