package smartticketing.dto.user;

import smartticketing.entity.enums.TheaterBrand;

import java.math.BigDecimal;

public record UserNearbyTheaterResponse(
        Long theaterId,
        String name,
        TheaterBrand brand,
        String address,
        BigDecimal distanceMeters,
        Integer travelTimeMinutes,
        Integer priority
) {}
