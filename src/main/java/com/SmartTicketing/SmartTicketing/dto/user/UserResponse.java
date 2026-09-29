package com.SmartTicketing.SmartTicketing.dto.user;

import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import com.SmartTicketing.SmartTicketing.entity.enums.UserStatus;

import java.util.List;

public record UserResponse(
        Long id, String name, String loginId, String email, String nickname, String address, UserStatus status,
        List<PreferredTheaterResponse> preferredTheaters, List<SeatPosition> preferredSeats,
        List<SocialProvider> linkedProviders
) {
    public record PreferredTheaterResponse(Long theaterId, String theaterName, String brand, Integer priority) {
    }
}
