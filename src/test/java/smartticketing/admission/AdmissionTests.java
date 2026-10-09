package smartticketing.admission;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AdmissionTests {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    String prefix;
    AdmissionStore store;
    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory("127.0.0.1",6379); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
    }
    @AfterAll static void close() { connection.destroy(); }
    @BeforeEach void setup() {
        prefix = "{admission-test-"+UUID.randomUUID()+"}";
        store = new AdmissionStore(redis, new AdmissionSettings(true,2,1,60,30,200,false), prefix, () -> {});
        redis.opsForValue().set(prefix+":tick","1",java.time.Duration.ofMinutes(1));
    }
    @AfterEach void cleanup() { var keys = redis.keys(prefix+":*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
    void tick() { redis.delete(prefix+":tick"); store.execute("tick",""); }
    @Test void idleSiteAdmitsImmediatelyWithinSharedRateBudget() {
        redis.delete(prefix+":tick");
        assertThat(store.execute("enter","a").state()).isEqualTo("ADMITTED");
        assertThat(store.execute("enter","b").state()).isEqualTo("WAITING");
        store.execute("tick","");
        assertThat(store.execute("status","b").state()).isEqualTo("WAITING");
    }
    @Test void fifoDuplicateRefreshAndSharedPromotionLimit() {
        assertThat(store.execute("enter","a").ahead()).isZero();
        assertThat(store.execute("enter","b").ahead()).isEqualTo(1);
        assertThat(store.execute("enter","a").ahead()).isZero();
        tick();
        assertThat(store.execute("status","a").state()).isEqualTo("ADMITTED");
        store.execute("tick","");
        assertThat(store.execute("status","b").state()).isEqualTo("WAITING");
        tick();
        assertThat(store.execute("status","b").state()).isEqualTo("ADMITTED");
        assertThat(store.execute("check","forged").state()).isEqualTo("EXPIRED");
    }
    @Test void expiredWaitersAreRemovedAndActiveExpiryReleasesCapacity() {
        store.execute("enter","a"); store.execute("enter","b"); store.execute("enter","c");
        redis.opsForZSet().add(prefix+":heartbeat","a",0);
        tick();
        assertThat(store.execute("status","a").state()).isEqualTo("EXPIRED");
        assertThat(store.execute("status","b").state()).isEqualTo("ADMITTED");
        redis.opsForZSet().add(prefix+":active","b",0);
        assertThat(store.execute("check","b").state()).isEqualTo("EXPIRED");
        tick();
        assertThat(store.execute("status","c").state()).isEqualTo("ADMITTED");
    }
    @Test void statusDoesNotRenewActiveLeaseButActualApiDoes() {
        store.execute("enter","a"); tick();
        redis.opsForZSet().add(prefix+":active","a",System.currentTimeMillis()+10000);
        double expiry = redis.opsForZSet().score(prefix+":active","a");
        store.execute("status","a");
        assertThat(redis.opsForZSet().score(prefix+":active","a")).isEqualTo(expiry);
        store.execute("check","a");
        assertThat(redis.opsForZSet().score(prefix+":active","a")).isGreaterThan(expiry);
    }
    @Test void concurrentArrivalsAndSchedulersNeverExceedCapacity() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<AdmissionStore.State>>();
            for (int i=0;i<100;i++) { String id="user-"+i; tasks.add(() -> store.execute("enter",id)); }
            for (var future : executor.invokeAll(tasks)) assertThat(future.get().state()).isEqualTo("WAITING");
            assertThat(redis.opsForZSet().size(prefix+":waiting")).isEqualTo(100);
            for (int i=0;i<5;i++) {
                redis.delete(prefix+":tick");
                var ticks = new ArrayList<Callable<AdmissionStore.State>>();
                for (int n=0;n<10;n++) ticks.add(() -> store.execute("tick",""));
                for (var future : executor.invokeAll(ticks)) future.get();
            }
            assertThat(redis.opsForZSet().size(prefix+":active")).isEqualTo(2);
            assertThat(redis.opsForZSet().size(prefix+":waiting")).isEqualTo(98);
        }
    }
    @Test void fullQueueAllowsExistingWaitersAndLeaveReleasesSlot() {
        store = new AdmissionStore(redis,new AdmissionSettings(true,2,1,60,30,1,false),prefix,()->{});
        store.execute("enter","a");
        assertThat(store.execute("enter","b").state()).isEqualTo("FULL");
        assertThat(store.execute("enter","a").state()).isEqualTo("WAITING");
        store.execute("leave","a");
        assertThat(store.execute("enter","b").state()).isEqualTo("WAITING");
        tick(); store.execute("leave","b");
        assertThat(redis.opsForZSet().size(prefix+":active")).isZero();
    }
}
