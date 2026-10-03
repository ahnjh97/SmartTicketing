package smartticketing.controller;

import smartticketing.dto.ticket.TicketResponse;
import smartticketing.dto.ticket.TicketVerifyResponse;
import smartticketing.service.TicketService;
import smartticketing.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;
    private final CurrentUser current;

    public TicketController(TicketService s, CurrentUser c) {
        service = s;
        current = c;
    }

    @PostMapping("/verify/{qrCode}")
    public ResponseEntity<TicketVerifyResponse> verify(@PathVariable String qrCode) {
        return ResponseEntity.ok(service.verifyAndUse(qrCode));
    }

    @GetMapping("/verify/{qrCode}")
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

    @PostMapping("/reservations/{reservationId}")
    public ResponseEntity<TicketResponse> issue(@AuthenticationPrincipal Jwt jwt, @PathVariable Long reservationId) {
        return ResponseEntity.ok(service.issue(current.id(jwt), reservationId));
    }
}
