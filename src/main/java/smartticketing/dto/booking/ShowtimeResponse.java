package smartticketing.dto.booking;

import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SeatStatus;
import smartticketing.entity.enums.ShowtimeStatus;
import java.time.OffsetDateTime;
import java.util.List;

public final class ShowtimeResponse {
    private ShowtimeResponse() {}
    public record Items<T>(List<T> items, OffsetDateTime serverTime) {}
    public record MovieItem(Long movieId, String title, String posterUrl, Integer runningTime, String rating) {}
    public record ShowtimeItem(Long id, Long movieId, Long theaterId, Long screenId, String screenName,
            OffsetDateTime startTime, OffsetDateTime endTime, boolean endsNextDay, Integer pricePerPerson,
            long totalSeats, long availableSeats, int maxContiguousSeats, boolean layoutComplete, ShowtimeStatus status) {}
    /** id는 물리 좌석 ID. 선점 소유자/예약 ID/만료 시각은 공개하지 않는다. */
    public record SeatItem(Long id, String row, Integer number, String segment, Integer positionInSegment,
            SeatPosition position, SeatStatus status) {}
    public record SeatMap(Long showtimeId, List<SeatItem> seats, long totalSeats, long availableSeats,
            int maxContiguousSeats, boolean layoutComplete, OffsetDateTime serverTime) {}
}
