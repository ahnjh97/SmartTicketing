package smartticketing.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import smartticketing.config.BookingOutboxConfiguration;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookingOutboxSchedulingTests {
    @Test void workerStartsWithTheQualifiedSchedulerAndApplicationMeterRegistry() {
        var store=mock(BookingOutboxStore.class);
        when(store.backlog(any())).thenReturn(new BookingOutboxStore.Backlog(0,0,0,0,0));
        new ApplicationContextRunner()
                .withUserConfiguration(BookingOutboxConfiguration.class,BookingOutboxWorker.class)
                .withPropertyValues("booking.outbox.delay-ms=3600000","booking.outbox.metrics-delay-ms=3600000")
                .withBean(BookingOutboxStore.class,() -> store)
                .withBean(AdminMaintenanceGate.class,AdminMaintenanceGate::new)
                .withBean(Clock.class,Clock::systemUTC)
                .withBean(MeterRegistry.class,SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BookingOutboxWorker.class);
                    assertThat(context.getBean("bookingOutboxScheduler",org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).isRunning()).isTrue();
                    assertThat(context.getBean(MeterRegistry.class).find("booking.outbox.processed").counter()).isNotNull();
                });
    }
}
