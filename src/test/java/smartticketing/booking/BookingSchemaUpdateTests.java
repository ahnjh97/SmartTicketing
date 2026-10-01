package smartticketing.booking;

import smartticketing.entity.Movie;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BookingSchemaUpdateTests {
    @Test void hibernateAddsNullableMetadataWithoutChangingExistingMovie() throws Exception {
        try (var database = new TemporaryMysqlDatabase("""
                CREATE TABLE movies (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  tmdb_movie_id BIGINT NOT NULL UNIQUE, title VARCHAR(255) NOT NULL,
                  description TEXT, running_time INT, rating VARCHAR(20), release_date DATE,
                  poster_url VARCHAR(500), trailer_url VARCHAR(500), is_active BIT NOT NULL)
                """, """
                INSERT INTO movies(tmdb_movie_id,title,running_time,is_active)
                VALUES (11,'기존 영화',125,0)
                """)) {
            try (var em = database.open()) {
                var movie = em.createQuery("from Movie where tmdbMovieId = 11", Movie.class).getSingleResult();
                assertThat(movie.getTitle()).isEqualTo("기존 영화");
                assertThat(movie.getRunningTime()).isEqualTo(125);
                assertThat(movie.isActive()).isFalse();
                assertThat(movie.getMetadataFetchedAt()).isNull();
                assertThat(movie.getImageMetadataFetchedAt()).isNull();
                assertThat(movie.getBackdropUrl()).isNull();
                assertThat(movie.getLogoUrl()).isNull();
                assertThat(em.createNativeQuery("select price_per_person from showtimes").getResultList()).isEmpty();
                assertThat(em.createNativeQuery("select seed_key from screens").getResultList()).isEmpty();
            }
        }
    }
}
