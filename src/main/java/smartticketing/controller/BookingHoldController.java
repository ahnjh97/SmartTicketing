package smartticketing.controller;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.*;
import smartticketing.service.*;
import smartticketing.util.CurrentUser;

@RestController
@RequestMapping("/api")
public class BookingHoldController {
    private final BookingGroupService groups;
    private final BookingHoldService holds;
    private final CurrentUser current;
    public BookingHoldController(BookingGroupService groups, BookingHoldService holds, CurrentUser current) {
        this.groups = groups; this.holds = holds; this.current = current;
    }

    @PostMapping("/booking-groups")
    public ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateBookingGroupRequest request) {
        return result(groups.create(current.id(jwt), key, request));
    }

    @GetMapping("/booking-groups/{id}")
    public BookingGroupResponse group(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return holds.group(current.id(jwt), id);
    }

    @PostMapping("/booking-groups/{id}/manual-hold")
    public ResponseEntity<String> manual(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ManualHoldRequest request) {
        return result(holds.manual(current.id(jwt), id, key, request));
    }

    @GetMapping("/reservations/{id}")
    public ReservationResponse reservation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return holds.reservation(current.id(jwt), id);
    }

    private ResponseEntity<String> result(BookingResult result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
