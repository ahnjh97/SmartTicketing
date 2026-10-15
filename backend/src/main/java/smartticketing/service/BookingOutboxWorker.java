package smartticketing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import smartticketing.entity.BookingOutboxEvent.Type;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

@Component
@ConditionalOnProperty(name="booking.outbox.enabled",havingValue="true",matchIfMissing=true)
public class BookingOutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(BookingOutboxWorker.class);
    private final BookingOutboxStore store;
    private final List<BookingOutboxHandler> handlers;
    private final Set<Type> types=EnumSet.noneOf(Type.class);
    private final AdminMaintenanceGate gate;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicReference<BookingOutboxStore.Backlog> backlog=new AtomicReference<>(new BookingOutboxStore.Backlog(0,0,0,0,0));
    private final Counter processed,retries,claimed,emptyPolls,stale,saturated;
    private final io.micrometer.core.instrument.Timer processing,delay;
    private volatile long nextPollNanos;
    private long idleDelayMs;
    private int desiredBatch=8;
    @Value("${booking.outbox.max-events:100}") private int maxEvents=100;
    @Value("${booking.outbox.batch-size:32}") private int batchSize=32;
    @Value("${booking.outbox.lease-seconds:30}") private long leaseSeconds=30;
    @Value("${booking.outbox.run-budget-ms:1000}") private long runBudgetMs=1000;
    @Value("${booking.outbox.delay-ms:250}") private long delayMs=250;
    @Value("${booking.outbox.idle-max-ms:1000}") private long idleMaxMs=1000;

    @Autowired
    public BookingOutboxWorker(BookingOutboxStore store,List<BookingOutboxHandler> handlers,AdminMaintenanceGate gate,Clock clock,MeterRegistry registry) {
        this.store=store; this.handlers=List.copyOf(handlers); this.gate=gate; this.clock=clock;
        handlers.forEach(h -> types.addAll(h.types()));
        processed=registry.counter("booking.outbox.processed");
        retries=registry.counter("booking.outbox.retry");
        claimed=registry.counter("booking.outbox.claimed");
        emptyPolls=registry.counter("booking.outbox.empty.polls");
        stale=registry.counter("booking.outbox.stale.acks");
        saturated=registry.counter("booking.outbox.saturated.runs");
        processing=registry.timer("booking.outbox.processing"); // per show/schema work unit, not per event
        delay=registry.timer("booking.outbox.delay"); // creation to successful acknowledgement
        registry.gauge("booking.outbox.backlog",backlog,b -> b.get().pending()+b.get().processing());
        registry.gauge("booking.outbox.ready",backlog,b -> b.get().ready());
        registry.gauge("booking.outbox.processing.count",backlog,b -> b.get().processing());
        registry.gauge("booking.outbox.expired.leases",backlog,b -> b.get().expired());
        registry.gauge("booking.outbox.oldest.seconds",backlog,b -> b.get().oldestSeconds());
        registry.gauge("booking.outbox.batch.limit",this,w -> Math.min(w.desiredBatch,w.batchSize));
    }
    public BookingOutboxWorker(BookingOutboxStore store,List<BookingOutboxHandler> handlers,AdminMaintenanceGate gate,Clock clock) {
        this(store,handlers,gate,clock,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Scheduled(fixedDelayString="${booking.outbox.delay-ms:250}",scheduler="bookingOutboxScheduler")
    public void poll() {
        if(nextPollNanos==0 || System.nanoTime()-nextPollNanos>=0) recover();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if(!running.compareAndSet(false,true)) return;
        try { gate.background(this::deliver); }
        finally { running.set(false); }
    }
    private record Scope(Long show,int schema) {}
    private boolean budgetExceeded(long start) {
        return System.nanoTime()-start>=TimeUnit.MILLISECONDS.toNanos(Math.max(1,runBudgetMs));
    }
    private void deliver() {
        if(types.isEmpty()) return;
        long start=System.nanoTime();
        int count=0;
        while(count<Math.max(1,maxEvents) && (count==0 || !budgetExceeded(start))) {
            int limit=Math.min(Math.min(Math.max(1,batchSize),256),Math.min(desiredBatch,Math.max(1,maxEvents)-count));
            var now=LocalDateTime.now(clock);
            long leaseStart=System.nanoTime();
            var batch=store.claimBatch(types,now,Duration.ofSeconds(Math.max(1,leaseSeconds)),limit);
            if(batch.isEmpty()) {
                emptyPolls.increment();
                desiredBatch=8;
                idleDelayMs=count>0 ? Math.max(1,delayMs) : Math.min(Math.max(delayMs,idleMaxMs),Math.max(delayMs,idleDelayMs*2));
                nextPollNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(idleDelayMs);
                return;
            }
            idleDelayMs=0; nextPollNanos=0;
            claimed.increment(batch.size());
            desiredBatch=batch.size()==limit ? Math.min(256,desiredBatch*2) : 8;
            var scopes=new LinkedHashMap<Scope,List<BookingOutboxStore.Delivery>>();
            batch.forEach(e -> scopes.computeIfAbsent(new Scope(e.showtimeId(),e.schemaVersion()),ignored -> new ArrayList<>()).add(e));
            var unstarted=new ArrayList<>(batch);
            try {
                for(var unit:scopes.values()) {
                    // The budget is soft: do not interrupt domain transactions mid-allocation.
                    // Never start more work near an expired batch lease; return it for another run.
                    if((count>0 && budgetExceeded(start)) || System.nanoTime()-leaseStart>=TimeUnit.SECONDS.toNanos(Math.max(1,leaseSeconds))/2) break;
                    unstarted.removeAll(unit);
                    long processingStart=System.nanoTime();
                    try {
                        for(var handler:handlers) {
                            var relevant=unit.stream().filter(e -> handler.types().contains(e.type())).toList();
                            if(!relevant.isEmpty()) handler.handleBatch(relevant);
                        }
                        var completedAt=LocalDateTime.now(clock);
                        int completed=store.completeBatch(unit,completedAt);
                        processed.increment(completed); stale.increment(unit.size()-completed);
                        if(completed==unit.size()) unit.forEach(e -> delay.record(Duration.ofNanos(Math.max(0,Duration.between(e.createdAt(),completedAt).toNanos()))));
                    } catch(RuntimeException failure) {
                        int deferred=store.retryBatch(unit,LocalDateTime.now(clock),failure);
                        retries.increment(deferred); stale.increment(unit.size()-deferred);
                        log.warn("Outbox deferred show={}, events={}, error={}",unit.getFirst().showtimeId(),unit.size(),failure.getClass().getSimpleName());
                    } finally { processing.record(System.nanoTime()-processingStart,TimeUnit.NANOSECONDS); }
                    count+=unit.size();
                }
            } finally {
                store.releaseBatch(unstarted,LocalDateTime.now(clock));
            }
            if(!unstarted.isEmpty()) break;
        }
        saturated.increment();
    }

    @Scheduled(fixedDelayString="${booking.outbox.metrics-delay-ms:30000}")
    public void refreshBacklogMetric() {
        gate.background(() -> backlog.set(store.backlog(LocalDateTime.now(clock))));
    }
}
