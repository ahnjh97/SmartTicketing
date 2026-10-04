package smartticketing.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import smartticketing.service.BookingRecoveryService;
import smartticketing.service.BookingActivityService;
import smartticketing.util.CurrentUser;

@RestController
@RequestMapping("/api/booking-groups")
public class BookingRecoveryController {
    private final BookingRecoveryService recovery;
    private final CurrentUser current;
    private final BookingActivityService activity;
    public BookingRecoveryController(BookingRecoveryService recovery, CurrentUser current, BookingActivityService activity) {
        this.recovery = recovery; this.current = current; this.activity = activity;
    }
    @GetMapping("/active")
    public java.util.List<BookingActivityService.Item> active(@AuthenticationPrincipal Jwt jwt) {
        return activity.active(current.id(jwt));
    }
    @GetMapping
    public BookingRecoveryService.Page list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long before) {
        return recovery.list(current.id(jwt), before);
    }
    @GetMapping("/{id}/recovery")
    public BookingRecoveryService.Item one(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return recovery.one(current.id(jwt), id);
    }
}
