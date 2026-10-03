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
    private final smartticketing.service.AdminTaskService tasks;
    public AdminBookingController(AdminBookingService service, smartticketing.service.AdminTaskService tasks) { this.service = service; this.tasks = tasks; }
    @PostMapping("/preview")
    public AdminBookingService.Preview preview(@RequestBody AdminBookingService.Scope scope) { return service.preview(scope); }
    @PostMapping("/execute")
    public smartticketing.service.AdminTaskService.Status execute(@RequestBody AdminBookingService.Request request) {
        return tasks.submit("예매 처리", task -> {
            task.progress(0, 0, "예매 기록·좌석 처리 중");
            if (request.scope() != null && "global".equals(request.scope().mode())) {
                var shows = service.globalShowtimes(request); int completed = 0;
                for (long id : shows) {
                    task.progress(completed, shows.size(), "회차 #" + id + " 예매 기록 삭제 중");
                    service.purgeShow(id);
                    task.progress(++completed, shows.size(), "회차 " + completed + "개 처리 완료");
                }
                task.result(service.purgeRemainingGroups());
                return;
            }
            var result = service.execute(request);
            log.info("관리자 예매 처리: 회차={} 작업={} 예약={} 대기={}", request.scope().showtimeId(), request.scope().action(), result.reservations(), result.waitingQueues());
            task.result(result);
        });
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "동시 작업 또는 연결 데이터로 처리하지 못했습니다. 다시 확인해주세요."));
    }
}
