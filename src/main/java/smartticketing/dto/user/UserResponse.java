package smartticketing.dto.user;

import smartticketing.entity.enums.SeatPosition;
import smartticketing.entity.enums.SocialProvider;
import smartticketing.entity.enums.UserStatus;

import java.time.LocalDate;
import java.util.List;

public record UserResponse(
        Long id,
        String name,
        LocalDate birthDate,
        String loginId,
        String email,
        String nickname,
        String address,
        UserStatus status,
        List<PreferredTheaterResponse> preferredTheaters,
        List<PreferredSeatResponse> preferredSeats,
        List<SocialProvider> linkedProviders
) {

    public record PreferredTheaterResponse(
            Long theaterId,
            String theaterName,
            String brand,
            Integer priority
    ) {
    }

    public record PreferredSeatResponse(
            SeatPosition position,
            Integer priority
    ) {
    }
}