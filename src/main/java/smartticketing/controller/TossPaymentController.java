package smartticketing.controller;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.TossConfirmRequest;
import smartticketing.dto.booking.TossOrderResponse;
import smartticketing.service.TossPaymentService;
import smartticketing.util.CurrentUser;

@RestController
@RequestMapping("/api/reservations")
public class TossPaymentController {
    private final TossPaymentService service;
    private final CurrentUser current;

    public TossPaymentController(TossPaymentService service, CurrentUser current) {
        this.service = service;
        this.current = current;
    }

    @PostMapping("/{id}/toss-orders")
    public TossOrderResponse createOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.createOrder(current.id(jwt), id);
    }

    @PostMapping("/{id}/toss-confirmations")
    public ResponseEntity<String> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TossConfirmRequest request) {
        var result = service.confirm(current.id(jwt), id, key, request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
