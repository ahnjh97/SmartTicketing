package smartticketing.dto.movie;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieDetailResponse (
        Long id,
        String title,
        String overview,
        Integer runtime,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("release_dates") ReleaseDates releaseDates,
        Videos videos,
        Images images
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Images(List<Logo> logos, List<Backdrop> backdrops) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Backdrop(@JsonProperty("file_path") String filePath, Integer width, Integer height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Logo(@JsonProperty("file_path") String filePath,
                       @JsonProperty("iso_639_1") String language) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Videos(List<Video> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Video(String key, String site, String type, boolean official) {}

    // 나라별 개봉 정보 묶음
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseDates(List<CountryRelease> results) {
    }

    // 한 나라의 개봉 정보 (예: KR = 한국)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CountryRelease(
            @JsonProperty("iso_3166_1") String countryCode,
            @JsonProperty("release_dates") List<ReleaseDate> releaseDates
    ) {
    }

    // 개봉 정보 한 건 (관람등급 포함)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseDate(String certification) {
    }
}
