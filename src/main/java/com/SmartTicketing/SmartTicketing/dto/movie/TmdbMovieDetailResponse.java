package com.SmartTicketing.SmartTicketing.dto.movie;

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
        @JsonProperty("release_dates") ReleaseDates releaseDates
) {

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