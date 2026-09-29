package com.SmartTicketing.SmartTicketing.controller;

import com.SmartTicketing.SmartTicketing.auth.AuthService;
import com.SmartTicketing.SmartTicketing.auth.CustomOAuth2UserService;
import com.SmartTicketing.SmartTicketing.dto.auth.*;
import com.SmartTicketing.SmartTicketing.dto.user.UserResponse;
import com.SmartTicketing.SmartTicketing.entity.enums.SocialProvider;
import jakarta.servlet.http.*;
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
    private final String frontend;

    public AuthController(AuthService a, @Value("${app.frontend-url:http://localhost:5173}") String f) {
        auth = a;
        frontend = f;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest r) {
        return ResponseEntity.ok(auth.signup(r));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest r) {
        return ResponseEntity.ok(auth.login(r));
    }

    @GetMapping("/check-login-id")
    public ResponseEntity<Map<String, Boolean>> check(@RequestParam String loginId) {
        return ResponseEntity.ok(Map.of("available", !auth.isLoginIdTaken(loginId)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/link/{provider}")
    public ResponseEntity<LinkUrlResponse> link(@PathVariable SocialProvider provider, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        request.getSession(true).setAttribute(CustomOAuth2UserService.LINK_USER_ID, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.ok(new LinkUrlResponse(request.getRequestURL().toString().replace(request.getRequestURI(), "") + "/oauth2/authorization/" + provider.name().toLowerCase()));
    }
}
