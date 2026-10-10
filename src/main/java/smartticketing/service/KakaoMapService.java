package smartticketing.service;

import smartticketing.dto.theater.NearbyTheaterResponse;
import smartticketing.service.NearbyTheaterQuery.Match;
import smartticketing.performace.NearbyTheaterPerformance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.core.type.TypeReference;

@Service
@Transactional
public class KakaoMapService {

    private static final int WALK_RADIUS_METERS = 2_000;

    private final NearbyTheaterQuery theaters;
    private final RestClient restClient;
    private final String restApiKey;
    private final NearbyTheaterPerformance performance;
    private final RedisQueryCache queryCache;
    private final ExecutorService transitExecutor = Executors.newFixedThreadPool(8);

    public KakaoMapService(
            NearbyTheaterQuery theaters,
            @Value("${kakao.map.rest-api-key:}") String restApiKey,
            NearbyTheaterPerformance performance,
            RedisQueryCache queryCache
    ) {
        this.theaters = theaters;
        this.restApiKey = restApiKey;
        this.performance = performance;
        this.queryCache = queryCache;
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .build();
    }

    @PreDestroy
    public void shutdownTransitExecutor() {
        transitExecutor.shutdown();
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
            double latitude,
            double longitude,
            int radius
    ) {
        return findNearbyTheaters(null, latitude, longitude, radius, "DISTANCE");
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
            String address,
            double latitude,
            double longitude
    ) {
        return findNearbyTheaters(address, latitude, longitude, 0, "DISTANCE");
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
            String address,
            double latitude,
            double longitude,
            int radius
    ) {
        return findNearbyTheaters(address, latitude, longitude, radius, "DISTANCE");
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
            String address,
            double latitude,
            double longitude,
            int radius,
            String sort
    ) {
        NearbyTheaterQuery.validateCoordinates(latitude, longitude);
        String normalizedSort = normalizeSort(sort);
        String cacheKey = String.format(Locale.ROOT, "spatial-v1:%.6f:%.6f:%s", latitude, longitude, normalizedSort);
        if ("DISTANCE".equals(normalizedSort)) {
            List<NearbyTheaterResponse> cached = queryCache.get("nearby-distance", cacheKey,
                    new TypeReference<List<NearbyTheaterResponse>>() {});
            if (cached != null) return cached;
        }

        performance.start(
                address != null && !address.isBlank()
                        ? address
                        : "좌표 기반 조회 (lat=" + latitude + ", lon=" + longitude + ")"
        );

        try {
            List<NearbyTheaterResponse> response = findNearbyTheatersInternal(latitude, longitude, normalizedSort);
            if ("DISTANCE".equals(normalizedSort)) queryCache.put("nearby-distance", cacheKey, response);
            return response;
        } finally {
            performance.finish();
        }
    }

    private List<NearbyTheaterResponse> findNearbyTheatersInternal(
            double latitude,
            double longitude,
            String sort
    ) {
        String normalizedSort = normalizeSort(sort);

        if (!"DISTANCE".equals(normalizedSort) && restApiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 지도 연동이 설정되지 않았습니다."
            );
        }

        List<Match> candidates = performance.measureDbQuery(() -> theaters.findNearest(latitude, longitude));

        // 기존 WALK 호출과의 호환성을 유지하되 후보는 항상 가까운 12개로 제한한다.
        List<Match> selectedCandidates = "WALK".equals(normalizedSort)
                ? candidates.stream()
                        .filter(item -> item.distanceMeters() <= WALK_RADIUS_METERS)
                        .toList()
                : candidates;

        performance.setTheaterCount(selectedCandidates.size());

        if ("TRANSIT".equals(normalizedSort)) {
            return findNearbyWithParallelTransit(
                    selectedCandidates,
                    latitude,
                    longitude
            );
        }

        return selectedCandidates.stream()
                .map(item -> {
                    RouteInfo walk = RouteInfo.empty();

                    if ("WALK".equals(normalizedSort)) {
                        walk = performance.measureWalk(
                                () -> findWalk(
                                        latitude,
                                        longitude,
                                        item.latitude(),
                                        item.longitude()
                                )
                        );
                    }

                    return toResponse(item, RouteInfo.empty(), walk);
                })
                .sorted(comparatorFor(normalizedSort))
                .toList();
    }

    private List<NearbyTheaterResponse> findNearbyWithParallelTransit(
            List<Match> candidates,
            double latitude,
            double longitude
    ) {
        List<java.util.concurrent.CompletableFuture<TransitResult>> futures =
                candidates.stream()
                        .map(item -> java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> {
                                    long start = System.nanoTime();

                                    RouteInfo route = findPublicTransit(
                                            latitude,
                                            longitude,
                                            item.latitude(),
                                            item.longitude()
                                    );

                                    return new TransitResult(
                                            item,
                                            route,
                                            System.nanoTime() - start
                                    );
                                },
                                transitExecutor
                        ))
                        .toList();

        List<TransitResult> results = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .toList();

        long totalApiTimeNanos = results.stream()
                .mapToLong(TransitResult::elapsedNanos)
                .sum();

        performance.recordPublicTransitBatch(
                results.size(),
                totalApiTimeNanos
        );

        return results.stream()
                .map(result -> toResponse(
                        result.item(),
                        result.route(),
                        RouteInfo.empty()
                ))
                .sorted(comparatorFor("TRANSIT"))
                .toList();
    }

    private NearbyTheaterResponse toResponse(
            Match item,
            RouteInfo transit,
            RouteInfo walk
    ) {
        return new NearbyTheaterResponse(
                item.id(),
                item.name(),
                item.brand(),
                item.address(),
                item.kakaoPlaceId(),
                item.latitude(),
                item.longitude(),
                item.distanceMeters(),
                null,
                transit.distance(),
                transit.minutes(),
                walk.distance(),
                walk.minutes()
        );
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "DISTANCE";
        }

        return switch (sort.trim().toUpperCase(Locale.ROOT)) {
            case "DISTANCE", "TRANSIT", "WALK" -> sort.trim().toUpperCase(Locale.ROOT);
            default -> throw new IllegalArgumentException(
                    "정렬 기준은 DISTANCE, TRANSIT, WALK 중 하나여야 합니다."
            );
        };
    }

    private Comparator<NearbyTheaterResponse> comparatorFor(String sort) {
        return switch (sort) {
            case "TRANSIT" -> Comparator.comparing(
                    NearbyTheaterResponse::transitMinutes,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
            case "WALK" -> Comparator.comparing(
                    NearbyTheaterResponse::walkMinutes,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
            default -> Comparator.comparingInt(NearbyTheaterResponse::distance);
        };
    }

    private RouteInfo findPublicTransit(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude
    ) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/routing/publictraffic")
                            .queryParam("start_x", startLongitude)
                            .queryParam("start_y", startLatitude)
                            .queryParam("end_x", endLongitude)
                            .queryParam("end_y", endLatitude)
                            .queryParam("input_coord", "WGS84")
                            .queryParam("output_coord", "WGS84")
                            .build())
                    .header("Authorization", "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !"OK".equals(String.valueOf(response.get("status")))) {
                return RouteInfo.empty();
            }

            Object routes = response.get("routes");
            if (!(routes instanceof List<?> list)) {
                return RouteInfo.empty();
            }

            int bestDistance = Integer.MAX_VALUE;
            int bestSeconds = Integer.MAX_VALUE;

            for (Object route : list) {
                if (!(route instanceof Map<?, ?> routeMap)) {
                    continue;
                }

                Object properties = routeMap.get("properties");
                if (!(properties instanceof Map<?, ?> propertiesMap)) {
                    continue;
                }

                Integer distance = integerValue(propertiesMap.get("totalDistance"));
                Integer seconds = integerValue(propertiesMap.get("totalTime"));

                if (distance == null || seconds == null || distance < 0 || seconds <= 0) {
                    continue;
                }

                if (seconds < bestSeconds) {
                    bestSeconds = seconds;
                    bestDistance = distance;
                }
            }

            if (bestSeconds == Integer.MAX_VALUE) {
                return RouteInfo.empty();
            }

            return new RouteInfo(
                    bestDistance,
                    (int) Math.ceil(bestSeconds / 60.0)
            );
        } catch (Exception ignored) {
            return RouteInfo.empty();
        }
    }

    private RouteInfo findWalk(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude
    ) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/routing/walk")
                            .queryParam("start_x", startLongitude)
                            .queryParam("start_y", startLatitude)
                            .queryParam("end_x", endLongitude)
                            .queryParam("end_y", endLatitude)
                            .queryParam("input_coord", "WGS84")
                            .queryParam("output_coord", "WGS84")
                            .queryParam("route_mode", "BROAD_FIRST")
                            .build())
                    .header("Authorization", "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !"OK".equals(String.valueOf(response.get("status")))) {
                return RouteInfo.empty();
            }

            Object route = response.get("route");
            if (!(route instanceof Map<?, ?> routeMap)) {
                return RouteInfo.empty();
            }

            Object properties = routeMap.get("properties");
            if (!(properties instanceof Map<?, ?> propertiesMap)) {
                return RouteInfo.empty();
            }

            Integer distance = integerValue(propertiesMap.get("totalDistance"));
            Integer seconds = integerValue(propertiesMap.get("totalTime"));

            if (distance == null || seconds == null || distance < 0 || seconds <= 0) {
                return RouteInfo.empty();
            }

            return new RouteInfo(
                    distance,
                    (int) Math.ceil(seconds / 60.0)
            );
        } catch (Exception ignored) {
            return RouteInfo.empty();
        }
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record TransitResult(
            Match item,
            RouteInfo route,
            long elapsedNanos
    ) {}

    private record RouteInfo(
            Integer distance,
            Integer minutes
    ) {
        private static RouteInfo empty() {
            return new RouteInfo(null, null);
        }
    }
}
