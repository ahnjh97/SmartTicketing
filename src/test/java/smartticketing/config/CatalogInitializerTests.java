package smartticketing.config;

import org.junit.jupiter.api.Test;
import smartticketing.service.MovieImportService;
import smartticketing.service.SeoulTheaterCollectionService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogInitializerTests {
    @Test void disabledOrUnconfiguredInitializersNeverCollect() {
        var movies = mock(MovieImportService.class);
        var theaters = mock(SeoulTheaterCollectionService.class);
        new MovieDataInitializer(movies, true, "").run(null);
        new MovieDataInitializer(movies, false, "key").run(null);
        new TheaterDataInitializer(theaters, true, "").run(null);
        new TheaterDataInitializer(theaters, false, "key").run(null);
        verifyNoInteractions(movies, theaters);
    }

    @Test void startupUsesResumeModeAndExternalFailureDoesNotStopApplication() {
        var movies = mock(MovieImportService.class);
        var theaters = mock(SeoulTheaterCollectionService.class);
        when(movies.importConfiguredMovies()).thenThrow(new IllegalStateException("offline"));
        when(theaters.collectSeoulTheaters()).thenThrow(new IllegalStateException("offline"));
        assertThatCode(() -> new MovieDataInitializer(movies, true, "key").run(null)).doesNotThrowAnyException();
        assertThatCode(() -> new TheaterDataInitializer(theaters, true, "key").run(null)).doesNotThrowAnyException();
        verify(movies).importConfiguredMovies(); verify(theaters).collectSeoulTheaters();
        verify(movies, never()).importConfiguredMovies(true); verify(theaters, never()).collectSeoulTheaters(true);
    }
}
