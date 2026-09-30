package com.SmartTicketing.SmartTicketing.dto.auth;

import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;

public record SocialSignupInfoResponse(
        SocialProvider provider,
        String name,
        String email
) {
}