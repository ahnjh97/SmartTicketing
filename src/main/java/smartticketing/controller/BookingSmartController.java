package smartticketing.controller;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.service.BookingSmartService;
import smartticketing.util.CurrentUser;
import java.util.Map;

@RestController
@RequestMapping("/api/booking-groups")
public class BookingSmartController {
    private final BookingSmartService smart;
    private final CurrentUser current;
    public BookingSmartController(BookingSmartService smart, CurrentUser current) { this.smart = smart; this.current = current; }

    @PostMapping("/{id}/smart-hold")
    public ResponseEntity<String> hold(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) throw new IllegalArgumentException("추가 조건 없이 그룹의 저장된 조건으로 요청해주세요.");
        var result = smart.hold(current.id(jwt), id, key);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
