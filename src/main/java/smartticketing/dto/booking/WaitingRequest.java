package smartticketing.dto.booking;

import jakarta.validation.constraints.*;
import java.util.List;

public record WaitingRequest(@NotEmpty List<@NotNull @Positive Long> showtimeIds,
        smartticketing.entity.enums.SeatPosition seatZone, List<@NotNull @Positive Long> seatIds) {
    public WaitingRequest(List<Long> showtimeIds) { this(showtimeIds, null, null); }
    public WaitingRequest(List<Long> showtimeIds, smartticketing.entity.enums.SeatPosition seatZone) { this(showtimeIds, seatZone, null); }
}
