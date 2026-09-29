package com.SmartTicketing.SmartTicketing.util;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
