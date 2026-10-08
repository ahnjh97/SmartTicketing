package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import smartticketing.entity.BookingOutboxEvent.Type;
import java.util.*;

/** Allocation remains idempotent under MySQL locks; refresh always reloads committed state. */
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
    public void handle(BookingOutboxStore.Delivery event) {
        if(event.schemaVersion()!=1) throw new IllegalArgumentException("Unsupported booking event schema");
        // Redis failure must never prevent seats from being assigned in MySQL.
        if(dispatchEnabled) dispatcher.dispatch(event.showtimeId());
        projection.refresh(event.showtimeId());
    }
}
