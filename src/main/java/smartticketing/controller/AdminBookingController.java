package smartticketing.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import smartticketing.service.AdminBookingService;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminBookingController.class);
    private final AdminBookingService service;
    public AdminBookingController(AdminBookingService service) { this.service = service; }
    @PostMapping("/preview")
    public AdminBookingService.Preview preview(@RequestBody AdminBookingService.Scope scope) { return service.preview(scope); }
    @PostMapping("/execute")
    public AdminBookingService.Preview execute(@RequestBody AdminBookingService.Request request) {
        var result = service.execute(request);
        log.info("관리자 예매 처리: 회차={} 작업={} 예약={} 대기={}", request.scope().showtimeId(), request.scope().action(), result.reservations(), result.waitingQueues());
        return result;
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "동시 작업 또는 연결 데이터로 처리하지 못했습니다. 다시 확인해주세요."));
    }
}
