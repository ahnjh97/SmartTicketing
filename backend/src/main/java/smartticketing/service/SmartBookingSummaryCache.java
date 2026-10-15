package smartticketing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import smartticketing.entity.enums.SeatPosition;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Advisory only: final allocation always rechecks MySQL under the show lock. */
@Component
public class SmartBookingSummaryCache {
    public record Zone(SeatPosition zone, boolean capacity, long ahead, List<Long> seats) {}
    public record Snapshot(long capturedAt, List<Zone> zones) {}
    private StringRedisTemplate redis;
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory dedicated;
    private final boolean enabled;
    private final String prefix;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile long unavailableUntil;
    @jakarta.annotation.PostConstruct
    void initialize() {
        if(!enabled)return;
        if(redis.getConnectionFactory() instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory original
                && original.getStandaloneConfiguration()!=null) {
            var timeout=java.time.Duration.ofMillis(200);
            var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(timeout).shutdownTimeout(java.time.Duration.ZERO)
                    .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(timeout).build()).build()).build();
            dedicated=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(original.getStandaloneConfiguration(),client);
            dedicated.afterPropertiesSet();
            redis=new StringRedisTemplate(dedicated);
        }
    }
    @jakarta.annotation.PreDestroy
    void close() { if(dedicated!=null)dedicated.destroy(); }
    public SmartBookingSummaryCache(StringRedisTemplate redis, @Value("${app.cache.enabled:false}") boolean enabled,
            @Value("${spring.datasource.url}") String database) {
        this.redis=redis; this.enabled=enabled;
        this.prefix="smart-summary:v1:"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    }
    private String key(Long show, int party) { return prefix+show+":"+party; }
    private boolean available() { return enabled && System.currentTimeMillis()>=unavailableUntil; }
    private void failed() { unavailableUntil=System.currentTimeMillis()+30000; }
    public Map<Long,Snapshot> read(List<Long> ids,int party) {
        var result=new HashMap<Long,Snapshot>();
        if(ids.isEmpty() || !available())return result;
        try {
            var values=redis.opsForValue().multiGet(ids.stream().map(id->key(id,party)).toList());
            if(values==null || values.size()!=ids.size())return result;
            for(int i=0;i<values.size();i++) if(values.get(i)!=null) {
                var snapshot=json.readValue(values.get(i),Snapshot.class);
                long age=System.currentTimeMillis()-snapshot.capturedAt();
                if(age>=0 && age<=2000 && valid(snapshot,party))result.put(ids.get(i),snapshot);
            }
        } catch(RuntimeException unavailable) { failed(); result.clear(); }
        return result;
    }
    private boolean valid(Snapshot snapshot,int party) {
        if(snapshot.zones()==null || snapshot.zones().size()!=SeatPosition.values().length)return false;
        var zones=new HashSet<SeatPosition>();
        for(var zone:snapshot.zones()) {
            if(zone==null || zone.zone()==null || !zones.add(zone.zone()) || zone.ahead()<0 || zone.seats()==null
                    || (!zone.seats().isEmpty() && (zone.seats().size()!=party || !zone.capacity()))
                    || zone.seats().stream().anyMatch(id->id==null||id<=0) || new HashSet<>(zone.seats()).size()!=zone.seats().size())return false;
        }
        return true;
    }
    public void putAll(int party,Map<Long,Snapshot> snapshots) {
        if(snapshots.isEmpty() || !available())return;
        try { redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection->{
            snapshots.forEach((show,snapshot)->connection.stringCommands().setEx(
                    key(show,party).getBytes(java.nio.charset.StandardCharsets.UTF_8),2,
                    json.writeValueAsString(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return null;
        }); }
        catch(RuntimeException unavailable) { failed(); }
    }
    public void invalidate(Collection<Long> shows) {
        if(!available() || shows.isEmpty())return;
        var keys=shows.stream().distinct().flatMap(id->java.util.stream.IntStream.rangeClosed(1,6).mapToObj(party->key(id,party))).toList();
        Runnable remove=()->{ if(!available())return; try { redis.delete(keys); } catch(RuntimeException unavailable) { failed(); } };
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { remove.run(); }
            });
        else remove.run();
    }
}
