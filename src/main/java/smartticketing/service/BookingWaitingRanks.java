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
    private static final DefaultRedisScript<String> VERSION = new DefaultRedisScript<>("""
            local version=redis.call('GET',KEYS[1])
            if not version then return nil end
            for i=2,#KEYS do
              if not redis.call('ZSCORE',KEYS[i],'_') then return nil end
            end
            return version
            """,String.class);
    private static final DefaultRedisScript<Long> PATCH = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
            for i=2,#KEYS do
              if not redis.call('ZSCORE',KEYS[i],'_') then return 0 end
            end
            for i=3,#ARGV,3 do
              for k=2,#KEYS do redis.call('ZREM',KEYS[k],ARGV[i+2]) end
              local target=tonumber(ARGV[i])
              if target>0 then redis.call('ZADD',KEYS[target],ARGV[i+1],ARGV[i+2]) end
            end
            redis.call('SET',KEYS[1],ARGV[2])
            for i=1,#KEYS do redis.call('EXPIRE',KEYS[i],120) end
            return 1
            """,Long.class);
    private static final DefaultRedisScript<Long> CURRENT = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
            for i=2,#KEYS do
              if not redis.call('ZSCORE',KEYS[i],'_') then return 0 end
            end
            for i=1,#KEYS do redis.call('EXPIRE',KEYS[i],120) end
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
            redis.call('SET',KEYS[1]..':audit','1','EX',1800)
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
    private static final DefaultRedisScript<List> READ_ADJUSTED = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return {} end
            for k=2,#KEYS do
              if not redis.call('ZSCORE',KEYS[k],'_') then return {} end
            end
            local n=tonumber(ARGV[2])
            local changes=3+n*2
            local result={}
            for i=3,changes-1,2 do
              local zone=tonumber(ARGV[i])
              local number=tonumber(ARGV[i+1])
              local count=redis.call('ZCOUNT',KEYS[zone],0,'('..ARGV[i+1])
              for j=changes,#ARGV,3 do
                local old=redis.call('ZSCORE',KEYS[zone],ARGV[j+2])
                if old and tonumber(old)<number then count=count-1 end
                if tonumber(ARGV[j])==zone and tonumber(ARGV[j+1])<number then count=count+1 end
              end
              if count<0 then return {} end
              result[#result+1]=count
            end
            return result
            """,List.class);

    public BookingWaitingRanks(StringRedisTemplate redis,
            @Value("${app.cache.enabled:false}") boolean enabled,
            @Value("${spring.datasource.url}") String database) {
        this.redis=redis; this.enabled=enabled;
        prefix="booking-ranks:v1:"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    }
    public boolean enabled() { return enabled; }
    /** Null means missing metadata or partial eviction. Readers never extend cache lifetime. */
    public Long version(long show) {
        if(!enabled) return null;
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        try {
            var value=redis.execute(VERSION,keys(show));
            return value==null ? null : Long.valueOf(value);
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; throw failure; }
    }
    /** CAS, all zone changes and revision publication are one atomic Redis operation. */
    public boolean patch(long show,long expected,long version,List<Entry> upserts,List<Long> removals) {
        if(!enabled) return true;
        if(version<expected) throw new IllegalArgumentException("Projection version cannot decrease");
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        var args=new ArrayList<String>(); args.add(Long.toString(expected)); args.add(Long.toString(version));
        for(var id:removals) { args.add("0"); args.add("0"); args.add(Long.toString(id)); }
        for(var row:upserts) { args.add(Integer.toString(index(row.zone()))); args.add(Integer.toString(row.number())); args.add(Long.toString(row.id())); }
        try {
            var result=redis.execute(PATCH,keys(show),args.toArray());
            if(result==null) throw new IllegalStateException("Waiting rank patch was not acknowledged");
            return result==1;
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; throw failure; }
    }
    /** Worker-only renewal after checking the DB revision. Independent audit TTL still forces reconciliation. */
    public boolean isCurrent(long show,long version) {
        if(!enabled) return true;
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        try {
            var result=redis.execute(CURRENT,keys(show),Long.toString(version));
            if(result==null) throw new IllegalStateException("Waiting rank check was not acknowledged");
            return result==1;
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; throw failure; }
    }
    public boolean auditDue(long show) {
        if(!enabled) return false;
        if(System.currentTimeMillis()<unavailableUntil) throw new IllegalStateException("Waiting rank Redis temporarily unavailable");
        try { return !Boolean.TRUE.equals(redis.hasKey(keys(show).getFirst()+":audit")); }
        catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000;throw failure; }
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
    /** Read-only overlay: pending DB writes must never enter the shared Redis projection. */
    public Map<Long,Long> readAdjusted(long show,long base,List<Entry> entries,List<Entry> upserts,List<Long> removals) {
        if(!enabled || entries.isEmpty() || System.currentTimeMillis()<unavailableUntil) return Map.of();
        var args=new ArrayList<String>(); args.add(Long.toString(base)); args.add(Integer.toString(entries.size()));
        for(var row:entries) { args.add(Integer.toString(index(row.zone()))); args.add(Integer.toString(row.number())); }
        for(var id:removals) { args.add("0"); args.add("0"); args.add(Long.toString(id)); }
        for(var row:upserts) { args.add(Integer.toString(index(row.zone()))); args.add(Integer.toString(row.number())); args.add(Long.toString(row.id())); }
        try {
            var values=redis.execute(READ_ADJUSTED,keys(show),args.toArray());
            if(values==null || values.size()!=entries.size()) return Map.of();
            var result=new HashMap<Long,Long>();
            for(int i=0;i<entries.size();i++) result.put(entries.get(i).id(),((Number)values.get(i)).longValue());
            return result;
        } catch(RuntimeException failure) { unavailableUntil=System.currentTimeMillis()+5000; return Map.of(); }
    }
}
