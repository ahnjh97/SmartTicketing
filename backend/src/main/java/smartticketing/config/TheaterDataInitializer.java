package smartticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import smartticketing.service.SeoulTheaterCollectionService;

@Slf4j
@Component
@Order(1)
public class TheaterDataInitializer implements ApplicationRunner {
    private final SeoulTheaterCollectionService service;
    private final boolean enabled;
    private final String key;

    public TheaterDataInitializer(SeoulTheaterCollectionService service,
            @Value("${kakao.catalog.auto-import:true}") boolean enabled,
            @Value("${kakao.map.rest-api-key:}") String key) {
        this.service = service;
        this.enabled = enabled;
        this.key = key;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || key.isBlank()) {
            log.info("서울 영화관 자동 수집 생략: 비활성 또는 API 키 미설정");
            return;
        }
        try {
            var result = service.collectSeoulTheaters();
            log.info("[DB 데이터] theaters 삽입 {}건, 수정 {}건 | 수집 요청 생략 {}건 | 외부 API {}회 | 극장 준비 {}ms",
                    result.get("insertedCount"), result.get("updatedCount"), result.get("skippedQueryCount"),
                    result.get("apiCallCount"), result.get("elapsedMs"));
        } catch (Exception e) {
            log.warn("서울 영화관 준비 미완료 ({}). 기존 DB로 실행하며 다음 시작/관리자 요청에서 이어서 수집합니다.",
                    e.getClass().getSimpleName());
        }
    }
}
