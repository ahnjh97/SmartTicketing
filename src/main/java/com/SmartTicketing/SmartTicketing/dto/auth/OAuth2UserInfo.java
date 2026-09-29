package com.SmartTicketing.SmartTicketing.dto.auth;

import java.util.Map;

public record OAuth2UserInfo(String providerUserId, String email, String name) {
    public static OAuth2UserInfo from(String registrationId, Map<String, Object> a) {
        return switch (registrationId.toLowerCase()) {
            case "google" ->
                    new OAuth2UserInfo(str(a.get("sub")), str(a.get("email")), first(str(a.get("name")), str(a.get("given_name"))));
            case "naver" -> {
                Map<String, Object> r = map(a.get("response"));
                yield new OAuth2UserInfo(str(r.get("id")), str(r.get("email")), first(str(r.get("name")), str(r.get("nickname"))));
            }
            case "kakao" -> {
                Map<String, Object> k = map(a.get("kakao_account"));
                Map<String, Object> p = map(k.get("profile"));
                yield new OAuth2UserInfo(str(a.get("id")), str(k.get("email")), first(str(p.get("nickname")), str(p.get("profile_nickname"))));
            }
            default -> throw new IllegalArgumentException("지원하지 않는 소셜 로그인입니다.");
        };
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
