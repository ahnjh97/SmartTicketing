package com.SmartTicketing.SmartTicketing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
public class TmdbConfig {
    @Bean
    public RestClient tmdbRestClient(
            @Value("${tmdb.base-url}") String baseUrl,
            @Value("${tmdb.access-token}") String accessToken) {

        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
