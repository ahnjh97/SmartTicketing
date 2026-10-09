package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import smartticketing.entity.BookingOutboxEvent.Type;
import java.util.*;

/** Allocation is idempotent; projection catches up committed changes using the outbox revision range. */
@Component
public class BookingWaitingOutboxHandler implements BookingOutboxHandler {
    private final BookingWaitingDispatcher dispatcher;
    private final BookingWaitingProjection projection;
    private final boolean dispatchEnabled;
    public BookingWaitingOutboxHandler(BookingWaitingDispatcher dispatcher,BookingWaitingProjection projection,
            @Value("${booking.waiting.enabled:true}") boolean dispatchEnabled) {
        this.dispatcher=dispatcher; this.projection=projection; this.dispatchEnabled=dispatchEnabled;
    }
    public Set<Type> types() { return EnumSet.allOf(Type.class); }
    @Override public void handleBatch(List<BookingOutboxStore.Delivery> events) {
        // These are invalidations, not event payloads: one current-state reconciliation
        // covers every claimed revision. Never absorb an event claimed after this batch.
        if(events.isEmpty()) return;
        if(events.stream().anyMatch(e -> e.schemaVersion()!=1 || !e.showtimeId().equals(events.getFirst().showtimeId())))
            throw new IllegalArgumentException("Incompatible booking event batch");
        handle(events.stream().max(Comparator.comparingLong(BookingOutboxStore.Delivery::aggregateVersion)).orElseThrow());
    }
    public void handle(BookingOutboxStore.Delivery event) {
        if(event.schemaVersion()!=1) throw new IllegalArgumentException("Unsupported booking event schema");
        // Publish the committed queue change before potentially slow/busy allocation.
        // Redis failure must never prevent seats from being assigned in MySQL;
        // the final update below propagates failure so the durable event is retried.
        if(dispatchEnabled) {
            try { projection.update(event.showtimeId()); }
            catch(RuntimeException unavailable) { /* Retry after the allocation attempt. */ }
            dispatcher.dispatch(event.showtimeId());
        }
        projection.update(event.showtimeId());
    }
}
