package smartticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import smartticketing.service.ShowtimeScheduleSeedService;
import smartticketing.service.ShowtimeInventoryService;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "showtime.seed.enabled", havingValue = "true", matchIfMissing = true)
public class ShowtimeScheduleSeedConfiguration {
    @Bean
    public ApplicationRunner showtimeScheduleSeedRunner(ShowtimeScheduleSeedService seed, ShowtimeInventoryService inventory) {
        return new Runner(seed, inventory);
    }

    private record Runner(ShowtimeScheduleSeedService seed, ShowtimeInventoryService inventory) implements ApplicationRunner, Ordered {
        @Override
        public int getOrder() {
            return 200;
        }

        @Override
        public void run(ApplicationArguments args) {
            long startedAt = System.currentTimeMillis();
            int total = 0, screens = 0;
            var plan = seed.preparePlan();
            for (Long theaterId : seed.findActiveTheaterIds()) {
                var result = seed.seedTheater(theaterId, plan);
                total += result.createdShowtimes();
                screens += result.createdScreens();
            }
            long scheduleFinishedAt = System.currentTimeMillis();
            log.info("[DB 데이터] screens 삽입 {}건 | showtimes 삽입 {}건 | 시간표 {}ms",
                    screens, total, scheduleFinishedAt - startedAt);
            int seats = 0, showtimeSeats = 0, updatedShows = 0, updatedSeats = 0;
            var screenIds = inventory.pendingScreenIds();
            log.info("[DB 준비] 좌석 보충·배치 확인 대상 상영관 {}개 | 대상 조회 {}ms",
                    screenIds.size(), System.currentTimeMillis() - scheduleFinishedAt);
            int completed = 0;
            long lastProgressAt = scheduleFinishedAt;
            for (Long screenId : screenIds) {
                var result = inventory.prepare(screenId);
                seats += result.createdSeats();
                showtimeSeats += result.createdShowtimeSeats();
                updatedShows += result.updatedShowtimes();
                updatedSeats += result.updatedSeats();
                completed++;
                long now = System.currentTimeMillis();
                if (now - lastProgressAt >= 10000 && completed < screenIds.size()) {
                    log.info("[DB 준비 중] 상영관 {}/{}개 | seats 삽입 {}건 | showtime_seats 삽입 {}건",
                            completed, screenIds.size(), seats, showtimeSeats);
                    lastProgressAt = now;
                }
            }
            log.info("[DB 데이터] seats 삽입 {}건, 배치 수정 {}건 | showtime_seats 삽입 {}건 | showtimes 좌석 수 수정 {}건 | 좌석 준비 {}ms",
                    seats, updatedSeats, showtimeSeats, updatedShows, System.currentTimeMillis() - scheduleFinishedAt);
            log.info("[DB 준비 완료] 대상 상영관 {}/{}개 | 시간표·좌석 준비 {}초", completed, screenIds.size(),
                    (System.currentTimeMillis() - startedAt) / 1000);
        }
    }
}
