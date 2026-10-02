package smartticketing.service;

import smartticketing.dto.theater.NearbyTheaterResponse;
import smartticketing.entity.Theater;
import smartticketing.repository.TheaterRepository;
import smartticketing.performace.NearbyTheaterPerformance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Transactional
public class KakaoMapService {

    private static final int WALK_RADIUS_METERS = 2_000;

    private final TheaterRepository theaters;
    private final RestClient restClient;
    private final String restApiKey;
    private final NearbyTheaterPerformance performance;
    private final ExecutorService transitExecutor = Executors.newFixedThreadPool(8);

    public KakaoMapService(
            TheaterRepository theaters,
            @Value("${kakao.map.rest-api-key:}") String restApiKey,
            NearbyTheaterPerformance performance
    ) {
        this.theaters = theaters;
        this.restApiKey = restApiKey;
        this.performance = performance;
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
        performance.start(
                address != null && !address.isBlank()
                        ? address
                        : "좌표 기반 조회 (lat=" + latitude + ", lon=" + longitude + ")"
        );

        try {
            return findNearbyTheatersInternal(latitude, longitude, sort);
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

        // DB에 저장된 활성 영화관 전체를 거리순으로 정렬한 뒤 가까운 20개만 사용한다.
        // 더 이상 10km 반경으로 결과를 잘라내지 않는다.
        List<Theater> theaterList = performance.measureDbQuery(
                theaters::findByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullOrderByNameAsc
        );

        List<TheaterDistance> candidates = theaterList.stream()
                .map(theater -> new TheaterDistance(
                        theater,
                        calculateDistanceMeters(
                                latitude,
                                longitude,
                                theater.getLatitude(),
                                theater.getLongitude()
                        )
                ))
                .sorted(Comparator.comparingInt(TheaterDistance::distanceMeters))
                .limit(20)
                .toList();

        // 기존 WALK 호출과의 호환성을 유지하되 후보는 항상 가까운 20개로 제한한다.
        List<TheaterDistance> selectedCandidates = "WALK".equals(normalizedSort)
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
                    Theater theater = item.theater();
                    RouteInfo walk = RouteInfo.empty();

                    if ("WALK".equals(normalizedSort)) {
                        walk = performance.measureWalk(
                                () -> findWalk(
                                        latitude,
                                        longitude,
                                        toDouble(theater.getLatitude()),
                                        toDouble(theater.getLongitude())
                                )
                        );
                    }

                    return toResponse(item, RouteInfo.empty(), walk);
                })
                .sorted(comparatorFor(normalizedSort))
                .toList();
    }

    private List<NearbyTheaterResponse> findNearbyWithParallelTransit(
            List<TheaterDistance> candidates,
            double latitude,
            double longitude
    ) {
        List<java.util.concurrent.CompletableFuture<TransitResult>> futures =
                candidates.stream()
                        .map(item -> java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> {
                                    long start = System.nanoTime();
                                    Theater theater = item.theater();

                                    RouteInfo route = findPublicTransit(
                                            latitude,
                                            longitude,
                                            toDouble(theater.getLatitude()),
                                            toDouble(theater.getLongitude())
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
            TheaterDistance item,
            RouteInfo transit,
            RouteInfo walk
    ) {
        Theater theater = item.theater();

        return new NearbyTheaterResponse(
                theater.getId(),
                theater.getName(),
                theater.getBrand(),
                theater.getAddress(),
                theater.getKakaoPlaceId(),
                toDouble(theater.getLatitude()),
                toDouble(theater.getLongitude()),
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

    private int calculateDistanceMeters(
            double startLatitude,
            double startLongitude,
            BigDecimal endLatitude,
            BigDecimal endLongitude
    ) {
        double lat1 = Math.toRadians(startLatitude);
        double lon1 = Math.toRadians(startLongitude);
        double lat2 = Math.toRadians(toDouble(endLatitude));
        double lon2 = Math.toRadians(toDouble(endLongitude));

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return (int) Math.round(
                6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        );
    }

    private double toDouble(BigDecimal value) {
        return value.doubleValue();
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

    private record TheaterDistance(
            Theater theater,
            int distanceMeters
    ) {}

    private record TransitResult(
            TheaterDistance item,
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
