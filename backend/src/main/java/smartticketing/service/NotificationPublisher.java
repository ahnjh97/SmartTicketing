package smartticketing.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Best-effort wake-up only. Durable notifications are read from MySQL on SSE reconnect. */
@Component
public class NotificationPublisher implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String channel;
    private final String origin = UUID.randomUUID().toString();

    public NotificationPublisher(StringRedisTemplate redis,
            @Value("${app.notifications.redis-enabled:false}") boolean enabled,
            @Value("${spring.datasource.url}") String database) {
        this.redis = redis;
        this.enabled = enabled;
        this.channel = "notification:v1:" + UUID.nameUUIDFromBytes(database.getBytes(StandardCharsets.UTF_8));
    }

    public String channel() { return channel; }

    public void publish(Long userId) {
        if (userId == null) return;
        NotificationSseHub.publish(userId);
        if (!enabled) return;
        try {
            redis.convertAndSend(channel, origin + ":" + userId);
        } catch (RuntimeException failure) {
            // A failed wake-up must not turn a committed booking into an HTTP failure.
            log.warn("Notification broadcast unavailable; clients recover from DB on reconnect ({})",
                    failure.getClass().getSimpleName());
        }
    }

    @Override public void onMessage(Message message, byte[] pattern) {
        String value = new String(message.getBody(), StandardCharsets.UTF_8);
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || parts[0].equals(origin)) return;
        try {
            UUID.fromString(parts[0]);
            long userId = Long.parseLong(parts[1]);
            if (userId > 0) NotificationSseHub.publish(userId);
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed messages; never rebroadcast received messages.
        }
    }
}
