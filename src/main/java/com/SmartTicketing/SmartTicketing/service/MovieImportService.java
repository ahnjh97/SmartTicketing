package com.SmartTicketing.SmartTicketing.service;

import com.SmartTicketing.SmartTicketing.dto.movie.MovieImportResult;
import com.SmartTicketing.SmartTicketing.dto.movie.TmdbMovieDetailResponse;
import com.SmartTicketing.SmartTicketing.entity.Movie;
import com.SmartTicketing.SmartTicketing.repository.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;

@Slf4j
@Service
public class MovieImportService {
    private final RestClient tmdbRestClient;
    private final MovieRepository movieRepository;
    private final String imageBaseUrl;
    private final List<Long> movieIds;
    private final Map<Long, String> ratingOverrides;

    public MovieImportService(
            @Qualifier("tmdbRestClient") RestClient tmdbRestClient,
            MovieRepository movieRepository,
            @Value("${tmdb.image-base-url}") String imageBaseUrl,
            @Value("${tmdb.movie-ids}") String movieIds,
            @Value("${tmdb.rating-overrides:}") String ratingOverrides) {

        this.tmdbRestClient = tmdbRestClient;
        this.movieRepository = movieRepository;
        this.imageBaseUrl = imageBaseUrl;

        this.movieIds = Arrays.stream(movieIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::valueOf)
                .toList();

        this.ratingOverrides = Arrays.stream(ratingOverrides.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> Long.valueOf(pair[0].trim()), pair -> pair[1].trim()));
    }

    public MovieImportResult importConfiguredMovies() {
        int savedCount = 0;
        int skippedCount = 0;
        List<Long> failedIds = new ArrayList<>();

        for (Long tmdbId : movieIds) {
            if (movieRepository.existsByTmdbMovieId(tmdbId)) {
                skippedCount++;
                continue;
            }

            try {
                TmdbMovieDetailResponse response = fetchMovie(tmdbId);
                movieRepository.save(toMovie(response));
                savedCount++;
            } catch (Exception e) {
                log.warn("TMDB 영화 가져오기 실패 - tmdbId: {}, 원인: {}", tmdbId, e.getMessage());
                failedIds.add(tmdbId);
            }
        }

        log.info("영화 가져오기 완료 - 저장 {}건, 건너뜀 {}건, 실패 {}건",
                savedCount, skippedCount, failedIds.size());
        return new MovieImportResult(savedCount, skippedCount, failedIds);
    }

    private TmdbMovieDetailResponse fetchMovie(Long tmdbId) {
        return tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/{id}")
                        .queryParam("language", "ko-KR")
                        .queryParam("append_to_response", "release_dates")
                        .build(tmdbId))
                .retrieve()
                .body(TmdbMovieDetailResponse.class);
    }

    private Movie toMovie(TmdbMovieDetailResponse response) {
        Movie movie = new Movie();
        movie.setTmdbMovieId(response.id());
        movie.setTitle(response.title());
        movie.setDescription(response.overview());
        movie.setRunningTime(response.runtime());
        // TMDB에 한국 등급이 없으면 설정 파일에 적어둔 등급 사용
        String rating = findKoreanRating(response);
        movie.setRating(rating != null ? rating : ratingOverrides.get(response.id()));
        movie.setReleaseDate(parseDate(response.releaseDate()));
        movie.setPosterUrl(response.posterPath() == null ? null : imageBaseUrl + response.posterPath());
        movie.setActive(true);
        return movie;
    }

    private String findKoreanRating(TmdbMovieDetailResponse response) {
        if (response.releaseDates() == null || response.releaseDates().results() == null) {
            return null;
        }
        return response.releaseDates().results().stream()
                .filter(country -> "KR".equals(country.countryCode()))
                .filter(country -> country.releaseDates() != null)
                .flatMap(country -> country.releaseDates().stream())
                .map(TmdbMovieDetailResponse.ReleaseDate::certification)
                .filter(Objects::nonNull)
                .filter(certification -> !certification.isBlank())
                .findFirst()
                .orElse(null);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date);
    }
}