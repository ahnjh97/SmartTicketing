package smartticketing.controller;

import smartticketing.dto.ticket.TicketResponse;
import smartticketing.dto.ticket.TicketVerifyResponse;
import smartticketing.service.TicketService;
import smartticketing.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;
    private final CurrentUser current;
    private final String frontendUrl;

    public TicketController(
            TicketService s,
            CurrentUser c,
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        service = s;
        current = c;
        this.frontendUrl = frontendUrl;
    }

    @PostMapping("/verify/complete/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> completeVerify(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.useNow(qrCode));
    }

    @PostMapping("/verify/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> verify(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.verifyAndUse(qrCode));
    }

    @GetMapping("/verify/{qrCode}")
    public ResponseEntity<Void> verifyPage(@PathVariable String qrCode) {
        String baseUrl = frontendUrl.replaceAll("/+$", "");
        String verifyPageUrl = baseUrl + "/#/ticket/verify/"
                + java.net.URLEncoder.encode(qrCode, java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.status(302)
                .header("Location", verifyPageUrl)
                .build();
    }

    @GetMapping("/verify/status/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> verifyStatus(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.verifyStatus(qrCode));
    }

    @GetMapping
    public ResponseEntity<List<TicketResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(service.mine(current.id(jwt)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> one(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(service.one(current.id(jwt), id));
    }

    @GetMapping("/page")
    public smartticketing.dto.common.CursorPage<TicketResponse> page(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        return service.page(current.id(jwt), cursor, size);
    }

    @PostMapping("/reservations/{reservationId}")
    public ResponseEntity<TicketResponse> issue(@AuthenticationPrincipal Jwt jwt, @PathVariable Long reservationId) {
        return ResponseEntity.ok(service.issue(current.id(jwt), reservationId));
    }
}
