package com.SmartTicketing.SmartTicketing.auth;

import java.util.Map;

public record OAuth2UserInfo(
        String providerUserId,
        String email,
        String name
) {

    public static OAuth2UserInfo from(
            String provider,
            Map<String, Object> attributes
    ) {
        return switch (provider.toLowerCase()) {
            case "google" -> fromGoogle(attributes);
            case "naver" -> fromNaver(attributes);
            case "kakao" -> fromKakao(attributes);
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 소셜 로그인 제공자입니다."
            );
        };
    }

    private static OAuth2UserInfo fromGoogle(
            Map<String, Object> attributes
    ) {
        String providerUserId =
                stringValue(attributes.get("sub"));

        String email =
                stringValue(attributes.get("email"));

        String name =
                stringValue(attributes.get("name"));

        return new OAuth2UserInfo(
                providerUserId,
                email,
                name
        );
    }

    private static OAuth2UserInfo fromNaver(
            Map<String, Object> attributes
    ) {
        Map<String, Object> response =
                mapValue(attributes.get("response"));

        String providerUserId =
                stringValue(response.get("id"));

        String email =
                stringValue(response.get("email"));

        String name =
                stringValue(response.get("name"));

        return new OAuth2UserInfo(
                providerUserId,
                email,
                name
        );
    }

    private static OAuth2UserInfo fromKakao(
            Map<String, Object> attributes
    ) {
        String providerUserId =
                stringValue(attributes.get("id"));

        Map<String, Object> properties =
                mapValue(attributes.get("properties"));

        String name =
                stringValue(
                        properties.get("nickname")
                );

        return new OAuth2UserInfo(
                providerUserId,
                null,
                name
        );
    }

    private static String stringValue(
            Object value
    ) {
        if (value == null) {
            return null;
        }

        String result =
                String.valueOf(value).trim();

        return result.isBlank()
                ? null
                : result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(
            Object value
    ) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Map.of();
    }
}