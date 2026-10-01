package smartticketing.dto.movie;

import smartticketing.entity.Movie;

import java.time.LocalDate;

// 메인 차트에 보여줄 영화 한 편
public record MovieChartResponseDto(
        Long id,
        Long tmdbMovieId,
        String title,
        String posterUrl,
        Integer runningTime,
        String rating,
        LocalDate releaseDate,
        long audienceCount,
        double bookingRate
) {
    public static MovieChartResponseDto from(Movie movie, double bookingRate) {
        return new MovieChartResponseDto(
                movie.getId(),
                movie.getTmdbMovieId(),
                movie.getTitle(),
                movie.getPosterUrl(),
                movie.getRunningTime(),
                movie.getRating(),
                movie.getReleaseDate(),
                movie.getAudienceCount(),
                bookingRate
        );
    }
}