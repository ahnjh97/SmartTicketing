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
    public BookingWaitingWorker(BookingWaitingDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${booking.waiting.delay-ms:3000}")
    public void sweep() {
        for (var show : dispatcher.pendingShows()) {
            try { dispatcher.dispatch(show); }
            catch (RuntimeException failure) { log.warn("Waiting allocation deferred: show={}, error={}", show, failure.getClass().getSimpleName()); }
        }
    }
}
