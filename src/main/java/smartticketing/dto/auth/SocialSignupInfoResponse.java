package smartticketing.dto.auth;

import smartticketing.entity.enums.SocialProvider;

public record SocialSignupInfoResponse(
        SocialProvider provider,
        String name,
        String email
) {
}