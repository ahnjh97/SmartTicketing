package smartticketing.controller;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.*;
import smartticketing.service.BookingPaymentService;
import smartticketing.util.CurrentUser;

@RestController
@RequestMapping("/api/reservations")
public class BookingPaymentController {
    private final BookingPaymentService service;
    private final smartticketing.service.TossPaymentService tossPayments;
    private final CurrentUser current;
    public BookingPaymentController(BookingPaymentService service, smartticketing.service.TossPaymentService tossPayments, CurrentUser current) {
        this.service = service; this.tossPayments = tossPayments; this.current = current;
    }
    @PostMapping("/{id}/mock-payments")
    public ResponseEntity<String> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @Valid @RequestBody MockPaymentRequest request) {
        var result = service.pay(current.id(jwt), id, key, request);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    @GetMapping("/{id}/payment")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) { return service.get(current.id(jwt), id); }
    @PostMapping("/{id}/cancel")
    public ResponseEntity<String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestBody(required=false) java.util.Map<String,Object> body) {
        if (body != null && !body.isEmpty()) throw new IllegalArgumentException("전체 취소만 지원하며 취소 본문은 받지 않습니다.");
        var userId = current.id(jwt);
        var result = tossPayments.cancelIfPaidToss(userId, id, key);
        if (result == null) result = service.cancel(userId, id, key);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
