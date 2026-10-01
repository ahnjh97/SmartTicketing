package smartticketing.dto.movie;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MovieChartResponseDto {
    private Long id;
    private Long tmdbMovieId;
    private String title;
    private String posterPath;
    private Integer runningTime;
    private String rating;
    private Long audienceCount;
    private Double bookingRate;
}
