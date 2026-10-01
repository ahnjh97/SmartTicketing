package smartticketing.service;

import smartticketing.dto.movie.MovieChartResponseDto;
import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MainService {

    private final MovieRepository movieRepository;

    public List<MovieChartResponseDto> getMainChartMovies() {
        List<Movie> movies = movieRepository.findByActiveTrueOrderByIdAsc();

        long totalAudienceSum = movies.stream()
                .mapToLong(Movie::getAudienceCount)
                .sum();

        return movies.stream()
                .map(movie -> {
                    long currentAudience = movie.getAudienceCount();

                    double bookingRate = (totalAudienceSum > 0)
                            ? Math.round(((double) currentAudience / totalAudienceSum * 100) * 10.0) / 10.0
                            : 0.0;

                    return MovieChartResponseDto.builder()
                            .id(movie.getId())
                            .tmdbMovieId(movie.getTmdbMovieId())
                            .title(movie.getTitle())
                            .posterPath(movie.getPosterUrl()) // getPosterPath -> getPosterUrl 수정
                            .runningTime(movie.getRunningTime())
                            .rating(movie.getRating() != null ? movie.getRating().toString() : null) // name() -> toString() 안전하게 처리
                            .audienceCount(currentAudience)
                            .bookingRate(bookingRate)
                            .build();
                })
                .sorted(Comparator.comparing(MovieChartResponseDto::getBookingRate).reversed())
                .toList();
    }
}