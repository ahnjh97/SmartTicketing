package com.SmartTicketing.SmartTicketing.controller;

import com.SmartTicketing.SmartTicketing.auth.AuthService;
import com.SmartTicketing.SmartTicketing.auth.CustomOAuth2UserService;
import com.SmartTicketing.SmartTicketing.dto.auth.LinkUrlResponse;
import com.SmartTicketing.SmartTicketing.dto.auth.PasswordResetRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.LoginRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.SignupRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.SocialSignupInfoResponse;
import com.SmartTicketing.SmartTicketing.dto.auth.SocialSignupRequest;
import com.SmartTicketing.SmartTicketing.dto.auth.TokenResponse;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(
            AuthService auth,
            @Value("${app.frontend-url:http://localhost:5173}")
            String frontend
    ) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        return ResponseEntity.ok(
                auth.signup(request)
        );
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ResponseEntity.ok(
                auth.login(request)
        );
    }

    @PostMapping("/password-reset")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequest request
    ) {
        auth.resetPassword(
                request.identifier(),
                request.newPassword()
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/check-login-id")
    public ResponseEntity<Map<String, Boolean>> check(
            @RequestParam String loginId
    ) {
        return ResponseEntity.ok(
                Map.of(
                        "available",
                        !auth.isLoginIdTaken(loginId)
                )
        );
    }

    @GetMapping("/social-signup-info")
    public ResponseEntity<SocialSignupInfoResponse> socialSignupInfo(
            HttpServletRequest request
    ) {
        var session =
                request.getSession(false);

        if (session == null) {
            throw new IllegalStateException(
                    "소셜 회원가입 세션이 없습니다."
            );
        }

        Object provider =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_PROVIDER
                );

        Object name =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_NAME
                );

        Object email =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_EMAIL
                );

        if (
                provider == null
                        || name == null
        ) {
            throw new IllegalStateException(
                    "소셜 회원가입 정보를 찾을 수 없습니다."
            );
        }

        return ResponseEntity.ok(
                new SocialSignupInfoResponse(
                        SocialProvider.valueOf(
                                String.valueOf(provider)
                        ),
                        String.valueOf(name),
                        email == null
                                ? null
                                : String.valueOf(email)
                )
        );
    }

    @PostMapping("/social-signup")
    public ResponseEntity<TokenResponse> socialSignup(
            @Valid @RequestBody SocialSignupRequest request,
            HttpServletRequest httpRequest
    ) {
        var session =
                httpRequest.getSession(false);

        if (session == null) {
            throw new IllegalStateException(
                    "소셜 회원가입 세션이 없습니다."
            );
        }

        Object providerObject =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_PROVIDER
                );

        Object providerUserIdObject =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_PROVIDER_USER_ID
                );

        Object nameObject =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_NAME
                );

        Object emailObject =
                session.getAttribute(
                        CustomOAuth2UserService.SOCIAL_EMAIL
                );

        if (
                providerObject == null
                        || providerUserIdObject == null
                        || nameObject == null
        ) {
            throw new IllegalStateException(
                    "소셜 회원가입 정보가 없습니다."
            );
        }

        String provider =
                String.valueOf(providerObject);

        String providerUserId =
                String.valueOf(providerUserIdObject);

        String name =
                String.valueOf(nameObject);

        String email =
                emailObject == null
                        ? null
                        : String.valueOf(emailObject);

        TokenResponse response =
                auth.completeSocialSignup(
                        provider,
                        providerUserId,
                        name,
                        email,
                        request
                );

        session.removeAttribute(
                CustomOAuth2UserService.SOCIAL_PROVIDER
        );

        session.removeAttribute(
                CustomOAuth2UserService.SOCIAL_PROVIDER_USER_ID
        );

        session.removeAttribute(
                CustomOAuth2UserService.SOCIAL_NAME
        );

        session.removeAttribute(
                CustomOAuth2UserService.SOCIAL_EMAIL
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/link/{provider}")
    public ResponseEntity<LinkUrlResponse> link(
            @PathVariable SocialProvider provider,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request
    ) {
        request.getSession(true)
                .setAttribute(
                        CustomOAuth2UserService.LINK_USER_ID,
                        Long.valueOf(jwt.getSubject())
                );

        return ResponseEntity.ok(
                new LinkUrlResponse(
                        request.getRequestURL()
                                .toString()
                                .replace(
                                        request.getRequestURI(),
                                        ""
                                )
                                + "/oauth2/authorization/"
                                + provider.name().toLowerCase()
                )
        );
    }
}