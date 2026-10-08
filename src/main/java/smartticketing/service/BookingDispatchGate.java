package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.function.IntSupplier;

/** Best-effort suppression only. MySQL locks remain authoritative after expiry or Redis failure. */
@Component
public class BookingDispatchGate {
    public static final class Busy extends org.springframework.dao.TransientDataAccessException {
        public Busy() { super("Waiting allocation is already running for this show"); }
    }
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end
            return 0
            """,Long.class);
    private StringRedisTemplate redis;
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory dedicated;
    private final boolean enabled;
    private final Duration lease;
    private final String prefix;
    private volatile long unavailableUntil;

    public BookingDispatchGate(StringRedisTemplate redis,
            @Value("${booking.waiting.dispatch-lock-enabled:true}") boolean enabled,
            @Value("${booking.waiting.dispatch-lock-ms:30000}") long leaseMs,
            @Value("${spring.datasource.url}") String database) {
        this.redis=redis; this.enabled=enabled; lease=Duration.ofMillis(Math.max(1,leaseMs));
        prefix="booking-dispatch:v1:"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    }
    @jakarta.annotation.PostConstruct
    void initialize() {
        if(!enabled) return;
        if(redis.getConnectionFactory() instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory original
                && original.getStandaloneConfiguration()!=null) {
            var timeout=Duration.ofMillis(200);
            var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(timeout).shutdownTimeout(Duration.ZERO)
                    .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(timeout).build()).build()).build();
            dedicated=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(original.getStandaloneConfiguration(),client);
            dedicated.afterPropertiesSet(); redis=new StringRedisTemplate(dedicated);
        }
    }
    @jakarta.annotation.PreDestroy
    void close() { if(dedicated!=null) dedicated.destroy(); }

    public int run(long show,IntSupplier allocation) {
        if(!enabled || System.currentTimeMillis()<unavailableUntil) return allocation.getAsInt();
        String key=prefix+"{"+show+"}", token=UUID.randomUUID().toString();
        Boolean acquired;
        try { acquired=redis.opsForValue().setIfAbsent(key,token,lease); }
        catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; return allocation.getAsInt(); }
        if(Boolean.FALSE.equals(acquired)) throw new Busy();
        if(acquired==null) { unavailableUntil=System.currentTimeMillis()+5000; return allocation.getAsInt(); }
        try { return allocation.getAsInt(); }
        finally {
            // Never delete a successor's lease, and never turn a committed allocation into an error.
            try { redis.execute(RELEASE,List.of(key),token); }
            catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; }
        }
    }
}
