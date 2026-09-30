package smartticketing.dto.booking;

import java.util.List;

public record BookingSeedResult(int createdScreens, int createdSeats, int createdShowtimes,
                                int createdShowtimeSeats, List<Long> missingRuntimeMovieIds,
                                List<String> warnings) {}
