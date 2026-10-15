package smartticketing.service;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="booking.waiting.enabled", havingValue="true", matchIfMissing=true)
public class BookingWaitingWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingWaitingWorker.class);
    private final BookingWaitingDispatcher dispatcher;
    private final AdminMaintenanceGate gate;
    private long after;

    public BookingWaitingWorker(BookingWaitingDispatcher dispatcher, AdminMaintenanceGate gate) { this.dispatcher = dispatcher; this.gate = gate; }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${booking.waiting.delay-ms:3000}")
    public synchronized void sweep() {
        gate.background(this::sweepAvailable);
    }

    private void sweepAvailable() {
        long start=System.nanoTime();
        var shows=dispatcher.pendingShows(after,100);
        for (var show : shows) {
            try { dispatcher.dispatch(show); }
            catch (BookingDispatchGate.Busy busy) { /* Another worker owns this show; the next scan can retry. */ }
            catch (RuntimeException failure) { log.warn("Waiting allocation deferred: show={}, error={}", show, failure.getClass().getSimpleName()); }
            after=show;
            if(System.nanoTime()-start>1_000_000_000L) return;
        }
        if(shows.size()<100) after=0;
    }
}
