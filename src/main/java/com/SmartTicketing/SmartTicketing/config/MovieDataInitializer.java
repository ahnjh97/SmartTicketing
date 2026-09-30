package com.SmartTicketing.SmartTicketing.config;

import com.SmartTicketing.SmartTicketing.service.MovieImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MovieDataInitializer implements ApplicationRunner {
    private final MovieImportService movieImportService;
    private final boolean autoImport;

    public MovieDataInitializer(
            MovieImportService movieImportService,
            @Value("${tmdb.auto-import:true}") boolean autoImport) {
        this.movieImportService = movieImportService;
        this.autoImport = autoImport;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoImport) {
            log.info("영화 자동 가져오기 꺼짐 (tmdb.auto-import=false)");
            return;
        }
        movieImportService.importConfiguredMovies();
    }
}
