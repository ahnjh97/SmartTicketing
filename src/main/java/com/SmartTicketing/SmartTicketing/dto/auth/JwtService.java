package com.SmartTicketing.SmartTicketing.dto.auth;

import lombok.Getter;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    @Getter
    private final long expirationSeconds;

    public JwtService(JwtEncoder encoder, Environment env) {
        this.encoder = encoder;
        this.expirationSeconds = Long.parseLong(env.getProperty("app.jwt.expiration-seconds", "3600"));
    }

    public String issueAccessToken(Long userId) {
        Instant now = Instant.now();
        JwtClaimsSet c = JwtClaimsSet.builder().issuer("SmartTicketing").subject(String.valueOf(userId)).issuedAt(now).expiresAt(now.plusSeconds(expirationSeconds)).claim("type", "access").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), c)).getTokenValue();
    }

}
