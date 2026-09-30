package smartticketing.controller;

import smartticketing.dto.user.*;
import smartticketing.service.UserService;
import smartticketing.util.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;
    private final CurrentUser current;

    public UserController(UserService s, CurrentUser c) {
        service = s;
        current = c;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(service.getMe(current.id(jwt)));
    }

    @GetMapping("/preference-options")
    public ResponseEntity<PreferenceOptionResponse> options() {
        return ResponseEntity.ok(service.options());
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UserUpdateRequest r) {
        return ResponseEntity.ok(service.update(current.id(jwt), r));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt) {
        service.withdraw(current.id(jwt));
        return ResponseEntity.noContent().build();
    }
}
