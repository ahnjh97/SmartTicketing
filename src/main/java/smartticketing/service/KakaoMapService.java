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
        return findNearbyTheaters(
                null,
                latitude,
                longitude,
                radius
        );
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
            return findNearbyTheatersInternal(
                    latitude,
                    longitude,
                    radius
            );
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
            throw new IllegalArgumentException(
                    "검색 반경은 0~20000m까지 가능합니다."
            );
        }

        Map<String, TheaterBrand> brandQueries =
                Map.of(
                        "CGV",
                        TheaterBrand.CGV,

                        "롯데시네마",
                        TheaterBrand.LOTTE_CINEMA,

                        "메가박스",
                        TheaterBrand.MEGABOX
                );

        Map<String, NearbyPlace> places =
                new LinkedHashMap<>();

        for (
                Map.Entry<String, TheaterBrand> entry :
                brandQueries.entrySet()
        ) {
            List<NearbyPlace> results =
                    performance.measureTheaterSearch(
                            () -> search(
                                    entry.getKey(),
                                    entry.getValue(),
                                    latitude,
                                    longitude,
                                    radius
                            )
                    );

            for (NearbyPlace place : results) {
                places.putIfAbsent(
                        place.kakaoPlaceId(),
                        place
                );
            }
        }

        performance.setTheaterCount(places.size());

        return places.values()
                .stream()
                .sorted(
                        Comparator.comparingInt(
                                NearbyPlace::distance
                        )
                )
                .map(
                        place ->
                                upsert(
                                        place,
                                        latitude,
                                        longitude
                                )
                )
                .sorted(
                        Comparator.comparing(
                                NearbyTheaterResponse::transitMinutes,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
                )
                .limit(15)
                .toList();
    }

    private List<NearbyPlace> search(
            String query,
            TheaterBrand brand,
            double latitude,
            double longitude,
            int radius
    ) {
        Map<String, Object> response =
                restClient.get()
                        .uri(
                                uriBuilder ->
                                        uriBuilder
                                                .path(
                                                        "/v2/local/search/keyword.json"
                                                )
                                                .queryParam(
                                                        "query",
                                                        query
                                                )
                                                .queryParam(
                                                        "category_group_code",
                                                        "CT1"
                                                )
                                                .queryParam(
                                                        "x",
                                                        longitude
                                                )
                                                .queryParam(
                                                        "y",
                                                        latitude
                                                )
                                                .queryParam(
                                                        "radius",
                                                        radius
                                                )
                                                .queryParam(
                                                        "sort",
                                                        "distance"
                                                )
                                                .queryParam(
                                                        "size",
                                                        15
                                                )
                                                .build()
                        )
                        .header(
                                "Authorization",
                                "KakaoAK " + restApiKey
                        )
                        .retrieve()
                        .body(
                                new ParameterizedTypeReference<>() {
                                }
                        );

        if (response == null) {
            return List.of();
        }

        Object documents =
                response.get("documents");

        if (!(documents instanceof List<?> list)) {
            return List.of();
        }

        List<NearbyPlace> result =
                new ArrayList<>();

        for (Object item : list) {

            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String placeName =
                    string(
                            map.get(
                                    "place_name"
                            )
                    );

            if (placeName == null) {
                continue;
            }

            if (!matchesBrand(
                    placeName,
                    brand
            )) {
                continue;
            }

            String placeId =
                    string(
                            map.get("id")
                    );

            if (placeId == null) {
                continue;
            }

            double x =
                    doubleValue(
                            map.get("x")
                    );

            double y =
                    doubleValue(
                            map.get("y")
                    );

            int distance =
                    (int)
                            doubleValue(
                                    map.get(
                                            "distance"
                                    )
                            );

            String roadAddress =
                    string(
                            map.get(
                                    "road_address_name"
                            )
                    );

            String address =
                    roadAddress != null
                            ? roadAddress
                            : string(
                            map.get(
                                    "address_name"
                            )
                    );

            result.add(
                    new NearbyPlace(
                            placeId,
                            placeName,
                            brand,
                            address,
                            y,
                            x,
                            distance,
                            string(
                                    map.get(
                                            "place_url"
                                    )
                            )
                    )
            );
        }

        return result;
    }

    private NearbyTheaterResponse upsert(
            NearbyPlace place,
            double latitude,
            double longitude
    ) {
        Theater theater =
                theaters.findByKakaoPlaceId(
                        place.kakaoPlaceId()
                ).orElseGet(
                        Theater::new
                );

        theater.setBrand(
                place.brand()
        );

        theater.setName(
                place.name()
        );

        theater.setAddress(
                place.address()
        );

        theater.setKakaoPlaceId(
                place.kakaoPlaceId()
        );

        // 카카오에서 받은 영화관 좌표를 극장 기준 데이터에 저장한다.
        // 이후 스마트 예매에서 거리 계산, 위치 기반 추천 등에 재사용한다.
        theater.setLatitude(
                java.math.BigDecimal.valueOf(place.latitude())
        );
        theater.setLongitude(
                java.math.BigDecimal.valueOf(place.longitude())
        );

        theater.setActive(true);

        final Theater theaterToSave = theater;

        theater =
                performance.measureDbSave(
                        () -> theaters.save(theaterToSave)
                );

        RouteInfo transit =
                performance.measurePublicTransit(
                        () -> findPublicTransit(
                                latitude,
                                longitude,
                                place.latitude(),
                                place.longitude()
                        )
                );

        RouteInfo walk =
                performance.measureWalk(
                        () -> findWalk(
                                latitude,
                                longitude,
                                place.latitude(),
                                place.longitude()
                        )
                );

        return new NearbyTheaterResponse(
                theater.getId(),
                theater.getName(),
                theater.getBrand(),
                theater.getAddress(),
                theater.getKakaoPlaceId(),
                place.latitude(),
                place.longitude(),
                place.distance(),
                place.placeUrl(),
                transit.distance(),
                transit.minutes(),
                walk.distance(),
                walk.minutes()
        );
    }

    private RouteInfo findPublicTransit(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude
    ) {
        try {
            Map<String, Object> response =
                    restClient.get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path(
                                                            "/v2/routing/publictraffic"
                                                    )
                                                    .queryParam(
                                                            "start_x",
                                                            startLongitude
                                                    )
                                                    .queryParam(
                                                            "start_y",
                                                            startLatitude
                                                    )
                                                    .queryParam(
                                                            "end_x",
                                                            endLongitude
                                                    )
                                                    .queryParam(
                                                            "end_y",
                                                            endLatitude
                                                    )
                                                    .queryParam(
                                                            "input_coord",
                                                            "WGS84"
                                                    )
                                                    .queryParam(
                                                            "output_coord",
                                                            "WGS84"
                                                    )
                                                    .build()
                            )
                            .header(
                                    "Authorization",
                                    "KakaoAK " + restApiKey
                            )
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<>() {
                                    }
                            );

            if (
                    response == null ||
                            !"OK".equals(
                                    String.valueOf(
                                            response.get("status")
                                    )
                            )) {
                return RouteInfo.empty();
            }

            Object routes =
                    response.get("routes");

            if (!(routes instanceof List<?> list)) {
                return RouteInfo.empty();
            }

            int bestDistance =
                    Integer.MAX_VALUE;

            int bestSeconds =
                    Integer.MAX_VALUE;

            for (Object route : list) {

                if (!(route instanceof Map<?, ?> routeMap)) {
                    continue;
                }

                Object properties =
                        routeMap.get(
                                "properties"
                        );

                if (!(properties instanceof Map<?, ?> propertiesMap)) {
                    continue;
                }

                Integer distance =
                        integerValue(
                                propertiesMap.get(
                                        "totalDistance"
                                )
                        );

                Integer seconds =
                        integerValue(
                                propertiesMap.get(
                                        "totalTime"
                                )
                        );

                if (
                        distance == null ||
                                seconds == null ||
                                distance < 0 ||
                                seconds <= 0
                ) {
                    continue;
                }

                if (
                        seconds <
                                bestSeconds
                ) {
                    bestSeconds =
                            seconds;

                    bestDistance =
                            distance;
                }
            }

            if (
                    bestSeconds ==
                            Integer.MAX_VALUE
            ) {
                return RouteInfo.empty();
            }

            return new RouteInfo(
                    bestDistance,
                    (int)
                            Math.ceil(
                                    bestSeconds / 60.0
                            )
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
            Map<String, Object> response =
                    restClient.get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path(
                                                            "/v2/routing/walk"
                                                    )
                                                    .queryParam(
                                                            "start_x",
                                                            startLongitude
                                                    )
                                                    .queryParam(
                                                            "start_y",
                                                            startLatitude
                                                    )
                                                    .queryParam(
                                                            "end_x",
                                                            endLongitude
                                                    )
                                                    .queryParam(
                                                            "end_y",
                                                            endLatitude
                                                    )
                                                    .queryParam(
                                                            "input_coord",
                                                            "WGS84"
                                                    )
                                                    .queryParam(
                                                            "output_coord",
                                                            "WGS84"
                                                    )
                                                    .queryParam(
                                                            "route_mode",
                                                            "BROAD_FIRST"
                                                    )
                                                    .build()
                            )
                            .header(
                                    "Authorization",
                                    "KakaoAK " + restApiKey
                            )
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<>() {
                                    }
                            );

            if (
                    response == null ||
                            !"OK".equals(
                                    String.valueOf(
                                            response.get("status")
                                    )
                            )) {
                return RouteInfo.empty();
            }

            Object route =
                    response.get("route");

            if (!(route instanceof Map<?, ?> routeMap)) {
                return RouteInfo.empty();
            }

            Object properties =
                    routeMap.get(
                            "properties"
                    );

            if (!(properties instanceof Map<?, ?> propertiesMap)) {
                return RouteInfo.empty();
            }

            Integer distance =
                    integerValue(
                            propertiesMap.get(
                                    "totalDistance"
                            )
                    );

            Integer seconds =
                    integerValue(
                            propertiesMap.get(
                                    "totalTime"
                            )
                    );

            if (
                    distance == null ||
                            seconds == null ||
                            distance < 0 ||
                            seconds <= 0
            ) {
                return RouteInfo.empty();
            }

            return new RouteInfo(
                    distance,
                    (int)
                            Math.ceil(
                                    seconds / 60.0
                            )
            );

        } catch (Exception ignored) {
            return RouteInfo.empty();
        }
    }

    private Integer integerValue(
            Object value
    ) {
        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt(
                    String.valueOf(
                            value
                    )
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean matchesBrand(
            String placeName,
            TheaterBrand brand
    ) {
        return switch (brand) {

            case CGV ->
                    placeName
                            .toUpperCase()
                            .contains("CGV");

            case LOTTE_CINEMA ->
                    placeName.contains(
                            "롯데시네마"
                    );

            case MEGABOX ->
                    placeName.contains(
                            "메가박스"
                    );
        };
    }

    private String string(
            Object value
    ) {
        return value == null
                ? null
                : String.valueOf(
                value
        );
    }

    private double doubleValue(
            Object value
    ) {
        if (value == null) {
            return 0;
        }

        return Double.parseDouble(
                String.valueOf(
                        value
                )
        );
    }

    private record RouteInfo(
            Integer distance,
            Integer minutes
    ) {
        private static RouteInfo empty() {
            return new RouteInfo(
                    null,
                    null
            );
        }
    }

    private record NearbyPlace(
            String kakaoPlaceId,
            String name,
            TheaterBrand brand,
            String address,
            double latitude,
            double longitude,
            int distance,
            String placeUrl
    ) {
    }
}