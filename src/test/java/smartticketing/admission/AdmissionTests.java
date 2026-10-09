package smartticketing.admission;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Tag("core")
class AdmissionTests {
    @Test void fiveThousandConcurrentArrivalsPreserveFifoCapacityAndSharedBatchBudget() throws Exception {
        store=new AdmissionStore(redis,new AdmissionSettings(true,100,10,60,30,6000,false),prefix,()->{});
        redis.opsForValue().set(prefix+":tick","10",java.time.Duration.ofMinutes(1));
        try(var executor=Executors.newFixedThreadPool(32)) {
            var arrivals=new ArrayList<Callable<AdmissionStore.State>>();
            for(int i=0;i<5000;i++) { String id="burst-"+i;arrivals.add(() -> store.execute("enter",id)); }
            long start=System.nanoTime();
            for(var future:executor.invokeAll(arrivals)) assertThat(future.get().state()).isEqualTo("WAITING");
            var ordered=new ArrayList<>(redis.opsForZSet().range(prefix+":waiting",0,-1));
            assertThat(ordered).hasSize(5000);
            for(int round=1;round<=10;round++) {
                redis.delete(prefix+":tick");
                var ticks=new ArrayList<Callable<AdmissionStore.State>>();
                for(int i=0;i<16;i++) ticks.add(() -> store.execute("tick",""));
                for(var future:executor.invokeAll(ticks)) future.get();
                assertThat(redis.opsForZSet().size(prefix+":active")).isEqualTo(round*10L);
                assertThat(redis.opsForZSet().range(prefix+":active",0,-1)).containsExactlyInAnyOrderElementsOf(ordered.subList(0,round*10));
            }
            tick();
            assertThat(redis.opsForZSet().size(prefix+":active")).isEqualTo(100);
            assertThat(redis.opsForZSet().size(prefix+":waiting")).isEqualTo(4900);
            store.execute("leave",ordered.getFirst());tick();
            assertThat(store.execute("status",ordered.get(100)).state()).isEqualTo("ADMITTED");
            assertThat(redis.opsForZSet().size(prefix+":active")).isEqualTo(100);
            System.out.printf("ADMISSION_BURST arrivals=5000 capacity=100 batch=10 elapsedMs=%.1f%n",(System.nanoTime()-start)/1_000_000d);
        }
    }
    @Test void concurrentDuplicateRegistrationUsesOnePlaceAndNewArrivalsCannotJumpTheQueue() throws Exception {
        try(var executor=Executors.newFixedThreadPool(16)) {
            var tasks=new ArrayList<Callable<AdmissionStore.State>>();
            for(int i=0;i<100;i++) tasks.add(() -> store.execute("enter","same-browser"));
            for(var future:executor.invokeAll(tasks)) assertThat(future.get().ahead()).isZero();
        }
        assertThat(redis.opsForZSet().size(prefix+":waiting")).isEqualTo(1);
        redis.delete(prefix+":tick");
        assertThat(store.execute("enter","new-browser").state()).isEqualTo("WAITING");
        assertThat(store.execute("status","same-browser").state()).isEqualTo("ADMITTED");
    }
    @Test void redisStateLossClosesOldAdmissionAndRequiresFreshRegistration() {
        store.execute("enter","old");tick();
        assertThat(store.execute("check","old").state()).isEqualTo("ADMITTED");
        redis.delete(List.of(prefix+":waiting",prefix+":heartbeat",prefix+":active",prefix+":sequence",prefix+":tick"));
        assertThat(store.execute("check","old").state()).isEqualTo("EXPIRED");
        assertThat(store.execute("status","old").state()).isEqualTo("EXPIRED");
        assertThat(store.execute("enter","old").state()).isEqualTo("ADMITTED");
    }
    @Test void expiredWaitingTokenRejoinsAtTheBackAndStatusNeverCreatesAPlace() {
        store.execute("enter","old");store.execute("enter","next");
        redis.opsForZSet().add(prefix+":heartbeat","old",0);
        assertThat(store.execute("status","old").state()).isEqualTo("EXPIRED");
        assertThat(store.execute("status","unknown").state()).isEqualTo("EXPIRED");
        assertThat(store.execute("enter","old").ahead()).isEqualTo(1);
        assertThat(store.execute("status","next").ahead()).isZero();
    }
    @Test void largeAbandonmentIsCleanedInBoundedPassesWithoutAdmittingDeadWaiters() {
        store=new AdmissionStore(redis,new AdmissionSettings(true,2,1,60,30,1000,false),prefix,()->{});
        for(int i=0;i<450;i++) {
            redis.opsForZSet().add(prefix+":waiting","dead-"+i,i);
            redis.opsForZSet().add(prefix+":heartbeat","dead-"+i,0);
        }
        redis.opsForValue().set(prefix+":sequence","450");
        store.execute("enter","alive");
        tick();
        assertThat(redis.opsForZSet().size(prefix+":waiting")).isBetween(250L,251L);
        assertThat(redis.opsForZSet().size(prefix+":active")).isZero();
        tick();tick();
        assertThat(store.execute("status","alive").state()).isEqualTo("ADMITTED");
        assertThat(redis.opsForZSet().size(prefix+":waiting")).isZero();
        assertThat(redis.opsForZSet().size(prefix+":heartbeat")).isZero();
    }
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
