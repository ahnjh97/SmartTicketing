package smartticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BookingQueryConfiguration {
    @Bean
    public Clock bookingQueryClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
