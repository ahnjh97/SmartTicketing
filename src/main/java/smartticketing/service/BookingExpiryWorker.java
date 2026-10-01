package smartticketing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 후보 조회에는 잠금을 유지하지 않는다. 그룹마다 독립된 서비스 트랜잭션으로 복구한다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpiryWorker {
    private final BookingHoldService holds;
    public BookingExpiryWorker(BookingHoldService holds) { this.holds = holds; }

    @EventListener(ApplicationReadyEvent.class)
    public void restartRecovery() { recover(); }

    @Scheduled(fixedDelayString = "${booking.expiry.delay-ms:10000}")
    public void recover() {
        for (Long id : holds.expiredGroupIds(100)) {
            try { holds.expire(id); }
            catch (RuntimeException failure) {
                // 한 건의 불일치가 다른 그룹의 복구를 막지 않으며 다음 주기에 다시 검사한다.
                log.warn("선점 만료 복구 실패 groupId={}, errorType={}", id, failure.getClass().getSimpleName());
            }
        }
    }
}
