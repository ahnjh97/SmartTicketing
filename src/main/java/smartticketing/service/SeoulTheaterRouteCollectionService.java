package smartticketing.service;

import smartticketing.entity.Theater;
import smartticketing.entity.TheaterRouteCache;
import smartticketing.repository.TheaterRepository;
import smartticketing.repository.TheaterRouteCacheRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@Transactional
public class SeoulTheaterRouteCollectionService {

    private static final double SEOUL_MIN_LATITUDE = 37.4133;
    private static final double SEOUL_MAX_LATITUDE = 37.7151;
    private static final double SEOUL_MIN_LONGITUDE = 126.7341;
    private static final double SEOUL_MAX_LONGITUDE = 127.2693;

    private final TheaterRepository theaters;
    private final TheaterRouteCacheRepository routeCaches;
    private final RestClient restClient;
    private final String restApiKey;

    public SeoulTheaterRouteCollectionService(
            TheaterRepository theaters,
            TheaterRouteCacheRepository routeCaches,
            @Value("${kakao.map.rest-api-key:}") String restApiKey
    ) {
        this.theaters = theaters;
        this.routeCaches = routeCaches;
        this.restApiKey = restApiKey;
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .build();
    }

    public Map<String, Object> collectSeoulRoutes(double gridStepKm, int maxGridPoints) {
        if (restApiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 지도 REST API 키가 설정되지 않았습니다."
            );
        }

        if (gridStepKm < 1.0 || gridStepKm > 10.0) {
            throw new IllegalArgumentException("gridStepKm은 1~10km 범위여야 합니다.");
        }

        if (maxGridPoints < 1 || maxGridPoints > 500) {
            throw new IllegalArgumentException("maxGridPoints는 1~500 범위여야 합니다.");
        }

        List<Theater> theaterList = theaters.findByActiveTrueOrderByNameAsc();
        List<GridPoint> gridPoints = createGrid(gridStepKm, maxGridPoints);

        long startedAt = System.currentTimeMillis();
        int transitApiCalls = 0;
        int walkApiCalls = 0;
        int insertedCount = 0;
        int updatedCount = 0;
        int failedRouteCount = 0;

        for (GridPoint grid : gridPoints) {
            for (Theater theater : theaterList) {
                Optional<TheaterRouteCache> existing =
                        routeCaches.findByGridKeyAndTheaterId(grid.key(), theater.getId());

                TheaterRouteCache cache =
                        existing.orElseGet(TheaterRouteCache::new);

                if (existing.isPresent()) {
                    updatedCount++;
                } else {
                    insertedCount++;
                }

                cache.setGridKey(grid.key());
                cache.setOriginLatitude(grid.latitude());
                cache.setOriginLongitude(grid.longitude());
                cache.setTheater(theater);

                RouteInfo transit = findPublicTransit(
                        grid.latitude(),
                        grid.longitude(),
                        theater.getLatitude(),
                        theater.getLongitude()
                );
                transitApiCalls++;

                RouteInfo walk = findWalk(
                        grid.latitude(),
                        grid.longitude(),
                        theater.getLatitude(),
                        theater.getLongitude()
                );
                walkApiCalls++;

                cache.setTransitDistanceMeters(transit.distanceMeters());
                cache.setTransitTimeMinutes(transit.minutes());
                cache.setWalkDistanceMeters(walk.distanceMeters());
                cache.setWalkTimeMinutes(walk.minutes());

                if (transit.minutes() == null && walk.minutes() == null) {
                    failedRouteCount++;
                }

                routeCaches.save(cache);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gridStepKm", gridStepKm);
        result.put("gridPointCount", gridPoints.size());
        result.put("theaterCount", theaterList.size());
        result.put("transitApiCalls", transitApiCalls);
        result.put("walkApiCalls", walkApiCalls);
        result.put("totalApiCalls", transitApiCalls + walkApiCalls);
        result.put("insertedCount", insertedCount);
        result.put("updatedCount", updatedCount);
        result.put("failedRouteCount", failedRouteCount);
        result.put("elapsedMs", elapsedMs);
        return result;
    }

    private List<GridPoint> createGrid(double gridStepKm, int maxGridPoints) {
        double latStep = gridStepKm / 111.0;
        double lonStep = gridStepKm / 88.0;

        List<GridPoint> points = new ArrayList<>();

        int row = 0;
        for (double lat = SEOUL_MIN_LATITUDE; lat <= SEOUL_MAX_LATITUDE; lat += latStep) {
            int col = 0;
            for (double lon = SEOUL_MIN_LONGITUDE; lon <= SEOUL_MAX_LONGITUDE; lon += lonStep) {
                points.add(new GridPoint(
                        "SEOUL-" + row + "-" + col,
                        round(lat),
                        round(lon)
                ));
                col++;
                if (points.size() >= maxGridPoints) {
                    return points;
                }
            }
            row++;
        }

        return points;
    }

    private RouteInfo findPublicTransit(
            double startLatitude,
            double startLongitude,
            Double endLatitude,
            Double endLongitude
    ) {
        if (endLatitude == null || endLongitude == null) {
            return RouteInfo.empty();
        }

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
            Double endLatitude,
            Double endLongitude
    ) {
        if (endLatitude == null || endLongitude == null) {
            return RouteInfo.empty();
        }

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

    private double round(double value) {
        return Math.round(value * 10000000d) / 10000000d;
    }

    private record GridPoint(
            String key,
            double latitude,
            double longitude
    ) {}

    private record RouteInfo(
            Integer distanceMeters,
            Integer minutes
    ) {
        private static RouteInfo empty() {
            return new RouteInfo(null, null);
        }
    }
}
