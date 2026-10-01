package smartticketing.service;

import smartticketing.dto.theater.NearbyTheaterResponse;
import smartticketing.entity.Theater;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.repository.TheaterRepository;
import smartticketing.performace.NearbyTheaterPerformance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional
public class KakaoMapService {

    private final TheaterRepository theaters;
    private final RestClient restClient;
    private final String restApiKey;
    private final NearbyTheaterPerformance performance;

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

    public List<NearbyTheaterResponse> findNearbyTheaters(
            double latitude,
            double longitude,
            int radius
    ) {
        return findNearbyTheaters(null, latitude, longitude, radius);
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
            String address,
            double latitude,
            double longitude,
            int radius
    ) {
        performance.start(
                address != null && !address.isBlank()
                        ? address
                        : "좌표 기반 조회 (lat=" + latitude + ", lon=" + longitude + ")"
        );

        try {
            return findNearbyTheatersInternal(latitude, longitude, radius);
        } finally {
            performance.finish();
        }
    }

    private List<NearbyTheaterResponse> findNearbyTheatersInternal(
            double latitude,
            double longitude,
            int radius
    ) {
        if (restApiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 지도 연동이 설정되지 않았습니다."
            );
        }

        if (radius < 0 || radius > 20000) {
            throw new IllegalArgumentException("검색 반경은 0~20000m까지 가능합니다.");
        }

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
                .filter(item -> item.distanceMeters() <= radius)
                .sorted(Comparator.comparingInt(TheaterDistance::distanceMeters))
                .limit(15)
                .toList();

        performance.setTheaterCount(candidates.size());

        return candidates.stream()
                .map(item -> {
                    Theater theater = item.theater();

                    RouteInfo transit = performance.measurePublicTransit(
                            () -> findPublicTransit(
                                    latitude,
                                    longitude,
                                    toDouble(theater.getLatitude()),
                                    toDouble(theater.getLongitude())
                            )
                    );

                    RouteInfo walk = performance.measureWalk(
                            () -> findWalk(
                                    latitude,
                                    longitude,
                                    toDouble(theater.getLatitude()),
                                    toDouble(theater.getLongitude())
                            )
                    );

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
                })
                .sorted(Comparator.comparing(
                        NearbyTheaterResponse::transitMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
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

    private record RouteInfo(
            Integer distance,
            Integer minutes
    ) {
        private static RouteInfo empty() {
            return new RouteInfo(null, null);
        }
    }
}
