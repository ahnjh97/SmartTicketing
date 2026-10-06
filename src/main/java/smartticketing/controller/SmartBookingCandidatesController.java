package smartticketing.controller;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.CreateBookingGroupRequest;
import smartticketing.service.SmartBookingCandidatesService;
import smartticketing.util.CurrentUser;

@RestController
@RequestMapping("/api/smart-booking-candidates")
public class SmartBookingCandidatesController {
    private final SmartBookingCandidatesService plans;
    private final CurrentUser current;
    public SmartBookingCandidatesController(SmartBookingCandidatesService plans, CurrentUser current) { this.plans=plans; this.current=current; }
    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @Valid @RequestBody CreateBookingGroupRequest request) {
        var result=plans.create(current.id(jwt),key,request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    @GetMapping
    public SmartBookingCandidatesService.Candidates get(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long selected) { return plans.get(current.id(jwt),selected); }
}
