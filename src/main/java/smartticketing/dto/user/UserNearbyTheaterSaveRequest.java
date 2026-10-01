package smartticketing.dto.user;

import java.math.BigDecimal;

public record UserNearbyTheaterSaveRequest(
        Long theaterId,
        BigDecimal distanceMeters,
        Integer travelTimeMinutes,
        Integer priority
) {}
