package smartticketing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(RedisQueryCache.class);
    private static final long SLOW_MS = 50L;
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
            long redisStart = System.nanoTime();
            String value = redis.opsForValue().get(key(namespace, logicalKey));
            long redisMs = elapsedMs(redisStart);
            if (redisMs >= SLOW_MS) log.warn("REDIS_QUERY_CACHE_GET namespace={} hit={} duration={}ms", namespace, value != null, redisMs);
            if (value == null) return null;
            long deserializeStart = System.nanoTime();
            T result = json.readValue(value, type);
            long deserializeMs = elapsedMs(deserializeStart);
            if (deserializeMs >= SLOW_MS) log.warn("REDIS_QUERY_CACHE_DESERIALIZE namespace={} duration={}ms bytes={}", namespace, deserializeMs, value.length());
            return result;
        } catch (RuntimeException ex) {
            failed();
            return null;
        }
    }

    public <T> T get(String namespace, String logicalKey, TypeReference<T> type) {
        if (!available()) return null;
        try {
            long redisStart = System.nanoTime();
            String value = redis.opsForValue().get(key(namespace, logicalKey));
            long redisMs = elapsedMs(redisStart);
            if (redisMs >= SLOW_MS) log.warn("REDIS_QUERY_CACHE_GET namespace={} hit={} duration={}ms", namespace, value != null, redisMs);
            if (value == null) return null;
            long deserializeStart = System.nanoTime();
            T result = json.readValue(value, type);
            long deserializeMs = elapsedMs(deserializeStart);
            if (deserializeMs >= SLOW_MS) log.warn("REDIS_QUERY_CACHE_DESERIALIZE namespace={} duration={}ms bytes={}", namespace, deserializeMs, value.length());
            return result;
        } catch (RuntimeException ex) {
            failed();
            return null;
        }
    }

    public void put(String namespace, String logicalKey, Object value) {
        if (value == null || !available()) return;
        try {
            long serializeStart = System.nanoTime();
            String payload = json.writeValueAsString(value);
            long serializeMs = elapsedMs(serializeStart);
            long redisStart = System.nanoTime();
            redis.opsForValue().set(key(namespace, logicalKey), payload, ttl);
            long redisMs = elapsedMs(redisStart);
            if (serializeMs >= SLOW_MS || redisMs >= SLOW_MS) log.warn("REDIS_QUERY_CACHE_PUT namespace={} serialize={}ms redis={}ms bytes={}", namespace, serializeMs, redisMs, payload.length());
        } catch (RuntimeException ex) {
            failed();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
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
