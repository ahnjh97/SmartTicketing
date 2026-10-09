package smartticketing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name="booking.outbox.enabled",havingValue="true",matchIfMissing=true)
public class BookingOutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(BookingOutboxWorker.class);
    private final BookingOutboxStore store;
    private final List<BookingOutboxHandler> handlers;
    private final AdminMaintenanceGate gate;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicLong backlog=new AtomicLong();
    private final Counter processedCounter;
    private final Counter retryCounter;
    private final Timer processingTimer;
    private final Timer delayTimer;
    @Value("${booking.outbox.max-events:100}") private int maxEvents=100;
    @Value("${booking.outbox.lease-seconds:30}") private long leaseSeconds=30;
    @Value("${booking.outbox.run-budget-ms:1000}") private long runBudgetMs=1000;

    public BookingOutboxWorker(BookingOutboxStore store, List<BookingOutboxHandler> handlers, AdminMaintenanceGate gate, Clock clock, MeterRegistry registry) {
        this.store=store; this.handlers=List.copyOf(handlers); this.gate=gate; this.clock=clock;
        processedCounter=registry.counter("booking.outbox.processed");
        retryCounter=registry.counter("booking.outbox.retry");
        processingTimer=registry.timer("booking.outbox.processing");
        delayTimer=registry.timer("booking.outbox.delay");
        registry.gauge("booking.outbox.backlog", backlog);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString="${booking.outbox.recovery-delay-ms:10000}")
    public void recover() {
        if (!running.compareAndSet(false,true)) return;
        try { gate.background(this::deliver); }
        finally { running.set(false); }
    }

    private void deliver() {
        var types=EnumSet.noneOf(Type.class);
        handlers.forEach(handler -> types.addAll(handler.types()));
        if (types.isEmpty()) return;
        long start=System.nanoTime();
        for (int count=0;count<Math.max(1,maxEvents);count++) {
            var next=store.claim(types,LocalDateTime.now(clock),Duration.ofSeconds(Math.max(1,leaseSeconds)));
            if (next.isEmpty()) break;
            var event=next.get();
            delayTimer.record(Duration.between(event.createdAt(),LocalDateTime.now(clock)));
            long processingStart=System.nanoTime();
            try {
                for (var handler : handlers) if (handler.types().contains(event.type())) handler.handle(event);
                store.complete(event,LocalDateTime.now(clock));
                processedCounter.increment();
            } catch (RuntimeException failure) {
                store.retry(event,LocalDateTime.now(clock),failure);
                retryCounter.increment();
                log.warn("Outbox deferred id={}, show={}, attempt={}, error={}",event.id(),event.showtimeId(),event.attempt(),failure.getClass().getSimpleName());
            } finally {
                processingTimer.record(System.nanoTime()-processingStart,java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            if ((System.nanoTime()-start)/1_000_000>=Math.max(1,runBudgetMs)) break;
        }
    }

    @Scheduled(fixedDelayString="${booking.outbox.metrics-delay-ms:10000}")
    public void refreshBacklogMetric() {
        backlog.set(store.pendingCount(LocalDateTime.now(clock)));
    }
}
