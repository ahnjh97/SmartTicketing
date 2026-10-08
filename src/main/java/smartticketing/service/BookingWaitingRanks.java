package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import smartticketing.entity.enums.SeatPosition;
import java.util.*;

/** Advisory projection. The DB revision must match before a rank can be used. */
@Component
public class BookingWaitingRanks {
    public record Entry(long id, SeatPosition zone, int number) {}
    private StringRedisTemplate redis;
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory dedicated;
    private final boolean enabled;
    private final String prefix;
    private volatile long unavailableUntil;
    private static final DefaultRedisScript<Long> CURRENT = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
            for i=2,#KEYS do
              if not redis.call('ZSCORE',KEYS[i],'_') then return 0 end
            end
            return 1
            """,Long.class);
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            local old=redis.call('GET',KEYS[1])
            local version=ARGV[1]
            if old and (#old>#version or (#old==#version and old>version)) then return 0 end
            for i=2,#KEYS do
              redis.call('DEL',KEYS[i])
              redis.call('ZADD',KEYS[i],-1,'_')
            end
            for i=2,#ARGV,3 do redis.call('ZADD',KEYS[tonumber(ARGV[i])],ARGV[i+1],ARGV[i+2]) end
            for i=2,#KEYS do redis.call('EXPIRE',KEYS[i],120) end
            redis.call('SET',KEYS[1],version,'EX',120)
            return 1
            """,Long.class);
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return {} end
            local result={}
            for i=2,#ARGV,2 do
              local key=KEYS[tonumber(ARGV[i])]
              if not redis.call('ZSCORE',key,'_') then return {} end
              result[#result+1]=redis.call('ZCOUNT',key,0,'('..ARGV[i+1])
            end
            return result
            """,List.class);

    public BookingWaitingRanks(StringRedisTemplate redis,
            @Value("${booking.waiting.redis-enabled:true}") boolean enabled,
            @Value("${spring.datasource.url}") String database) {
        this.redis=redis; this.enabled=enabled;
        prefix="booking-ranks:v1:"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    }
    public boolean enabled() { return enabled; }
    /** Check metadata only; do not renew TTL so periodic rebuilding can still heal corruption. */
    public boolean isCurrent(long show,long version) {
        if(!enabled) return true;
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        try {
            var result=redis.execute(CURRENT,keys(show),Long.toString(version));
            if(result==null) throw new IllegalStateException("Waiting rank check was not acknowledged");
            return result==1;
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; throw failure; }
    }
    @jakarta.annotation.PostConstruct
    void initialize() {
        if(!enabled) return;
        if(redis.getConnectionFactory() instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory original
                && original.getStandaloneConfiguration()!=null) {
            var timeout=java.time.Duration.ofMillis(200);
            var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(timeout).shutdownTimeout(java.time.Duration.ZERO)
                    .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(timeout).build()).build()).build();
            dedicated=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(original.getStandaloneConfiguration(),client);
            dedicated.afterPropertiesSet(); redis=new StringRedisTemplate(dedicated);
        }
    }
    @jakarta.annotation.PreDestroy
    void close() { if(dedicated!=null) dedicated.destroy(); }
    private int index(SeatPosition zone) { return zone==null ? 2 : zone.ordinal()+3; }
    private List<String> keys(long show) {
        String root=prefix+"{"+show+"}:";
        var keys=new ArrayList<String>(); keys.add(root+"version"); keys.add(root+"NONE");
        for(var zone:SeatPosition.values()) keys.add(root+zone.name());
        return keys;
    }
    public void replace(long show,long version,List<Entry> entries) {
        if(!enabled) return;
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        var args=new ArrayList<String>(); args.add(Long.toString(version));
        for(var row:entries) { args.add(Integer.toString(index(row.zone()))); args.add(Integer.toString(row.number())); args.add(Long.toString(row.id())); }
        try {
            if(redis.execute(REPLACE,keys(show),args.toArray())==null)
                throw new IllegalStateException("Waiting rank update was not acknowledged");
        }
        catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; throw failure; }
    }
    public Map<Long,Long> read(long show,long version,List<Entry> entries) {
        if(!enabled || entries.isEmpty() || System.currentTimeMillis()<unavailableUntil) return Map.of();
        var args=new ArrayList<String>(); args.add(Long.toString(version));
        for(var row:entries) { args.add(Integer.toString(index(row.zone()))); args.add(Integer.toString(row.number())); }
        try {
            var values=redis.execute(READ,keys(show),args.toArray());
            if(values==null || values.size()!=entries.size()) return Map.of();
            var result=new HashMap<Long,Long>();
            for(int i=0;i<entries.size();i++) result.put(entries.get(i).id(),((Number)values.get(i)).longValue());
            return result;
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; return Map.of(); }
    }
}
