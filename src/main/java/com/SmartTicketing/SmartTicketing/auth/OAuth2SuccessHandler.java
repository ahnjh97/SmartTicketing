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

@Component
public class OAuth2SuccessHandler
        implements AuthenticationSuccessHandler {

    private final AuthService auth;
    private final String frontend;
    private final HttpServletRequest request;

    public OAuth2SuccessHandler(
            AuthService a,
            @Value("${app.frontend-url:http://localhost:5173}")
            String f,
            HttpServletRequest r
    ) {
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

        OAuth2User principal =
                token.getPrincipal();

        SocialProvider provider =
                SocialProvider.valueOf(
                        token
                                .getAuthorizedClientRegistrationId()
                                .toUpperCase()
                );

        Long linkId = linkUserId();


        if (linkId != null) {

            String providerId =
                    String.valueOf(
                            principal.getAttributes()
                                    .get("providerUserId")
                    );

            String email =
                    (String) principal.getAttributes()
                            .get("email");

            auth.linkSocialAccount(
                    linkId,
                    provider,
                    providerId,
                    email
            );

            var session =
                    request.getSession(false);

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

        String providerId =
                String.valueOf(
                        principal.getAttributes()
                                .get("providerUserId")
                );

        String name =
                (String) principal.getAttributes()
                        .get("name");

        String email =
                (String) principal.getAttributes()
                        .get("email");

        var session =
                request.getSession(true);

        session.setAttribute(
                CustomOAuth2UserService.SOCIAL_PROVIDER,
                provider.name()
        );

        session.setAttribute(
                CustomOAuth2UserService.SOCIAL_PROVIDER_USER_ID,
                providerId
        );

        session.setAttribute(
                CustomOAuth2UserService.SOCIAL_NAME,
                name
        );

        session.setAttribute(
                CustomOAuth2UserService.SOCIAL_EMAIL,
                email
        );

        res.sendRedirect(
                frontend + "/signup?social="
                        + provider.name()
        );
    }

    private Long linkUserId() {

        var session =
                request.getSession(false);

        if (session == null) {
            return null;
        }

        Object value =
                session.getAttribute(
                        CustomOAuth2UserService.LINK_USER_ID
                );

        return value instanceof Long
                ? (Long) value
                : null;
    }
}