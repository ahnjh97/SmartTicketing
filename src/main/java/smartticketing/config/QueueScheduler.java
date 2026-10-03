package smartticketing.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import smartticketing.service.WaitingQueueService;

@Component
public class QueueScheduler {
    private final WaitingQueueService service;
    private final smartticketing.service.AdminMaintenanceGate gate;

    public QueueScheduler(WaitingQueueService service, smartticketing.service.AdminMaintenanceGate gate) {
        this.service = service;
        this.gate = gate;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireQueueOpportunities() {
        gate.background(service::expireOpportunities);
    }
}
