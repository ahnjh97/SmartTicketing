package smartticketing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="booking.outbox.enabled",havingValue="true",matchIfMissing=true)
public class BookingOutboxConfiguration {
    @org.springframework.context.annotation.Bean("bookingOutboxScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler bookingOutboxScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("booking-outbox-");
        return scheduler;
    }
}
