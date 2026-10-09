package smartticketing.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingCacheModeTests {
    private ApplicationContextRunner runner(StringRedisTemplate redis) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.datasource.url=isolated-cache-mode")
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(BookingWaitingProjection.class, () -> mock(BookingWaitingProjection.class))
                .withBean(AdminMaintenanceGate.class, AdminMaintenanceGate::new)
                .withUserConfiguration(SmartBookingSummaryCache.class, BookingWaitingRanks.class,
                        BookingDispatchGate.class, BookingWaitingProjectionWorker.class);
    }

    @Test void offAndMissingSwitchDisableAllBookingRedisEvenWithLegacyFlagsOn() {
        for (String[] properties : List.of(new String[]{}, new String[]{"app.cache.enabled=false"})) {
            var redis=mock(StringRedisTemplate.class);
            runner(redis).withPropertyValues(properties)
                    .withPropertyValues("booking.waiting.redis-enabled=true", "booking.waiting.dispatch-lock-enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(BookingWaitingProjectionWorker.class);
                        var ranks=context.getBean(BookingWaitingRanks.class);
                        assertThat(ranks.enabled()).isFalse();
                        var entries=List.of(new BookingWaitingRanks.Entry(1,null,1));
                        ranks.replace(1,1,entries);
                        assertThat(ranks.read(1,1,entries)).isEmpty();
                        assertThat(context.getBean(BookingDispatchGate.class).run(1,() -> 7)).isEqualTo(7);
                        var summary=context.getBean(SmartBookingSummaryCache.class);
                        assertThat(summary.read(List.of(1L),1)).isEmpty();
                        summary.putAll(1,Map.of(1L,new SmartBookingSummaryCache.Snapshot(0,List.of())));
                        summary.invalidate(List.of(1L));
                        // Container lifecycle calls are not Redis commands.
                        verify(redis).setBeanClassLoader(any(ClassLoader.class));
                        verify(redis).afterPropertiesSet();
                        verifyNoMoreInteractions(redis);
                    });
        }
    }

    @Test @SuppressWarnings("unchecked") void onEnablesAllBookingRedisAndRepairWorker() {
        var redis=mock(StringRedisTemplate.class);
        var values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);
        runner(redis).withPropertyValues("app.cache.enabled=true",
                "booking.waiting.redis-enabled=false", "booking.waiting.dispatch-lock-enabled=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BookingWaitingProjectionWorker.class);
            assertThat(context.getBean(BookingWaitingRanks.class).enabled()).isTrue();
            assertThatThrownBy(() -> context.getBean(BookingDispatchGate.class).run(1,() -> {
                throw new AssertionError("Busy Redis gate must not allocate");
            })).isInstanceOf(BookingDispatchGate.Busy.class);
            context.getBean(SmartBookingSummaryCache.class).read(List.of(1L),1);
            verify(values).multiGet(anyCollection());
            context.getBean(BookingWaitingProjectionWorker.class).repair();
            verify(context.getBean(BookingWaitingProjection.class)).activeShows(0,100);
        });
    }
}
