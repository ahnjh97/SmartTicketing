package smartticketing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogHttpConfig {
    @Bean
    public RestClient theaterCatalogRestClient(
            @Value("${kakao.catalog.base-url:https://dapi.kakao.com}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build();
    }

    public static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return factory;
    }
}
