package smartticketing.config;

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
            @Value("${tmdb.access-token:}") String accessToken) {

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(CatalogHttpConfig.requestFactory())
                .requestInterceptor((request, body, execution) -> {
                    if (accessToken.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "TMDB 토큰이 설정되지 않았습니다.");
                    return execution.execute(request, body);
                })
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
