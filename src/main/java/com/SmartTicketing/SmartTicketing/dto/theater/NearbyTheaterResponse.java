package com.SmartTicketing.SmartTicketing.dto.theater;

import com.SmartTicketing.SmartTicketing.entity.enums.TheaterBrand;

public record NearbyTheaterResponse(
        Long theaterId,
        String name,
        TheaterBrand brand,
        String address,
        String kakaoPlaceId,
        double latitude,
        double longitude,
        int distance,
        String placeUrl
) {
}