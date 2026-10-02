package smartticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import smartticketing.service.ShowtimeScheduleSeedService;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "showtime.seed.enabled", havingValue = "true", matchIfMissing = true)
public class ShowtimeScheduleSeedConfiguration {
    @Bean
    public ApplicationRunner showtimeScheduleSeedRunner(ShowtimeScheduleSeedService seed) {
        return new Runner(seed);
    }

    private record Runner(ShowtimeScheduleSeedService seed) implements ApplicationRunner, Ordered {
        @Override
        public int getOrder() {
            return 200;
        }

        @Override
        public void run(ApplicationArguments args) {
            long startedAt = System.currentTimeMillis();
            int total = 0;
            for (Long theaterId : seed.findActiveTheaterIds()) {
                total += seed.seedTheater(theaterId);
            }
            log.info("상영 시간표 생성 완료 - 새 회차 {}건, {}초",
                    total, (System.currentTimeMillis() - startedAt) / 1000);
        }
    }
}
