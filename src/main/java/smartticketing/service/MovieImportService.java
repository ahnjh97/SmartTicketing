package smartticketing.service;

import smartticketing.dto.movie.MovieImportResult;
import smartticketing.dto.movie.TmdbMovieDetailResponse;
import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MovieMetadataWriter metadataWriter;
    private final String imageBaseUrl;
    private final List<Long> movieIds;
    private final Map<Long, String> ratingOverrides;

    public MovieImportService(
            @Qualifier("tmdbRestClient") RestClient tmdbRestClient,
            MovieRepository movieRepository,
            MovieMetadataWriter metadataWriter,
            @Value("${tmdb.image-base-url}") String imageBaseUrl,
            @Value("${tmdb.movie-ids}") String movieIds,
            @Value("${tmdb.rating-overrides:}") String ratingOverrides) {

        this.tmdbRestClient = tmdbRestClient;
        this.movieRepository = movieRepository;
        this.metadataWriter = metadataWriter;
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
        return importConfiguredMovies(false);
    }

    public MovieImportResult importConfiguredMovies(boolean refresh) {
        int savedCount = 0;
        int skippedCount = 0;
        int updatedCount = 0;
        List<Long> failedIds = new ArrayList<>();

        for (Long tmdbId : movieIds) {
            var existing = movieRepository.findByTmdbMovieId(tmdbId);
            if (!refresh && existing.isPresent() && existing.get().getMetadataFetchedAt() != null) {
                skippedCount++;
                continue;
            }

            try {
                importOne(tmdbId);
                if (existing.isPresent()) updatedCount++; else savedCount++;
            } catch (Exception e) {
                log.warn("TMDB 영화 가져오기 실패 - tmdbId: {}, 유형: {}", tmdbId, e.getClass().getSimpleName());
                failedIds.add(tmdbId);
            }
        }

        log.info("영화 가져오기 완료 - 저장 {}건, 건너뜀 {}건, 실패 {}건",
                savedCount, skippedCount, failedIds.size());
        return new MovieImportResult(savedCount, skippedCount, failedIds, updatedCount);
    }

    /** 3단계 조회 서비스에서 사용. DB에 있는 영화는 외부 API 없이 즉시 반환한다. */
    public Movie getOrImportMovie(Long tmdbId) {
        if (tmdbId == null || tmdbId <= 0) throw new IllegalArgumentException("TMDB ID는 양수여야 합니다.");
        return movieRepository.findByTmdbMovieId(tmdbId).orElseGet(() -> importOne(tmdbId));
    }

    private Movie importOne(Long tmdbId) {
        var response = fetchMovie(tmdbId);
        if (response == null || !tmdbId.equals(response.id()) || response.title() == null || response.title().isBlank()) {
            throw new IllegalStateException("TMDB 영화 응답이 올바르지 않습니다.");
        }
        try {
            return metadataWriter.saveMissing(toMovie(response));
        } catch (DataIntegrityViolationException duplicate) {
            // 동시에 같은 영화를 수집한 요청이 먼저 저장했다면 그 레코드를 사용한다.
            return movieRepository.findByTmdbMovieId(tmdbId).orElseThrow(() -> duplicate);
        }
    }

    private TmdbMovieDetailResponse fetchMovie(Long tmdbId) {
        return tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/{id}")
                        .queryParam("language", "ko-KR")
                        .queryParam("append_to_response", "release_dates,videos")
                        .build(tmdbId))
                .retrieve()
                .body(TmdbMovieDetailResponse.class);
    }

    private Movie toMovie(TmdbMovieDetailResponse response) {
        Movie movie = new Movie();
        movie.setTmdbMovieId(response.id());
        movie.setTitle(response.title());
        movie.setDescription(response.overview());
        movie.setRunningTime(response.runtime() != null && response.runtime() > 0 ? response.runtime() : null);
        // TMDB에 한국 등급이 없으면 설정 파일에 적어둔 등급 사용
        String rating = findKoreanRating(response);
        movie.setRating(rating != null ? rating : ratingOverrides.get(response.id()));
        movie.setReleaseDate(parseDate(response.releaseDate()));
        movie.setPosterUrl(response.posterPath() == null ? null : imageBaseUrl + response.posterPath());
        movie.setTrailerUrl(findTrailer(response));
        movie.setMetadataFetchedAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        movie.setActive(true);
        return movie;
    }

    private String findTrailer(TmdbMovieDetailResponse response) {
        if (response.videos() == null || response.videos().results() == null) return null;
        return response.videos().results().stream()
                .filter(Objects::nonNull)
                .filter(v -> "YouTube".equals(v.site()) && "Trailer".equals(v.type()))
                .filter(v -> v.key() != null && v.key().matches("[A-Za-z0-9_-]{11}"))
                .sorted(Comparator.comparing(TmdbMovieDetailResponse.Video::official).reversed()
                        .thenComparing(TmdbMovieDetailResponse.Video::key))
                .map(v -> "https://www.youtube.com/watch?v=" + v.key()).findFirst().orElse(null);
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
