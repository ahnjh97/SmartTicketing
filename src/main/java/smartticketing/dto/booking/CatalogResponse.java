package smartticketing.dto.booking;

import smartticketing.dto.movie.MovieMedia;
import smartticketing.entity.Movie;
import smartticketing.entity.Theater;
import smartticketing.entity.enums.TheaterBrand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** DB 내부 ID만 노출하며 JPA 엔티티를 응답으로 직렬화하지 않는다. */
public final class CatalogResponse {
    private CatalogResponse() {}

    public record Page<T>(List<T> items, int page, int size, long totalElements) {}

    public record MovieItem(Long id, String title, String posterUrl, Integer runningTime, String rating) {
        public static MovieItem from(Movie m) {
            return new MovieItem(m.getId(), m.getTitle(), m.getPosterUrl(), m.getRunningTime(), m.getRating());
        }
    }

    public record MovieDetail(Long id, String title, String description, Integer runningTime,
            String rating, LocalDate releaseDate, String posterUrl, String trailerUrl, MovieMedia media) {
        public static MovieDetail from(Movie m) {
            return new MovieDetail(m.getId(), m.getTitle(), m.getDescription(), m.getRunningTime(),
                    m.getRating(), m.getReleaseDate(), m.getPosterUrl(), m.getTrailerUrl(), MovieMedia.from(m));
        }
    }

    public record TheaterItem(Long id, String name, TheaterBrand brand, String address,
            BigDecimal latitude, BigDecimal longitude) {
        public static TheaterItem from(Theater t) {
            return new TheaterItem(t.getId(), t.getName(), t.getBrand(), t.getAddress(), t.getLatitude(), t.getLongitude());
        }
    }
}
