package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class CustomOAuth2UserService
        implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String LINK_USER_ID =
            "OAUTH2_LINK_USER_ID";

    public static final String SOCIAL_PROVIDER =
            "SOCIAL_SIGNUP_PROVIDER";

    public static final String SOCIAL_PROVIDER_USER_ID =
            "SOCIAL_SIGNUP_PROVIDER_USER_ID";

    public static final String SOCIAL_NAME =
            "SOCIAL_SIGNUP_NAME";

    public static final String SOCIAL_EMAIL =
            "SOCIAL_SIGNUP_EMAIL";

    private final DefaultOAuth2UserService delegate =
            new DefaultOAuth2UserService();

    private final AuthService authService;
    private final HttpServletRequest request;

    public CustomOAuth2UserService(
            AuthService a,
            HttpServletRequest r
    ) {
        authService = a;
        request = r;
    }

    @Override
    public OAuth2User loadUser(
            OAuth2UserRequest req
    ) {
        OAuth2User source =
                delegate.loadUser(req);

        String registrationId =
                req.getClientRegistration()
                        .getRegistrationId();

        SocialProvider provider =
                SocialProvider.valueOf(
                        registrationId.toUpperCase()
                );

        OAuth2UserInfo info =
                OAuth2UserInfo.from(
                        registrationId,
                        source.getAttributes()
                );

        if (info.providerUserId() == null
                || info.providerUserId().isBlank()) {

            throw new IllegalStateException(
                    "소셜 계정 정보를 가져오지 못했습니다."
            );
        }

        Long linkId = linkUserId();

        if (linkId != null) {

            var user =
                    authService.getUserForLink(linkId);

            Map<String, Object> attrs =
                    new HashMap<>(
                            source.getAttributes()
                    );

            attrs.put("userId", user.getId());
            attrs.put("provider", provider.name());
            attrs.put(
                    "providerUserId",
                    info.providerUserId()
            );
            attrs.put(
                    "email",
                    info.email()
            );

            return new DefaultOAuth2User(
                    AuthorityUtils.createAuthorityList(
                            "ROLE_USER"
                    ),
                    attrs,
                    "userId"
            );
        }

        Map<String, Object> attrs =
                new HashMap<>(
                        source.getAttributes()
                );

        attrs.put(
                "provider",
                provider.name()
        );

        attrs.put(
                "providerUserId",
                info.providerUserId()
        );

        attrs.put(
                "name",
                info.name()
        );

        attrs.put(
                "email",
                info.email()
        );

        return new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList(
                        "ROLE_USER"
                ),
                attrs,
                "providerUserId"
        );
    }

    private Long linkUserId() {

        var session =
                request.getSession(false);

        if (session == null) {
            return null;
        }

        Object value =
                session.getAttribute(LINK_USER_ID);

        return value instanceof Long
                ? (Long) value
                : null;
    }
}