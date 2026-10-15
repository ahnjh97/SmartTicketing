package smartticketing.config;

import smartticketing.service.MovieImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Slf4j
@Component
@Order(0)
public class MovieDataInitializer implements ApplicationRunner {
    private final MovieImportService movieImportService;
    private final boolean autoImport;
    private final String accessToken;

    public MovieDataInitializer(
            MovieImportService movieImportService,
            @Value("${tmdb.auto-import:true}") boolean autoImport,
            @Value("${tmdb.access-token:}") String accessToken) {
        this.movieImportService = movieImportService;
        this.autoImport = autoImport;
        this.accessToken = accessToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoImport || accessToken.isBlank()) {
            log.info("영화 자동 수집 생략: 비활성 또는 TMDB 토큰 미설정");
            return;
        }
        try {
            movieImportService.importConfiguredMovies();
        } catch (Exception e) {
            log.warn("영화 준비 미완료 ({}). 기존 DB로 실행하며 다음 시작/관리자 요청에서 재시도합니다.",
                    e.getClass().getSimpleName());
        }
    }
}
