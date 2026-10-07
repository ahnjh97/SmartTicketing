package smartticketing.booking;

import smartticketing.dto.movie.MovieMedia;
import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;
import smartticketing.service.MovieImportService;
import smartticketing.service.MovieMetadataWriter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MovieImportTests {
    private MovieRepository movies;
    private MovieMetadataWriter writer;
    private MockRestServiceServer server;
    private MovieImportService service;
    @BeforeEach void setup() {
        movies = mock(MovieRepository.class); writer = mock(MovieMetadataWriter.class);
        var builder = RestClient.builder().baseUrl("https://tmdb.test/3");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new MovieImportService(builder.build(), movies, writer, "https://images.test", "https://backdrops.test", "11,12", "11:ALL", "11:10000", "", "");
        when(writer.saveMissing(any())).thenAnswer(call -> call.getArgument(0));
    }
    @AfterEach void verifyServer() { server.verify(); }

    @Test void defaultMetadataIsLoadedInBulkWithoutReadingEntireCatalog() {
        var checked = new Movie(); checked.setGenres(""); checked.setId(1L); checked.setTmdbMovieId(11L);
        checked.setMetadataFetchedAt(LocalDateTime.now()); checked.setImageMetadataFetchedAt(checked.getMetadataFetchedAt());
        when(movies.findByTmdbMovieId(anyLong())).thenReturn(Optional.of(checked));
        when(movies.findByTmdbMovieIdIn(anyList())).thenReturn(java.util.List.of(checked));
        assertThat(service.importConfiguredMovies().skippedCount()).isEqualTo(2);
        assertThat(checked.getRating()).isEqualTo("ALL");
        assertThat(checked.getAudienceCount()).isEqualTo(10000);
        verify(movies).findByTmdbMovieIdIn(java.util.List.of(11L, 12L));
        verify(movies).findTop10ByReleaseDateIsNotNullOrderByReleaseDateDescTmdbMovieIdAsc();
        verify(movies, never()).findAll();
    }

    @Test void explicitReleaseDatesStayExcludedAndUnchangedDefaultsAreNotSavedAgain() {
        var fixed = new Movie(); fixed.setId(1L); fixed.setTmdbMovieId(11L);
        fixed.setReleaseDate(java.time.LocalDate.of(2026, 10, 1));
        var upcoming = new Movie(); upcoming.setId(2L); upcoming.setTmdbMovieId(12L);
        upcoming.setReleaseDate(java.time.LocalDate.of(2026, 12, 1));
        for (var movie : java.util.List.of(fixed, upcoming)) {
            movie.setGenres(""); movie.setMetadataFetchedAt(LocalDateTime.now());
            movie.setImageMetadataFetchedAt(movie.getMetadataFetchedAt());
            when(movies.findByTmdbMovieId(movie.getTmdbMovieId())).thenReturn(Optional.of(movie));
        }
        when(movies.findByTmdbMovieIdIn(anyList())).thenReturn(java.util.List.of(fixed, upcoming));
        when(movies.findTop10ByReleaseDateIsNotNullAndTmdbMovieIdNotInOrderByReleaseDateDescTmdbMovieIdAsc(java.util.List.of(11L)))
                .thenReturn(java.util.List.of(upcoming));
        service = new MovieImportService(RestClient.create(), movies, writer, "", "", "11,12", "", "", "", "11:2026-10-03");
        service.importConfiguredMovies(); service.importConfiguredMovies();
        assertThat(fixed.getReleaseDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 3));
        assertThat(upcoming.getReleaseDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 1));
        verify(movies, times(1)).save(fixed); verify(movies, never()).save(upcoming);
        verify(movies, never()).findAll();
    }

    @Test void databaseHitDoesNotCallExternalApi() {
        var movie = new Movie(); movie.setTmdbMovieId(11L); movie.setTitle("기존");
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(movie));
        assertThat(service.getOrImportMovie(11L)).isSameAs(movie);
        verifyNoInteractions(writer);
    }

    @Test void missingMovieLoadsDetailsAndOfficialTrailerInOneRequest() {
        server.expect(requestTo("https://tmdb.test/3/movie/11?language=ko-KR&append_to_response=release_dates,videos,images,credits&include_image_language=ko,en,null"))
                .andRespond(withSuccess("""
                        {"id":11,"title":"새 영화","runtime":120,"poster_path":"/poster.jpg","backdrop_path":"/wide.jpg",
                         "images":{"logos":[{"file_path":"/en.png","iso_639_1":"en"},{"file_path":"/ko.png","iso_639_1":"ko"}]},
                         "videos":{"results":[
                          {"key":"bbbbbbbbbbb","site":"YouTube","type":"Trailer","official":false},
                          {"key":"aaaaaaaaaaa","site":"YouTube","type":"Trailer","official":true}]}}
                        """, MediaType.APPLICATION_JSON));
        var movie = service.getOrImportMovie(11L);
        assertThat(movie.getTrailerUrl()).isEqualTo("https://www.youtube.com/watch?v=aaaaaaaaaaa");
        assertThat(movie.getPosterUrl()).isEqualTo("https://images.test/poster.jpg");
        assertThat(movie.getBackdropUrl()).isEqualTo("https://backdrops.test/wide.jpg");
        assertThat(movie.getLogoUrl()).isEqualTo("https://images.test/ko.png");
        assertThat(smartticketing.dto.booking.CatalogResponse.MovieItem.from(movie).logoUrl()).isEqualTo(movie.getLogoUrl());
        assertThat(smartticketing.dto.booking.CatalogResponse.MovieItem.from(movie).backdropUrl()).isEqualTo(movie.getBackdropUrl());
        assertThat(smartticketing.dto.booking.CatalogResponse.MovieDetail.from(movie).backdropUrl()).isEqualTo(movie.getBackdropUrl());
        assertThat(movie.getRating()).isEqualTo("ALL");
        assertThat(movie.getAudienceCount()).isEqualTo(10000L);
        assertThat(movie.getMetadataFetchedAt()).isNotNull();
        assertThat(movie.getImageMetadataFetchedAt()).isNotNull();
        assertThat(MovieMedia.from(movie).type()).isEqualTo("TRAILER");
    }

    @Test void landscapeCollectionSelectsLargestWideImageAndNeverUsesPortrait() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?"))).andRespond(withSuccess("""
                {"id":11,"title":"가로 이미지","poster_path":"/poster.jpg","images":{"backdrops":[
                  {"file_path":"/portrait.jpg","width":4000,"height":6000},
                  {"file_path":"/small.jpg","width":1280,"height":720},
                  {"file_path":"/wide.jpg","width":3840,"height":2160}]}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/12?"))).andRespond(withSuccess("""
                {"id":12,"title":"세로만","poster_path":"/poster.jpg","images":{"backdrops":[
                  {"file_path":"/portrait.jpg","width":1000,"height":1500}]}}
                """, MediaType.APPLICATION_JSON));
        assertThat(service.getOrImportMovie(11L).getBackdropUrl()).isEqualTo("https://backdrops.test/wide.jpg");
        assertThat(service.getOrImportMovie(12L).getBackdropUrl()).isNull();
    }

    @Test void noUsableTrailerFallsBackToPosterAndMissingRuntimeStaysMissing() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?"))).andRespond(withSuccess("""
                {"id":11,"title":"영상 없음","runtime":0,"poster_path":"/poster.jpg",
                 "videos":{"results":[{"key":"unsafe?x=1","site":"YouTube","type":"Trailer","official":true}]}}
                """, MediaType.APPLICATION_JSON));
        var movie = service.getOrImportMovie(11L);
        assertThat(movie.getRunningTime()).isNull();
        assertThat(movie.getTrailerUrl()).isNull();
        assertThat(movie.getLogoUrl()).isNull();
        assertThat(MovieMedia.from(movie).type()).isEqualTo("POSTER");
        movie.setPosterUrl(null);
        assertThat(MovieMedia.from(movie).type()).isEqualTo("NONE");
    }

    @Test void configuredImportSkipsCheckedMoviesAndContinuesAfterFailure() {
        var checked = new Movie(); checked.setGenres(""); checked.setMetadataFetchedAt(LocalDateTime.now());
        checked.setImageMetadataFetchedAt(checked.getMetadataFetchedAt());
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(checked));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/12?"))).andRespond(withServerError());
        var result = service.importConfiguredMovies();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(12L);
        assertThat(result.savedCount()).isZero();
        verifyNoInteractions(writer);
    }

    @Test void configuredImportEnrichesUncheckedExistingMovieAndStillImportsNextOne() {
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(new Movie()));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"기존\",\"runtime\":120}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/12?")))
                .andRespond(withSuccess("{\"id\":12,\"title\":\"신규\",\"runtime\":130}", MediaType.APPLICATION_JSON));
        var result = service.importConfiguredMovies();
        assertThat(result.updatedCount()).isEqualTo(1); assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.failedIds()).isEmpty();
    }

    @Test void legacyMoviesCollectImagesOnceEvenWhenNoImagesAreAvailable() {
        var stored = new java.util.HashMap<Long, Movie>();
        for (long id : new long[]{11L, 12L}) {
            var legacy = new Movie(); legacy.setTmdbMovieId(id);
            legacy.setMetadataFetchedAt(LocalDateTime.now()); stored.put(id, legacy);
        }
        when(movies.findByTmdbMovieId(anyLong())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(writer.saveMissing(any())).thenAnswer(call -> {
            Movie incoming = call.getArgument(0); stored.put(incoming.getTmdbMovieId(), incoming); return incoming;
        });
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"가로\",\"backdrop_path\":\"/wide.jpg\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/12?")))
                .andRespond(withSuccess("{\"id\":12,\"title\":\"이미지 없음\"}", MediaType.APPLICATION_JSON));
        assertThat(service.importConfiguredMovies().updatedCount()).isEqualTo(2);
        assertThat(stored.get(11L).getBackdropUrl()).isEqualTo("https://backdrops.test/wide.jpg");
        assertThat(stored.get(12L).getBackdropUrl()).isNull();
        assertThat(stored.get(12L).getImageMetadataFetchedAt()).isNotNull();
        assertThat(service.importConfiguredMovies().skippedCount()).isEqualTo(2);
        verify(writer, times(2)).saveMissing(any());
    }

    @Test void failedLegacyImageCollectionRemainsEligibleForRetry() {
        var legacy = new Movie(); legacy.setMetadataFetchedAt(LocalDateTime.now());
        var checked = new Movie(); checked.setGenres(""); checked.setMetadataFetchedAt(LocalDateTime.now());
        checked.setImageMetadataFetchedAt(LocalDateTime.now());
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(legacy));
        when(movies.findByTmdbMovieId(12L)).thenReturn(Optional.of(checked));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?"))).andRespond(withServerError());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"재시도\"}", MediaType.APPLICATION_JSON));
        assertThat(service.importConfiguredMovies().failedIds()).containsExactly(11L);
        assertThat(legacy.getImageMetadataFetchedAt()).isNull();
        verifyNoInteractions(writer);
        assertThat(service.importConfiguredMovies().updatedCount()).isEqualTo(1);
        verify(writer).saveMissing(any());
    }

    @Test void mismatchedResponseIsNotSaved() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":12,\"title\":\"잘못된 응답\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.getOrImportMovie(11L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test void explicitRefreshCanRecheckMoviesWithPreviouslyMissingVideos() {
        var checked = new Movie(); checked.setGenres(""); checked.setMetadataFetchedAt(LocalDateTime.now());
        checked.setImageMetadataFetchedAt(checked.getMetadataFetchedAt());
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(checked));
        when(movies.findByTmdbMovieId(12L)).thenReturn(Optional.of(checked));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"기존\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/12?")))
                .andRespond(withSuccess("{\"id\":12,\"title\":\"기존\"}", MediaType.APPLICATION_JSON));
        assertThat(service.importConfiguredMovies(true).updatedCount()).isEqualTo(2);
    }

    @Test void concurrentInsertWinnerIsReusedWithoutDuplicateMovie() {
        var winner = new Movie(); winner.setTmdbMovieId(11L);
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.empty(), Optional.of(winner));
        when(writer.saveMissing(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"신규\"}", MediaType.APPLICATION_JSON));
        assertThat(service.getOrImportMovie(11L)).isSameAs(winner);
    }

    @Test void quotaFailureStopsRemainingOutboundCalls() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThat(service.importConfiguredMovies().failedIds()).containsExactly(11L, 12L);
        verifyNoInteractions(writer);
    }

    @Test void duplicateConfiguredIdsAreFetchedOnce() {
        service = new MovieImportService(clientForDuplicateTest(), movies, writer,
                "https://images.test", "https://backdrops.test", "11,11", "", "", "", "");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":11,\"title\":\"영화\"}", MediaType.APPLICATION_JSON));
        assertThat(service.importConfiguredMovies().savedCount()).isEqualTo(1);
    }

    @Test void quotaFailureDoesNotLabelAlreadyPreparedMoviesAsFailed() {
        var checked = new Movie(); checked.setGenres(""); checked.setMetadataFetchedAt(LocalDateTime.now());
        checked.setImageMetadataFetchedAt(checked.getMetadataFetchedAt());
        when(movies.findByTmdbMovieId(12L)).thenReturn(Optional.of(checked));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        var result = service.importConfiguredMovies();
        assertThat(result.failedIds()).containsExactly(11L);
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    private RestClient clientForDuplicateTest() {
        var builder = RestClient.builder().baseUrl("https://tmdb.test/3");
        server = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }
}
