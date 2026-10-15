package smartticketing.service;

import smartticketing.entity.BookingOutboxEvent.Type;
import java.util.Set;

/** At-least-once delivery: implementations must tolerate repeats and out-of-order versions.
 * Read current DB state; event IDs are not a global commit watermark.
 * External side effects must be idempotent even if acknowledgement or another handler fails. */
public interface BookingOutboxHandler {
    Set<Type> types();
    void handle(BookingOutboxStore.Delivery event);

    /** Same show and schema only. Default preserves every event's side effects. */
    default void handleBatch(java.util.List<BookingOutboxStore.Delivery> events) {
        events.forEach(this::handle);
    }
}
