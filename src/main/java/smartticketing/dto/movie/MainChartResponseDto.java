package smartticketing.dto.movie;

import java.util.List;

public record MainChartResponseDto(
        List<MovieChartResponseDto> nowShowing,
        List<MovieChartResponseDto> comingSoon
) {
}