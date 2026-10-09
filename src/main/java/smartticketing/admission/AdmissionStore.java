package smartticketing.admission;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/** All keys share a Redis Cluster slot. Redis TIME avoids differences between application clocks. */
public class AdmissionStore implements AutoCloseable {
    private final StringRedisTemplate redis;
    private final AdmissionSettings settings;
    private final List<String> keys;
    private final Runnable close;
    public record State(String state, long ahead, int pollAfterSeconds) {}

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
        local clock = redis.call('TIME')
        local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
        local op, id = ARGV[1], ARGV[2]
        local capacity, batch = tonumber(ARGV[3]), tonumber(ARGV[4])
        local activeTTL, waitTTL = tonumber(ARGV[5])*1000, tonumber(ARGV[6])*1000
        -- Bounded cleanup prevents long scripts even after a large abandonment wave.
        if op == 'tick' then
        local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, 200)
        for _, member in ipairs(stale) do
            redis.call('ZREM', KEYS[1], member)
            redis.call('ZREM', KEYS[2], member)
        end
        redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
        end
        if op == 'leave' then
            redis.call('ZREM', KEYS[1], id)
            redis.call('ZREM', KEYS[2], id)
            redis.call('ZREM', KEYS[3], id)
            return {0, 0}
        end
        local function promote()
            -- Shared one-second budget covers both immediate entry and all schedulers.
            local used = tonumber(redis.call('GET', KEYS[5]) or '0')
            local room = math.max(0, math.min(batch-used, capacity - redis.call('ZCARD', KEYS[3])))
            local moved = 0
            if room > 0 then
                local candidates = redis.call('ZRANGE', KEYS[1], 0, room-1)
                for _, member in ipairs(candidates) do
                    local alive = tonumber(redis.call('ZSCORE', KEYS[2], member) or '0')
                    if alive > now then
                        redis.call('ZADD', KEYS[3], now + activeTTL, member)
                        moved = moved + 1
                    end
                    redis.call('ZREM', KEYS[1], member)
                    redis.call('ZREM', KEYS[2], member)
                end
            end
            if moved > 0 then
                local count = redis.call('INCRBY', KEYS[5], moved)
                if count == moved then redis.call('PEXPIRE', KEYS[5], 1000) end
            end
        end
        if op == 'tick' then
            promote()
            return {0,0}
        end
        local activeUntil = tonumber(redis.call('ZSCORE', KEYS[3], id) or '0')
        if activeUntil > now then
            if op == 'check' then redis.call('ZADD', KEYS[3], now + activeTTL, id) end
            return {1, 0}
        end
        if activeUntil > 0 then redis.call('ZREM', KEYS[3], id) end
        if op == 'check' then return {0,0} end
        local waitingUntil = tonumber(redis.call('ZSCORE', KEYS[2], id) or '0')
        if waitingUntil <= now then
            redis.call('ZREM', KEYS[1], id)
            redis.call('ZREM', KEYS[2], id)
        end
        local rank = redis.call('ZRANK', KEYS[1], id)
        if not rank and op == 'enter' then
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[7]) then return {3,0} end
            local sequence = redis.call('INCR', KEYS[4])
            redis.call('ZADD', KEYS[1], sequence, id)
            rank = redis.call('ZRANK', KEYS[1], id)
        end
        if rank then
            redis.call('ZADD', KEYS[2], now + waitTTL, id)
            if op == 'enter' then
                promote()
                if redis.call('ZSCORE', KEYS[3], id) then return {1,0} end
                rank = redis.call('ZRANK', KEYS[1], id)
            end
            return {2,rank}
        end
        return {0,0}
        """, List.class);

    public AdmissionStore(StringRedisTemplate redis, AdmissionSettings settings, String prefix, Runnable close) {
        this.redis = redis; this.settings = settings; this.close = close;
        keys = List.of(prefix+":waiting", prefix+":heartbeat", prefix+":active", prefix+":sequence", prefix+":tick");
    }
    public State execute(String op, String id) {
        if (!settings.enabled()) return new State("DISABLED", 0, 60);
        List<?> result = redis.execute(SCRIPT, keys, op, id, ""+settings.capacity(), ""+settings.batchSize(),
                ""+settings.activeSeconds(), ""+settings.waitSeconds(), ""+settings.maxWaiting());
        if (result == null || result.size() != 2) throw new IllegalStateException("Admission unavailable");
        int state = ((Number) result.get(0)).intValue();
        long ahead = ((Number) result.get(1)).longValue();
        return new State(switch (state) { case 1 -> "ADMITTED"; case 2 -> "WAITING"; case 3 -> "FULL"; default -> "EXPIRED"; },
                ahead, state == 1 ? 30 : state == 3 ? 15 : ahead >= 100 ? 10 : 5);
    }
    @Override public void close() { close.run(); }
}
