package smartticketing.service;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingDispatchGateTests {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    String database=UUID.randomUUID().toString();
    String prefix="booking-dispatch:v1:"+UUID.nameUUIDFromBytes(database.getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    @BeforeAll static void start() {
        connection=new LettuceConnectionFactory("127.0.0.1",6379); connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);
    }
    @AfterAll static void stop() { if(connection!=null) connection.destroy(); }
    @AfterEach void clean() { var keys=redis.keys(prefix+"*"); if(keys!=null && !keys.isEmpty()) redis.delete(keys); }
    BookingDispatchGate gate() { return new BookingDispatchGate(redis,true,30000,database); }

    @Test void separateInstancesSuppressSameShowButAllowAnotherShow() throws Exception {
        var first=gate(); var second=gate(); var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var running=executor.submit(() -> first.run(1,() -> {
                entered.countDown();
                try { if(!finish.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return 7;
            }));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(redis.getExpire(prefix+"{1}")).isBetween(1L,30L);
                assertThatThrownBy(() -> second.run(1,() -> { throw new AssertionError("must not execute"); }))
                        .isInstanceOf(BookingDispatchGate.Busy.class);
                assertThat(second.run(2,() -> 2)).isEqualTo(2);
            } finally { finish.countDown(); }
            assertThat(running.get(10,TimeUnit.SECONDS)).isEqualTo(7);
        }
        assertThat(second.run(1,() -> 8)).isEqualTo(8);
        assertThat(redis.hasKey(prefix+"{1}")).isFalse();
    }
    @Test void expiredOwnersCannotReleaseSuccessorToken() {
        var gate=gate(); String key=prefix+"{1}";
        gate.run(1,() -> {
            redis.expire(key,Duration.ZERO);
            assertThat(redis.opsForValue().setIfAbsent(key,"successor",Duration.ofSeconds(30))).isTrue();
            return 1;
        });
        assertThat(redis.opsForValue().get(key)).isEqualTo("successor");
    }

    @Test void anotherZoneInTheSameShowUsesAnIndependentLease() {
        var first = gate(); var second = gate();
        first.run(1, smartticketing.entity.enums.SeatPosition.MIDDLE_FRONT, () -> {
            assertThatThrownBy(() -> second.run(1, smartticketing.entity.enums.SeatPosition.MIDDLE_FRONT, () -> 0))
                    .isInstanceOf(BookingDispatchGate.Busy.class);
            assertThat(second.run(1, smartticketing.entity.enums.SeatPosition.SIDE_FRONT, () -> 2)).isEqualTo(2);
            return 1;
        });
    }
    @Test void businessFailureReleasesLeaseAndIsNotRetriedInsideGate() {
        var count=new AtomicInteger(); var gate=gate();
        assertThatThrownBy(() -> gate.run(1,() -> { count.incrementAndGet(); throw new IllegalArgumentException("business"); }))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("business");
        assertThat(count.get()).isEqualTo(1);
        assertThat(redis.hasKey(prefix+"{1}")).isFalse();
        assertThat(gate.run(1,() -> 2)).isEqualTo(2);
    }
    @Test void disabledGateDoesNotContactRedis() {
        var unavailable=mock(StringRedisTemplate.class);
        assertThat(new BookingDispatchGate(unavailable,false,30000,database).run(1,() -> 3)).isEqualTo(3);
        verifyNoInteractions(unavailable);
    }
    @Test @SuppressWarnings("unchecked") void acquisitionFailureFallsBackAndUsesCircuitBreak() {
        var unavailable=mock(StringRedisTemplate.class); var values=mock(ValueOperations.class);
        when(unavailable.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenThrow(new IllegalStateException("offline"));
        var gate=new BookingDispatchGate(unavailable,true,30000,database);
        assertThat(gate.run(1,() -> 3)).isEqualTo(3);
        assertThat(gate.run(1,() -> 4)).isEqualTo(4);
        verify(values).setIfAbsent(anyString(),anyString(),any(Duration.class));
    }
    @Test @SuppressWarnings("unchecked") void releaseFailureDoesNotFailCommittedWork() {
        var unavailable=mock(StringRedisTemplate.class); var values=mock(ValueOperations.class);
        when(unavailable.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        when(unavailable.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("offline"));
        assertThat(new BookingDispatchGate(unavailable,true,30000,database).run(1,() -> 3)).isEqualTo(3);
    }
}
