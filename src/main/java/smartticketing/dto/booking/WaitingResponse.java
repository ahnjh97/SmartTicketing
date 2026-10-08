package smartticketing.dto.booking;

import smartticketing.entity.enums.*;
import java.time.OffsetDateTime;
import java.util.List;

public record WaitingResponse(Long groupId, BookingGroupStatus groupStatus, Long activeReservationId,
        List<Item> items, List<Choice> choices, OffsetDateTime serverTime, long nextPollAfterMs) {
    public WaitingResponse(Long groupId, BookingGroupStatus groupStatus, Long activeReservationId,
                           List<Item> items, List<Choice> choices, OffsetDateTime serverTime) {
        this(groupId, groupStatus, activeReservationId, items, choices, serverTime,
                activeReservationId != null ? 3000 : items.stream().anyMatch(item -> item.status() == QueueStatus.WAITING
                        || item.status() == QueueStatus.PAUSED) ? 5000 : 10000);
    }
    public record Item(Long id, Long showtimeId, int queueNumber, long aheadCount, QueueStatus status,
            OffsetDateTime opportunityExpiresAt, String theaterName, String screenName, OffsetDateTime startTime, SeatPosition seatZone,
            List<Long> seatIds, List<String> seatLabels) {}
    public record Choice(Long showtimeId, String theaterName, String screenName, OffsetDateTime startTime, OffsetDateTime endTime) {}
}
