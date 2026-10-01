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
        service = new MovieImportService(builder.build(), movies, writer, "https://images.test", "https://backdrops.test", "11,12", "11:ALL");
        when(writer.saveMissing(any())).thenAnswer(call -> call.getArgument(0));
    }
    @AfterEach void verifyServer() { server.verify(); }

    @Test void databaseHitDoesNotCallExternalApi() {
        var movie = new Movie(); movie.setTmdbMovieId(11L); movie.setTitle("기존");
        when(movies.findByTmdbMovieId(11L)).thenReturn(Optional.of(movie));
        assertThat(service.getOrImportMovie(11L)).isSameAs(movie);
        verifyNoInteractions(writer);
    }

    @Test void missingMovieLoadsDetailsAndOfficialTrailerInOneRequest() {
        server.expect(requestTo("https://tmdb.test/3/movie/11?language=ko-KR&append_to_response=release_dates,videos,images&include_image_language=ko,en,null"))
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
        assertThat(movie.getMetadataFetchedAt()).isNotNull();
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
        var checked = new Movie(); checked.setMetadataFetchedAt(LocalDateTime.now());
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

    @Test void mismatchedResponseIsNotSaved() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/movie/11?")))
                .andRespond(withSuccess("{\"id\":12,\"title\":\"잘못된 응답\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.getOrImportMovie(11L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test void explicitRefreshCanRecheckMoviesWithPreviouslyMissingVideos() {
        var checked = new Movie(); checked.setMetadataFetchedAt(LocalDateTime.now());
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
}
