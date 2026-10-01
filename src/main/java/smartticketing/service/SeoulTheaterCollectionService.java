package smartticketing.service;

import smartticketing.entity.Theater;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.repository.TheaterRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class SeoulTheaterCollectionService {

    private static final List<String> SEOUL_DISTRICTS = List.of(
            "강남구", "강동구", "강북구", "강서구", "관악구",
            "광진구", "구로구", "금천구", "노원구", "도봉구",
            "동대문구", "동작구", "마포구", "서대문구", "서초구",
            "성동구", "성북구", "송파구", "양천구", "영등포구",
            "용산구", "은평구", "종로구", "중구", "중랑구"
    );

    private final TheaterRepository theaters;
    private final RestClient restClient;
    private final String restApiKey;

    public SeoulTheaterCollectionService(
            TheaterRepository theaters,
            @Value("${kakao.map.rest-api-key:}") String restApiKey
    ) {
        this.theaters = theaters;
        this.restApiKey = restApiKey;
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .build();
    }

    public Map<String, Object> collectSeoulTheaters() {
        if (restApiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "카카오 지도 REST API 키가 설정되지 않았습니다."
            );
        }

        long startedAt = System.currentTimeMillis();
        int apiCallCount = 0;
        int insertedCount = 0;
        int updatedCount = 0;
        int duplicateCount = 0;

        Map<String, TheaterPlace> places = new LinkedHashMap<>();
        Map<String, TheaterBrand> brandQueries = new LinkedHashMap<>();
        brandQueries.put("CGV", TheaterBrand.CGV);
        brandQueries.put("롯데시네마", TheaterBrand.LOTTE_CINEMA);
        brandQueries.put("메가박스", TheaterBrand.MEGABOX);

        for (String district : SEOUL_DISTRICTS) {
            for (Map.Entry<String, TheaterBrand> entry : brandQueries.entrySet()) {
                for (int page = 1; page <= 3; page++) {
                    apiCallCount++;

                    List<TheaterPlace> results =
                            search(entry.getKey(), entry.getValue(), district, page);

                    if (results.isEmpty()) {
                        break;
                    }

                    for (TheaterPlace place : results) {
                        if (places.putIfAbsent(place.kakaoPlaceId(), place) != null) {
                            duplicateCount++;
                        }
                    }

                    if (results.size() < 15) {
                        break;
                    }
                }
            }
        }

        for (TheaterPlace place : places.values()) {
            Optional<Theater> existing =
                    theaters.findByKakaoPlaceId(place.kakaoPlaceId());

            Theater theater = existing.orElseGet(Theater::new);

            if (existing.isPresent()) {
                updatedCount++;
            } else {
                insertedCount++;
            }

            theater.setBrand(place.brand());
            theater.setName(place.name());
            theater.setAddress(place.address());
            theater.setKakaoPlaceId(place.kakaoPlaceId());
            theater.setLatitude(place.latitude());
            theater.setLongitude(place.longitude());
            theater.setActive(true);

            theaters.save(theater);
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("districtCount", SEOUL_DISTRICTS.size());
        result.put("apiCallCount", apiCallCount);
        result.put("collectedTheaterCount", places.size());
        result.put("insertedCount", insertedCount);
        result.put("updatedCount", updatedCount);
        result.put("duplicateCount", duplicateCount);
        result.put("elapsedMs", elapsedMs);
        return result;
    }

    private List<TheaterPlace> search(
            String query,
            TheaterBrand brand,
            String district,
            int page
    ) {
        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", district + " " + query)
                        .queryParam("category_group_code", "CT1")
                        .queryParam("size", 15)
                        .queryParam("page", page)
                        .build())
                .header("Authorization", "KakaoAK " + restApiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !(response.get("documents") instanceof List<?> documents)) {
            return List.of();
        }

        List<TheaterPlace> result = new ArrayList<>();

        for (Object item : documents) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String name = string(map.get("place_name"));
            String placeId = string(map.get("id"));
            String roadAddress = string(map.get("road_address_name"));
            String address = roadAddress != null
                    ? roadAddress
                    : string(map.get("address_name"));

            if (name == null || placeId == null || address == null) {
                continue;
            }

            if (!address.contains("서울") || !matchesBrand(name, brand)) {
                continue;
            }

            result.add(new TheaterPlace(
                    placeId,
                    name,
                    brand,
                    address,
                    decimal(map.get("y")),
                    decimal(map.get("x"))
            ));
        }

        return result;
    }

    private boolean matchesBrand(String name, TheaterBrand brand) {
        return switch (brand) {
            case CGV -> name.toUpperCase().contains("CGV");
            case LOTTE_CINEMA -> name.contains("롯데시네마");
            case MEGABOX -> name.contains("메가박스");
        };
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private java.math.BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record TheaterPlace(
            String kakaoPlaceId,
            String name,
            TheaterBrand brand,
            String address,
            java.math.BigDecimal latitude,
            java.math.BigDecimal longitude
    ) {}
}
