package smartticketing.dto.booking;

import smartticketing.entity.enums.*;
import java.time.OffsetDateTime;
import java.util.List;

public record ReservationResponse(Long id, Long groupId, ReservationStatus status, ReservationType reservationType,
        Long movieId, String movieTitle, Long showtimeId, Long theaterId, String theaterName,
        Long screenId, String screenName, OffsetDateTime startTime, OffsetDateTime endTime,
        List<Long> seatIds, List<String> seatLabels, Integer totalAmount,
        OffsetDateTime expiresAt, OffsetDateTime serverTime, List<SeatPrice> seatPrices) {
    public record SeatPrice(Long seatId, String audienceType, Integer price) {}
}
