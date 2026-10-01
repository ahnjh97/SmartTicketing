package smartticketing.config;

import smartticketing.service.BookingSeedService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;

@Slf4j
@Configuration
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "booking.seed.enabled", havingValue = "true")
public class BookingSeedConfiguration {
    @Bean
    public Clock bookingSeedClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }

    @Bean
    public ApplicationRunner bookingSeedRunner(BookingSeedService seed,
            @Value("${booking.seed.theater-ids:}") String theaterIds) {
        return new SeedRunner(seed, theaterIds);
    }

    private record SeedRunner(BookingSeedService seed, String theaterIds) implements ApplicationRunner, Ordered {
        @Override public int getOrder() { return 100; }
        @Override public void run(ApplicationArguments args) {
            var ids = Arrays.stream(theaterIds.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
            var result = seed.seed(ids);
            log.info("가상 상영 데이터 생성 결과: {}", result);
        }
    }
}
