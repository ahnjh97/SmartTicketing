package com.SmartTicketing.SmartTicketing.auth;

import com.SmartTicketing.SmartTicketing.entity.Users;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.user.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class CustomOAuth2UserService
        implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String LINK_USER_ID =
            "OAUTH2_LINK_USER_ID";

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

        String reg =
                req.getClientRegistration()
                        .getRegistrationId();

        SocialProvider p =
                SocialProvider.valueOf(
                        reg.toUpperCase()
                );

        OAuth2UserInfo info =
                OAuth2UserInfo.from(
                        reg,
                        source.getAttributes()
                );

        if (info.providerUserId() == null) {
            throw new IllegalStateException(
                    "소셜 계정 정보를 가져오지 못했습니다."
            );
        }

        Long linkId = linkUserId();

        Users user;

        if (linkId != null) {
            user = authServiceLinkTarget(linkId);
        } else {
            user = authService.findOrCreateSocialUser(
                    p,
                    info.providerUserId(),
                    info.email(),
                    info.name()
            );
        }

        Map<String, Object> attrs =
                new HashMap<>(
                        source.getAttributes()
                );

        attrs.put("userId", user.getId());
        attrs.put("provider", p.name());
        attrs.put(
                "providerUserId",
                info.providerUserId()
        );

        /*
         * Google / Naver
         * → 이메일 자동 저장
         *
         * Kakao
         * → 이메일이 없으면 null
         */
        attrs.put("email", info.email());

        return new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList(
                        "ROLE_USER"
                ),
                attrs,
                "userId"
        );
    }

    private Users authServiceLinkTarget(Long id) {
        return authService.getUserForLink(id);
    }

    private Long linkUserId() {
        var s = request.getSession(false);

        if (s == null) {
            return null;
        }

        Object v =
                s.getAttribute(LINK_USER_ID);

        return v instanceof Long
                ? (Long) v
                : null;
    }
}