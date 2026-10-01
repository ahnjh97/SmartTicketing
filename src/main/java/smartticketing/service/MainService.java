package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.dto.movie.MainChartResponseDto;
import smartticketing.dto.movie.MovieChartResponseDto;
import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class MainService {

    private final MovieRepository movieRepository;
    private final String referenceDate;

    public MainService(
            MovieRepository movieRepository,
            @Value("${app.reference-date:}") String referenceDate) {
        this.movieRepository = movieRepository;
        this.referenceDate = referenceDate;
    }

    public MainChartResponseDto getMainChart() {
        LocalDate baseDate = getBaseDate();
        long totalAudience = movieRepository.sumActiveAudienceCount();

        List<MovieChartResponseDto> nowShowing = toChart(
                movieRepository.findTop10ByActiveTrueAndReleaseDateLessThanEqualOrderByAudienceCountDescReleaseDateDesc(baseDate),
                totalAudience);
        List<MovieChartResponseDto> comingSoon = toChart(
                movieRepository.findTop10ByActiveTrueAndReleaseDateAfterOrderByReleaseDateAscTitleAsc(baseDate),
                totalAudience);

        return new MainChartResponseDto(nowShowing, comingSoon);
    }

    // 설정된 기준일이 있으면 그 날짜, 없으면 오늘(한국 시간)
    private LocalDate getBaseDate() {
        return referenceDate.isBlank()
                ? LocalDate.now(ZoneId.of("Asia/Seoul"))
                : LocalDate.parse(referenceDate);
    }

    private List<MovieChartResponseDto> toChart(List<Movie> movies, long totalAudience) {
        return movies.stream()
                .map(movie -> MovieChartResponseDto.from(movie, calculateBookingRate(movie.getAudienceCount(), totalAudience)))
                .toList();
    }

    // 예매율 = 이 영화 누적관객수 ÷ 전체 누적관객수 × 100 (소수점 첫째 자리)
    private double calculateBookingRate(long audienceCount, long totalAudience) {
        if (totalAudience == 0) {
            return 0.0;
        }
        return Math.round(audienceCount * 1000.0 / totalAudience) / 10.0;
    }
}