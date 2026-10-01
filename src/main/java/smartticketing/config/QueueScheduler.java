package smartticketing.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import smartticketing.service.WaitingQueueService;

@Component
public class QueueScheduler {
    private final WaitingQueueService service;

    public QueueScheduler(WaitingQueueService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    public void expireQueueOpportunities() {
        service.expireOpportunities();
    }
}
