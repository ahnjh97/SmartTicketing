package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/** Read-only API query cache. Redis failures fall back to the existing query path. */
@Component
public class RedisQueryCache {
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;
    private final String prefix;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private volatile long unavailableUntil;

    public RedisQueryCache(
            StringRedisTemplate redis,
            @Value("${app.cache.enabled:false}") boolean enabled,
            @Value("${app.cache.query-ttl-ms:2000}") long ttlMs,
            @Value("${spring.datasource.url}") String database) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofMillis(Math.max(250, ttlMs));
        this.prefix = "query:v1:" + sha256(database) + ":";
    }

    public <T> T get(String namespace, String logicalKey, Class<T> type) {
        if (!available()) return null;
        try {
            String value = redis.opsForValue().get(key(namespace, logicalKey));
            return value == null ? null : json.readValue(value, type);
        } catch (RuntimeException ex) {
            failed();
            return null;
        }
    }

    public <T> T get(String namespace, String logicalKey, TypeReference<T> type) {
        if (!available()) return null;
        try {
            String value = redis.opsForValue().get(key(namespace, logicalKey));
            return value == null ? null : json.readValue(value, type);
        } catch (RuntimeException ex) {
            failed();
            return null;
        }
    }

    public void put(String namespace, String logicalKey, Object value) {
        if (value == null || !available()) return;
        try {
            redis.opsForValue().set(key(namespace, logicalKey), json.writeValueAsString(value), ttl);
        } catch (RuntimeException ex) {
            failed();
        }
    }

    private String key(String namespace, String logicalKey) {
        return prefix + namespace + ":" + sha256(logicalKey);
    }

    private boolean available() {
        return enabled && System.currentTimeMillis() >= unavailableUntil;
    }

    private void failed() {
        unavailableUntil = System.currentTimeMillis() + 30_000;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash cache key", ex);
        }
    }
}
