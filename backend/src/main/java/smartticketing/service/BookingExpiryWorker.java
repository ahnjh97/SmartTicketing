package smartticketing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.atomic.AtomicBoolean;

/** 후보 조회에는 잠금을 유지하지 않는다. 그룹마다 독립된 서비스 트랜잭션으로 복구한다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpiryWorker {
    private final BookingHoldService holds;
    private final AdminMaintenanceGate gate;
    @Value("${booking.expiry.batch-size:100}")
    private int batchSize = 100;
    @Value("${booking.expiry.max-batches:10}")
    private int maxBatches = 10;
    @Value("${booking.expiry.run-budget-ms:1000}")
    private long runBudgetMs = 1000;
    private final AtomicBoolean running = new AtomicBoolean();
    private java.time.LocalDateTime scanCutoff;
    private BookingHoldService.ExpiryCandidate scanCursor;
    public record RecoveryStats(int attempted, int released, int failed, long remaining, long oldestDelayMs, long elapsedMs) {}
    private volatile RecoveryStats lastRun = new RecoveryStats(0, 0, 0, 0, 0, 0);
    public RecoveryStats lastRun() { return lastRun; }
    public BookingExpiryWorker(BookingHoldService holds, AdminMaintenanceGate gate) { this.holds = holds; this.gate = gate; }

    @EventListener(ApplicationReadyEvent.class)
    public void restartRecovery() { recover(); }

    @Scheduled(fixedDelayString = "${booking.expiry.delay-ms:1000}")
    public void recover() {
        if (!running.compareAndSet(false, true)) return;
        try { gate.background(this::recoverAvailable); }
        finally { running.set(false); }
    }
    private void recoverAvailable() {
        long started = System.nanoTime();
        if (scanCutoff == null) scanCutoff = holds.now();
        var cutoff = scanCutoff;
        BookingHoldService.ExpiryCandidate cursor = scanCursor;
        int attempted = 0, released = 0, failed = 0;
        // A fixed cutoff and (expiry,id) cursor prevent failed rows from starving later ones.
        // The budget is checked between transactions; an in-flight expiry is never interrupted.
        outer: for (int batch = 0; batch < Math.max(1, maxBatches); batch++) {
            var candidates = holds.expiredBatch(cutoff, cursor, Math.max(1, batchSize));
            if (candidates.isEmpty()) { scanCutoff = null; scanCursor = null; break; }
            for (var candidate : candidates) {
                attempted++;
                try { if (holds.expire(candidate.groupId())) released++; }
                catch (RuntimeException failure) {
                    failed++;
                    log.warn("선점 만료 복구 실패 groupId={}, errorType={}", candidate.groupId(), failure.getClass().getSimpleName());
                }
                cursor = candidate;
                scanCursor = cursor;
                if ((System.nanoTime() - started) / 1_000_000 >= Math.max(1, runBudgetMs)) break outer;
            }
            if (candidates.size() < Math.max(1, batchSize)) { scanCutoff = null; scanCursor = null; break; }
        }
        var backlog = holds.expiryBacklog();
        lastRun = new RecoveryStats(attempted, released, failed, backlog.count(), backlog.oldestDelayMs(), (System.nanoTime() - started) / 1_000_000);
        if (backlog.count() > 0 || failed > 0) log.warn("선점 만료 처리 attempted={}, released={}, failed={}, remaining={}, oldestDelayMs={}, elapsedMs={}",
                attempted, released, failed, backlog.count(), backlog.oldestDelayMs(), lastRun.elapsedMs());
        else if (attempted > 0) log.info("선점 만료 처리 released={}, elapsedMs={}", released, lastRun.elapsedMs());
    }
}
