package smartticketing.service;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Rebuild even after Redis loss when all outbox events were already acknowledged. */
@Component
@ConditionalOnProperty(name="app.cache.enabled",havingValue="true")
public class BookingWaitingProjectionWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BookingWaitingProjectionWorker.class);
    private final BookingWaitingProjection projection;
    private final AdminMaintenanceGate gate;
    private long after;
    public BookingWaitingProjectionWorker(BookingWaitingProjection projection,AdminMaintenanceGate gate) { this.projection=projection; this.gate=gate; }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${booking.waiting.redis-repair-ms:10000}")
    public synchronized void repair() {
        gate.background(() -> {
            long start=System.nanoTime();
            var shows=projection.activeShows(after,100);
            for(var show:shows) {
                try { projection.repairIfNeeded(show); }
                catch(RuntimeException failure) {
                    // Advance even on failure: retry on the next cycle without starving later shows.
                    log.warn("Waiting projection repair deferred: show={}, error={}",show,failure.getClass().getSimpleName());
                }
                after=show;
                if(System.nanoTime()-start>1_000_000_000L) return;
            }
            if(shows.size()<100) after=0;
        });
    }
}
