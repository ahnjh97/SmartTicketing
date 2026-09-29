package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2SuccessHandler
        implements AuthenticationSuccessHandler {

    private final JwtService jwt;
    private final AuthService auth;
    private final String frontend;
    private final HttpServletRequest request;

    public OAuth2SuccessHandler(
            JwtService j,
            AuthService a,
            @Value("${app.frontend-url:http://localhost:5173}")
            String f,
            HttpServletRequest r
    ) {
        jwt = j;
        auth = a;
        frontend = f;
        request = r;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest req,
            HttpServletResponse res,
            Authentication authentication
    ) throws IOException {

        OAuth2AuthenticationToken token =
                (OAuth2AuthenticationToken) authentication;

        OAuth2User p = token.getPrincipal();

        SocialProvider provider =
                SocialProvider.valueOf(
                        token
                                .getAuthorizedClientRegistrationId()
                                .toUpperCase()
                );

        Long linkId = linkUserId();

        String providerId =
                String.valueOf(
                        p.getAttributes()
                                .get("providerUserId")
                );

        String email =
                (String) p.getAttributes()
                        .get("email");

        if (linkId != null) {

            auth.linkSocialAccount(
                    linkId,
                    provider,
                    providerId,
                    email
            );

            var session = request.getSession(false);

            if (session != null) {
                session.removeAttribute(
                        CustomOAuth2UserService.LINK_USER_ID
                );
            }

            res.sendRedirect(
                    frontend
                            + "/oauth2/callback?linked="
                            + provider.name()
            );

            return;
        }

        Object id =
                p.getAttributes()
                        .get("userId");

        String access =
                jwt.issueAccessToken(
                        ((Number) id).longValue()
                );

        res.sendRedirect(
                frontend
                        + "/oauth2/callback#token="
                        + URLEncoder.encode(
                        access,
                        StandardCharsets.UTF_8
                )
        );
    }

    private Long linkUserId() {

        var s =
                request.getSession(false);

        if (s == null) {
            return null;
        }

        Object v =
                s.getAttribute(
                        CustomOAuth2UserService.LINK_USER_ID
                );

        return v instanceof Long
                ? (Long) v
                : null;
    }
}

