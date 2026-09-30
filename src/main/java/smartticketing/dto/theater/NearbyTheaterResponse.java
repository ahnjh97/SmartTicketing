package smartticketing.dto.theater;

import smartticketing.entity.enums.TheaterBrand;

public record NearbyTheaterResponse(
        Long theaterId,
        String name,
        TheaterBrand brand,
        String address,
        String kakaoPlaceId,
        double latitude,
        double longitude,
        int distance,
        String placeUrl,
        Integer transitDistance,
        Integer transitMinutes,
        Integer walkDistance,
        Integer walkMinutes
) {
}