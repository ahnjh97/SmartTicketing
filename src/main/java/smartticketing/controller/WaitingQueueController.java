package smartticketing.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import smartticketing.entity.WaitingQueue;
import smartticketing.service.WaitingQueueService;

@RestController
@RequestMapping("/api/waiting-queues")
public class WaitingQueueController {
    private final WaitingQueueService service;

    public WaitingQueueController(WaitingQueueService service) {
        this.service = service;
    }

    /**
     * 대기열 승급 지점에서 호출한다.
     * WAITING -> NOTIFIED + 5분 기회 + QUEUE_TURN 알림
     */
    @PostMapping("/{queueId}/notify-turn")
    public ResponseEntity<Void> notifyTurn(@PathVariable Long queueId) {
        service.notifyTurn(queueId);
        return ResponseEntity.noContent().build();
    }
}
