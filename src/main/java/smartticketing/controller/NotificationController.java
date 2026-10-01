package smartticketing.controller;

import smartticketing.dto.notification.NotificationResponse;
import smartticketing.service.NotificationService;
import smartticketing.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    private final CurrentUser current;

    public NotificationController(NotificationService s, CurrentUser c) {
        service = s;
        current = c;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ResponseEntity.ok(service.list(current.id(jwt), unreadOnly));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.read(current.id(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        service.readAll(current.id(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.delete(current.id(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
