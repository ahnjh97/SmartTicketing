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
    private final String backdropBaseUrl;
    private final List<Long> movieIds;
    private final Map<Long, String> ratingOverrides;
    private final Map<Long, Long> audienceSeeds;
    private final Map<String, String> nameOverrides;
    private final Map<Long, LocalDate> releaseDateOverrides;

    public MovieImportService(
            @Qualifier("tmdbRestClient") RestClient tmdbRestClient,
            MovieRepository movieRepository,
            MovieMetadataWriter metadataWriter,
            @Value("${tmdb.image-base-url}") String imageBaseUrl,
            @Value("${tmdb.backdrop-base-url:https://image.tmdb.org/t/p/w1280}") String backdropBaseUrl,
            @Value("${tmdb.movie-ids}") String movieIds,
            @Value("${tmdb.rating-overrides:}") String ratingOverrides,
            @Value("${tmdb.audience-seeds:}") String audienceSeeds,
            @Value("${tmdb.name-overrides:}") String nameOverrides,
            @Value("${tmdb.release-date-overrides:}") String releaseDateOverrides) {

        this.tmdbRestClient = tmdbRestClient;
        this.movieRepository = movieRepository;
        this.metadataWriter = metadataWriter;
        this.imageBaseUrl = imageBaseUrl;
        this.backdropBaseUrl = backdropBaseUrl;

        this.movieIds = Arrays.stream(movieIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(Long::valueOf)
                .distinct()
                .toList();

        this.ratingOverrides = Arrays.stream(ratingOverrides.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> Long.valueOf(pair[0].trim()), pair -> pair[1].trim()));

        this.audienceSeeds = Arrays.stream(audienceSeeds.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> Long.valueOf(pair[0].trim()), pair -> Long.valueOf(pair[1].trim())));

        this.nameOverrides = Arrays.stream(nameOverrides.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> pair[0].trim(), pair -> pair[1].trim()));

        this.releaseDateOverrides = Arrays.stream(releaseDateOverrides.split(","))
                .map(String::trim)
                .filter(entry -> entry.contains(":"))
                .map(entry -> entry.split(":"))
                .collect(Collectors.toMap(pair -> Long.valueOf(pair[0].trim()), pair -> LocalDate.parse(pair[1].trim())));
    }

    public MovieImportResult importConfiguredMovies() {
        return importConfiguredMovies(false);
    }

    public synchronized MovieImportResult importConfiguredMovies(boolean refresh) {
        long startedAt = System.nanoTime();
        int savedCount = 0;
        int skippedCount = 0;
        int updatedCount = 0;
        boolean upstreamUnavailable = false;
        List<Long> failedIds = new ArrayList<>();

        for (Long tmdbId : movieIds) {
            var existing = movieRepository.findByTmdbMovieId(tmdbId);
            if (!refresh && existing.isPresent() && existing.get().getMetadataFetchedAt() != null
                    && existing.get().getImageMetadataFetchedAt() != null && existing.get().getGenres() != null){
                skippedCount++;
                continue;
            }

            if (upstreamUnavailable) {
                failedIds.add(tmdbId); // Incomplete, but no further upstream request is attempted.
                continue;
            }

            try {
                importOne(tmdbId);
                if (existing.isPresent()) updatedCount++; else savedCount++;
            } catch (Exception e) {
                log.warn("TMDB 영화 가져오기 실패 - tmdbId: {}, 유형: {}", tmdbId, e.getClass().getSimpleName());
                failedIds.add(tmdbId);
                // An invalid credential/quota cannot recover by requesting the next movie.
                if (e instanceof org.springframework.web.client.ResourceAccessException
                        || e instanceof org.springframework.web.client.RestClientResponseException http
                        && (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403
                            || http.getStatusCode().value() == 429)) {
                    upstreamUnavailable = true;
                }
            }
        }

        int defaultsUpdated = applyConfiguredDefaults();

        log.info("[DB 데이터] movies 메타데이터 삽입 {}건, 수정 {}건 | 기본정보 보정 {}건 | 건너뜀 {}건 | 실패 {}건 | 영화 준비 {}ms",
                savedCount, updatedCount, defaultsUpdated, skippedCount, failedIds.size(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return new MovieImportResult(savedCount, skippedCount, failedIds, updatedCount);
    }

    private int applyConfiguredDefaults() {
        var changedIds = new java.util.HashSet<Long>();
        var configuredIds = new java.util.HashSet<>(movieIds);
        configuredIds.addAll(releaseDateOverrides.keySet());
        for (Movie movie : movieRepository.findByTmdbMovieIdIn(configuredIds.stream().sorted().toList())) {
            Long tmdbId = movie.getTmdbMovieId();
            boolean changed = false;

            if (movie.getAudienceCount() == 0 && audienceSeeds.containsKey(tmdbId)) {
                movie.setAudienceCount(audienceSeeds.get(tmdbId));
                changed = true;
            }
            if (movie.getRating() == null && ratingOverrides.containsKey(tmdbId)) {
                movie.setRating(ratingOverrides.get(tmdbId));
                changed = true;
            }

            LocalDate releaseDate = releaseDateOverrides.get(tmdbId);
            if (releaseDate != null && !releaseDate.equals(movie.getReleaseDate())) {
                movie.setReleaseDate(releaseDate);
                changed = true;
            }
            if (changed) {
                movieRepository.save(movie);
                changedIds.add(movie.getId());
            }
        }


        // =========================================================================
        // 개봉일이 가장 뒤에 있는 영화 10개만 상영예정작(10/11 ~ 10/17)으로 지정
        // =========================================================================
        List<Movie> upcomingMovies = (releaseDateOverrides.isEmpty()
                ? movieRepository.findTop10ByReleaseDateIsNotNullOrderByReleaseDateDescTmdbMovieIdAsc()
                : movieRepository.findTop10ByReleaseDateIsNotNullAndTmdbMovieIdNotInOrderByReleaseDateDescTmdbMovieIdAsc(
                        releaseDateOverrides.keySet().stream().sorted().toList())).stream()
                .sorted(Comparator.comparing(Movie::getTmdbMovieId))
                .toList();

        for (int i = 0; i < upcomingMovies.size(); i++) {
            Movie upcomingMovie = upcomingMovies.get(i);
            LocalDate adjustedDate = LocalDate.of(2026, 10, 11).plusDays(i % 7);
            if (!adjustedDate.equals(upcomingMovie.getReleaseDate())) {
                upcomingMovie.setReleaseDate(adjustedDate);
                movieRepository.save(upcomingMovie);
                changedIds.add(upcomingMovie.getId());
            }
        }

        return changedIds.size();
    }

    /** 3단계 조회 서비스에서 사용. DB에 있는 영화는 외부 API 없이 즉시 반환한다. */
    public synchronized Movie getOrImportMovie(Long tmdbId) {
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
                        .queryParam("append_to_response", "release_dates,videos,images,credits")
                        .queryParam("include_image_language", "ko,en,null")
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
        movie.setBackdropUrl(findBackdrop(response));
        movie.setTrailerUrl(findTrailer(response));
        movie.setLogoUrl(findLogo(response));
        movie.setMetadataFetchedAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
        movie.setImageMetadataFetchedAt(movie.getMetadataFetchedAt());
        movie.setActive(true);
        movie.setAudienceCount(audienceSeeds.getOrDefault(response.id(), 0L));
        movie.setGenres(joinGenres(response));
        movie.setDirector(findDirector(response));
        movie.setCastNames(joinCast(response));
        return movie;
    }

    private String findBackdrop(TmdbMovieDetailResponse response) {
        if (response.images() != null && response.images().backdrops() != null) {
            return response.images().backdrops().stream().filter(Objects::nonNull)
                    .filter(image -> image.filePath() != null && image.filePath().startsWith("/")
                            && image.width() != null && image.height() != null
                            && image.height() > 0 && image.width() > image.height())
                    .sorted(Comparator.comparingLong((TmdbMovieDetailResponse.Backdrop image) ->
                            (long) image.width() * image.height()).reversed()
                            .thenComparing(TmdbMovieDetailResponse.Backdrop::filePath))
                    .map(image -> backdropBaseUrl + image.filePath()).findFirst().orElse(null);
        }
        // TMDB's dedicated backdrop field only; never substitute poster_path.
        return response.backdropPath() != null && response.backdropPath().startsWith("/")
                ? backdropBaseUrl + response.backdropPath() : null;
    }

    private String findLogo(TmdbMovieDetailResponse response) {
        if (response.images() == null || response.images().logos() == null) return null;
        return response.images().logos().stream().filter(Objects::nonNull)
                .filter(logo -> logo.filePath() != null && logo.filePath().startsWith("/") && !logo.filePath().isBlank())
                .sorted(Comparator.comparingInt((TmdbMovieDetailResponse.Logo logo) ->
                        "ko".equals(logo.language()) ? 0 : "en".equals(logo.language()) ? 1 : 2)
                        .thenComparing(TmdbMovieDetailResponse.Logo::filePath))
                .map(logo -> imageBaseUrl + logo.filePath()).findFirst().orElse(null);
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

    // 장르를 "모험, 액션, 판타지"로 연결 (없으면 빈 문자열: 다시 가져오지 않도록 null 대신 사용)
    private String joinGenres(TmdbMovieDetailResponse response) {
        if (response.genres() == null) return "";
        return response.genres().stream()
                .map(TmdbMovieDetailResponse.Genre::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String findDirector(TmdbMovieDetailResponse response) {
        if (response.credits() == null || response.credits().crew() == null) return null;
        String directors = response.credits().crew().stream()
                .filter(crew -> "Director".equals(crew.job()))
                .map(TmdbMovieDetailResponse.CrewMember::name)
                .map(name -> nameOverrides.getOrDefault(name, name))
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(2)
                .collect(Collectors.joining(", "));
        return directors.isBlank() ? null : directors;
    }

    private String joinCast(TmdbMovieDetailResponse response) {
        if (response.credits() == null || response.credits().cast() == null) return null;
        String cast = response.credits().cast().stream()
                .filter(member -> member.name() != null && !member.name().isBlank())
                .sorted(Comparator.comparing(TmdbMovieDetailResponse.CastMember::order,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(5)
                .map(TmdbMovieDetailResponse.CastMember::name)
                .map(name -> nameOverrides.getOrDefault(name, name))
                .collect(Collectors.joining(", "));
        return cast.isBlank() ? null : cast;
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
