package smartticketing.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import smartticketing.auth.*;
import smartticketing.config.SecurityConfig;
import smartticketing.controller.*;
import smartticketing.service.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingQuerySecurityTests {
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class WebConfig {}

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(WebConfig.class, SecurityConfig.class, BookingCatalogController.class,
                    ShowtimeQueryController.class, TheaterController.class)
            .withBean(smartticketing.auth.AdminAccess.class, () -> mock(smartticketing.auth.AdminAccess.class))
            .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
            .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
            .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
            .withBean(BookingCatalogService.class, () -> mock(BookingCatalogService.class))
            .withBean(ShowtimeQueryService.class, () -> mock(ShowtimeQueryService.class))
            .withBean(KakaoMapService.class, () -> mock(KakaoMapService.class));

    @Test void onlyCatalogGetEndpointsArePublicAndNearbyKeepsAuthentication() {
        runner.run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            for (String path : new String[]{"/api/movies", "/api/movies/1", "/api/theaters", "/api/theaters/1",
                    "/api/theaters/1/movies?date=2026-10-01", "/api/showtimes?movieId=1&date=2026-10-01",
                    "/api/showtimes/1/seats"}) mvc.perform(get(path)).andExpect(status().isOk());
            for (String path : new String[]{"/api/theaters/nearby?latitude=37&longitude=127", "/api/tickets",
                    "/api/notifications", "/api/users/me", "/api/users/preference-options", "/api/showtimes/1/private"})
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/showtimes")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/theaters/nearby?latitude=37&longitude=127").with(jwt())).andExpect(status().isOk());
        });
    }
}
