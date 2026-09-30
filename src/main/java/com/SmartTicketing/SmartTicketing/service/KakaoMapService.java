package com.SmartTicketing.SmartTicketing.service;

import com.SmartTicketing.SmartTicketing.dto.theater.NearbyTheaterResponse;
import com.SmartTicketing.SmartTicketing.entity.Theater;
import com.SmartTicketing.SmartTicketing.entity.enums.TheaterBrand;
import com.SmartTicketing.SmartTicketing.repository.TheaterRepository;
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
    private final RestClient publicTransitClient;
    private final String restApiKey;

    public KakaoMapService(
            TheaterRepository theaters,
            @Value("${kakao.map.rest-api-key:}") String restApiKey
    ) {
        this.theaters = theaters;
        this.restApiKey = restApiKey;

        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .build();

        this.publicTransitClient = RestClient.builder()
                .baseUrl("https://apis-navi.kakaomobility.com")
                .build();
    }

    public List<NearbyTheaterResponse> findNearbyTheaters(
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
                    search(
                            entry.getKey(),
                            entry.getValue(),
                            latitude,
                            longitude,
                            radius
                    );

            for (NearbyPlace place : results) {
                places.putIfAbsent(
                        place.kakaoPlaceId(),
                        place
                );
            }
        }

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

        theater.setActive(true);

        theater =
                theaters.save(
                        theater
                );

        Integer transitMinutes =
                findPublicTransitMinutes(
                        latitude,
                        longitude,
                        place.latitude(),
                        place.longitude()
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
                transitMinutes
        );
    }

    private Integer findPublicTransitMinutes(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude
    ) {
        try {
            Map<String, Object> response =
                    publicTransitClient.get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path(
                                                            "/affiliate/publictransit/v1/multimodal/directions"
                                                    )
                                                    .queryParam(
                                                            "start",
                                                            startLongitude
                                                                    + ","
                                                                    + startLatitude
                                                    )
                                                    .queryParam(
                                                            "goal",
                                                            endLongitude
                                                                    + ","
                                                                    + endLatitude
                                                    )
                                                    .queryParam(
                                                            "route_type",
                                                            "All"
                                                    )
                                                    .build()
                            )
                            .header(
                                    "Authorization",
                                    "KakaoAK " + restApiKey
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .retrieve()
                            .body(
                                    new ParameterizedTypeReference<>() {
                                    }
                            );

            if (response == null) {
                return null;
            }

            Object routes =
                    response.get("routes");

            if (!(routes instanceof List<?> list)) {
                return null;
            }

            int bestSeconds =
                    Integer.MAX_VALUE;

            for (Object route : list) {

                if (!(route instanceof Map<?, ?> routeMap)) {
                    continue;
                }

                Object resultCode =
                        routeMap.get(
                                "result_code"
                        );

                if (
                        resultCode != null &&
                                Integer.parseInt(
                                        String.valueOf(
                                                resultCode
                                        )
                                ) != 0
                ) {
                    continue;
                }

                Object summary =
                        routeMap.get(
                                "summary"
                        );

                if (!(summary instanceof Map<?, ?> summaryMap)) {
                    continue;
                }

                Object duration =
                        summaryMap.get(
                                "duration"
                        );

                if (duration == null) {
                    continue;
                }

                int seconds =
                        Integer.parseInt(
                                String.valueOf(
                                        duration
                                )
                        );

                if (
                        seconds > 0 &&
                                seconds < bestSeconds
                ) {
                    bestSeconds =
                            seconds;
                }
            }

            if (
                    bestSeconds ==
                            Integer.MAX_VALUE
            ) {
                return null;
            }

            return (int)
                    Math.ceil(
                            bestSeconds / 60.0
                    );

        } catch (Exception ignored) {
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