package smartticketing.controller;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.*;
import smartticketing.service.BookingWaitingService;
import smartticketing.util.CurrentUser;
import java.util.Map;

@RestController
@RequestMapping("/api/booking-groups")
public class BookingWaitingController {
    private final BookingWaitingService waiting;
    private final CurrentUser current;
    public BookingWaitingController(BookingWaitingService waiting, CurrentUser current) { this.waiting = waiting; this.current = current; }

    @GetMapping("/{id}/waiting-queues")
    public WaitingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { return waiting.get(current.id(jwt), id); }

    @PostMapping("/{id}/waiting-queues")
    public ResponseEntity<String> register(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @Valid @RequestBody WaitingRequest request) {
        return result(waiting.register(current.id(jwt), id, key, request));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody(required=false) Map<String,Object> body) {
        if (body != null && !body.isEmpty()) throw new IllegalArgumentException("그룹 전체 취소만 가능합니다.");
        return result(waiting.cancel(current.id(jwt), id, key));
    }
    private ResponseEntity<String> result(BookingResult r) { return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body()); }
}
