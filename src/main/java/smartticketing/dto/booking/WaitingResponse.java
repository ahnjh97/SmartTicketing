package smartticketing.dto.booking;

import smartticketing.entity.enums.*;
import java.time.OffsetDateTime;
import java.util.List;

public record WaitingResponse(Long groupId, BookingGroupStatus groupStatus, Long activeReservationId,
        List<Item> items, List<Choice> choices, OffsetDateTime serverTime) {
    public record Item(Long id, Long showtimeId, int queueNumber, long aheadCount, QueueStatus status,
            OffsetDateTime opportunityExpiresAt, String theaterName, String screenName, OffsetDateTime startTime) {}
    public record Choice(Long showtimeId, String theaterName, String screenName, OffsetDateTime startTime, OffsetDateTime endTime) {}
}
