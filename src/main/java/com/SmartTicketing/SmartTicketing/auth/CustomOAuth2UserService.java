package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.entity.Users;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
            "OAUTH2_SOCIAL_PROVIDER";

    public static final String SOCIAL_PROVIDER_USER_ID =
            "OAUTH2_SOCIAL_PROVIDER_USER_ID";

    public static final String SOCIAL_NAME =
            "OAUTH2_SOCIAL_NAME";

    public static final String SOCIAL_EMAIL =
            "OAUTH2_SOCIAL_EMAIL";

    private final DefaultOAuth2UserService delegate =
            new DefaultOAuth2UserService();

    private final AuthService authService;
    private final HttpServletRequest request;

    public CustomOAuth2UserService(
            AuthService authService,
            HttpServletRequest request
    ) {
        this.authService = authService;
        this.request = request;
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

        if (
                info.providerUserId() == null
                        || info.providerUserId().isBlank()
        ) {
            throw new IllegalStateException(
                    "소셜 계정 정보를 가져오지 못했습니다."
            );
        }

        Long linkUserId =
                linkUserId();

        if (linkUserId != null) {

            Users user =
                    authService.getUserForLink(
                            linkUserId
                    );

            Map<String, Object> attributes =
                    new HashMap<>(
                            source.getAttributes()
                    );

            attributes.put(
                    "userId",
                    user.getId()
            );

            attributes.put(
                    "provider",
                    provider.name()
            );

            attributes.put(
                    "providerUserId",
                    info.providerUserId()
            );

            attributes.put(
                    "email",
                    info.email()
            );

            return new DefaultOAuth2User(
                    AuthorityUtils.createAuthorityList(
                            "ROLE_USER"
                    ),
                    attributes,
                    "userId"
            );
        }

        HttpSession session =
                request.getSession(true);

        session.setAttribute(
                SOCIAL_PROVIDER,
                provider.name()
        );

        session.setAttribute(
                SOCIAL_PROVIDER_USER_ID,
                info.providerUserId()
        );

        session.setAttribute(
                SOCIAL_NAME,
                info.name()
        );

        session.setAttribute(
                SOCIAL_EMAIL,
                info.email()
        );

        Map<String, Object> attributes =
                new HashMap<>(
                        source.getAttributes()
                );

        attributes.put(
                "provider",
                provider.name()
        );

        attributes.put(
                "providerUserId",
                info.providerUserId()
        );

        attributes.put(
                "name",
                info.name()
        );

        attributes.put(
                "email",
                info.email()
        );

        return new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList(
                        "ROLE_USER"
                ),
                attributes,
                "providerUserId"
        );
    }

    private Long linkUserId() {

        HttpSession session =
                request.getSession(false);

        if (session == null) {
            return null;
        }

        Object value =
                session.getAttribute(
                        LINK_USER_ID
                );

        return value instanceof Long
                ? (Long) value
                : null;
    }
}