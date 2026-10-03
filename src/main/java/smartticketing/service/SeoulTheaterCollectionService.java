package smartticketing.service;

import smartticketing.entity.enums.TheaterBrand;
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

    private final smartticketing.repository.TheaterCollectionProgressRepository progress;
    private final TheaterCatalogWriter writer;
    private final RestClient restClient;
    private final String restApiKey;

    public SeoulTheaterCollectionService(
            smartticketing.repository.TheaterCollectionProgressRepository progress,
            TheaterCatalogWriter writer,
            @org.springframework.beans.factory.annotation.Qualifier("theaterCatalogRestClient") RestClient restClient,
            @Value("${kakao.map.rest-api-key:}") String restApiKey) {
        this.progress = progress;
        this.writer = writer;
        this.restClient = restClient;
        this.restApiKey = restApiKey;
    }

    public Map<String, Object> collectSeoulTheaters() { return collectSeoulTheaters(false); }

    // Startup and manual collection share a lock in this application instance.
    public synchronized Map<String, Object> collectSeoulTheaters(boolean refresh) {
        if (restApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "카카오 지도 REST API 키가 설정되지 않았습니다.");
        }
        long start = System.nanoTime();
        writer.consolidateBranches();
        int calls = 0, inserted = 0, updated = 0, duplicates = 0, skipped = 0;
        var seen = new HashSet<String>();
        var brands = new LinkedHashMap<String, TheaterBrand>();
        brands.put("CGV", TheaterBrand.CGV);
        brands.put("롯데시네마", TheaterBrand.LOTTE_CINEMA);
        brands.put("메가박스", TheaterBrand.MEGABOX);
        if (refresh) writer.reset(SEOUL_DISTRICTS.stream()
                .flatMap(d -> brands.values().stream().map(b -> key(d, b))).toList());
        var keys = SEOUL_DISTRICTS.stream().flatMap(d -> brands.values().stream().map(b -> key(d, b))).toList();
        var states = new HashMap<String, smartticketing.entity.TheaterCollectionProgress>();
        progress.findAllById(keys).forEach(state -> states.put(state.getId(), state));

        for (String district : SEOUL_DISTRICTS) {
            for (var brand : brands.entrySet()) {
                String key = key(district, brand.getValue());
                var state = states.get(key);
                if (state != null && state.isComplete()) { skipped++; continue; }
                int page = state == null ? 1 : state.getNextPage();
                while (true) {
                    if (page > 45) throw new IllegalStateException("Kakao 검색 페이지 한도를 넘었습니다: " + key);
                    calls++;
                    SearchPage result = search(brand.getKey(), brand.getValue(), district, page);
                    var unique = new ArrayList<TheaterPlace>();
                    for (var place : result.places()) {
                        if (seen.add(place.kakaoPlaceId())) unique.add(place); else duplicates++;
                    }
                    // Network requests run outside this short database transaction.
                    var counts = writer.savePage(key, page, result.last(), unique);
                    inserted += counts.inserted();
                    updated += counts.updated();
                    if (result.last()) break;
                    page++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("districtCount", SEOUL_DISTRICTS.size());
        result.put("apiCallCount", calls);
        result.put("collectedTheaterCount", seen.size());
        result.put("insertedCount", inserted);
        result.put("updatedCount", updated);
        result.put("duplicateCount", duplicates);
        result.put("skippedQueryCount", skipped);
        result.put("complete", true);
        result.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private String key(String district, TheaterBrand brand) { return "seoul-v1:" + district + ":" + brand; }

    private SearchPage search(String query, TheaterBrand brand, String district, int page) {
        Map<String, Object> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v2/local/search/keyword.json")
                        .queryParam("query", "서울 " + district + " " + query)
                        .queryParam("category_group_code", "CT1")
                        .queryParam("size", 15).queryParam("page", page).build())
                .header("Authorization", "KakaoAK " + restApiKey)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (response == null || !(response.get("documents") instanceof List<?> documents)
                || !(response.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("is_end") instanceof Boolean last)) {
            throw new IllegalStateException("카카오 장소 응답 형식이 올바르지 않습니다.");
        }
        List<TheaterPlace> result = new ArrayList<>();
        for (Object item : documents) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalStateException("잘못된 장소 응답입니다.");
            String name = string(map.get("place_name"));
            String placeId = string(map.get("id"));
            String road = string(map.get("road_address_name"));
            String address = road != null ? road : string(map.get("address_name"));
            if (name == null || placeId == null || address == null) {
                throw new IllegalStateException("장소 필수 정보가 없습니다.");
            }
            if (!(address.startsWith("서울 ") || address.startsWith("서울특별시 ")) || !matchesBrand(name, brand)) continue;
            if (TheaterBranchIdentity.excludedFromCollection(brand, name, address)) continue;
            var lat = decimal(map.get("y"));
            var lon = decimal(map.get("x"));
            if (lat == null || lon == null || lat.abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0
                    || lon.abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0) {
                throw new IllegalStateException("장소 좌표가 올바르지 않습니다.");
            }
            result.add(new TheaterPlace(placeId, name, brand, address, lat, lon));
        }
        // Filtering must never decide pagination: only upstream meta.is_end does.
        return new SearchPage(result, last);
    }

    private record SearchPage(List<TheaterPlace> places, boolean last) {}

    private boolean matchesBrand(String name, TheaterBrand brand) {
        return switch (brand) {
            case CGV -> name.toUpperCase().contains("CGV");
            case LOTTE_CINEMA -> name.contains("롯데시네마");
            case MEGABOX -> name.contains("메가박스");
        };
    }

    private String string(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
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

    public record TheaterPlace(
            String kakaoPlaceId,
            String name,
            TheaterBrand brand,
            String address,
            java.math.BigDecimal latitude,
            java.math.BigDecimal longitude
    ) {}
}
