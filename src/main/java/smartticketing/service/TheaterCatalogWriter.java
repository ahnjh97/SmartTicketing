package smartticketing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.Theater;
import smartticketing.entity.TheaterCollectionProgress;
import smartticketing.repository.TheaterRepository;
import smartticketing.repository.TheaterCollectionProgressRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class TheaterCatalogWriter {
    private final TheaterRepository theaters;
    private final TheaterCollectionProgressRepository progress;

    public TheaterCatalogWriter(TheaterRepository theaters, TheaterCollectionProgressRepository progress) {
        this.theaters = theaters;
        this.progress = progress;
    }

    @Transactional
    public Counts savePage(String key, int page, boolean last, List<SeoulTheaterCollectionService.TheaterPlace> places) {
        var state = progress.findById(key).orElseGet(() -> new TheaterCollectionProgress(key));
        if (state.isComplete() || state.getNextPage() != page) {
            throw new IllegalStateException("영화관 수집 진행 상태가 변경되었습니다. 다시 실행하세요.");
        }
        int inserted = 0, updated = 0;
        for (var place : places) {
            var existing = theaters.findByKakaoPlaceId(place.kakaoPlaceId());
            Theater theater = existing.orElseGet(Theater::new);
            if (existing.isEmpty()) inserted++; else updated++;
            theater.setKakaoPlaceId(place.kakaoPlaceId());
            theater.setName(place.name());
            theater.setBrand(place.brand());
            theater.setAddress(place.address());
            theater.setLatitude(place.latitude());
            theater.setLongitude(place.longitude());
            // Existing manual deactivation is preserved; new entities default to active.
            theaters.save(theater);
        }
        state.setNextPage(page + 1);
        state.setComplete(last);
        state.setCheckedAt(LocalDateTime.now(ZoneOffset.UTC));
        progress.save(state);
        return new Counts(inserted, updated);
    }

    /** Explicit refresh invalidates progress only, never catalog data. */
    @Transactional
    public void reset(List<String> keys) { progress.deleteAllById(keys); }

    public record Counts(int inserted, int updated) {}
}
