package smartticketing.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import smartticketing.auth.*;
import smartticketing.controller.ReadinessController;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReadinessTests {
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class WebConfig {}

    @Test
    void anonymousProbeWaitsForStartupAndRefusesTrafficAgainOnShutdown() {
        new WebApplicationContextRunner()
                .withUserConfiguration(WebConfig.class, SecurityConfig.class, ReadinessController.class)
                .withBean(ApplicationAvailabilityBean.class, ApplicationAvailabilityBean::new)
                .withBean(AdminAccess.class, () -> mock(AdminAccess.class))
                .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
                .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
                .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .run(context -> {
                    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                    mvc.perform(get("/api/health/readiness")).andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
                    AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
                    mvc.perform(get("/api/health/readiness")).andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("UP"));
                    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                    mvc.perform(get("/api/health/readiness")).andExpect(status().isServiceUnavailable());
                    mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
                });
    }
}
