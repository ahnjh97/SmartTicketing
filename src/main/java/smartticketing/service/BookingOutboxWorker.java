package smartticketing.service;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import smartticketing.entity.BookingOutboxEvent.Type;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name="booking.outbox.enabled",havingValue="true",matchIfMissing=true)
public class BookingOutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(BookingOutboxWorker.class);
    private final BookingOutboxStore store;
    private final List<BookingOutboxHandler> handlers;
    private final AdminMaintenanceGate gate;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    @Value("${booking.outbox.max-events:100}") private int maxEvents=100;
    @Value("${booking.outbox.lease-seconds:30}") private long leaseSeconds=30;
    @Value("${booking.outbox.run-budget-ms:1000}") private long runBudgetMs=1000;

    public BookingOutboxWorker(BookingOutboxStore store, List<BookingOutboxHandler> handlers, AdminMaintenanceGate gate, Clock clock) {
        this.store=store; this.handlers=List.copyOf(handlers); this.gate=gate; this.clock=clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${booking.outbox.delay-ms:1000}")
    public void recover() {
        if (!running.compareAndSet(false,true)) return;
        try { gate.background(this::deliver); }
        finally { running.set(false); }
    }

    private void deliver() {
        var types=EnumSet.noneOf(Type.class);
        handlers.forEach(handler -> types.addAll(handler.types()));
        // Phase 3 does not install Redis/dispatch handlers. Unhandled events stay pending.
        if (types.isEmpty()) return;
        long start=System.nanoTime();
        for (int count=0;count<Math.max(1,maxEvents);count++) {
            var next=store.claim(types,LocalDateTime.now(clock),Duration.ofSeconds(Math.max(1,leaseSeconds)));
            if (next.isEmpty()) break;
            var event=next.get();
            try {
                for (var handler : handlers) if (handler.types().contains(event.type())) handler.handle(event);
                store.complete(event,LocalDateTime.now(clock));
            } catch (RuntimeException failure) {
                store.retry(event,LocalDateTime.now(clock),failure);
                log.warn("Outbox deferred id={}, show={}, attempt={}, error={}",event.id(),event.showtimeId(),event.attempt(),failure.getClass().getSimpleName());
            }
            if ((System.nanoTime()-start)/1_000_000>=Math.max(1,runBudgetMs)) break;
        }
    }
}
