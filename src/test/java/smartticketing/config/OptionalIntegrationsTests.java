package smartticketing.config;

import smartticketing.auth.CustomOAuth2UserService;
import smartticketing.auth.OAuth2SuccessHandler;
import smartticketing.performace.NearbyTheaterPerformance;
import smartticketing.repository.TheaterRepository;
import smartticketing.service.KakaoMapService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OptionalIntegrationsTests {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(WebSecurity.class, SecurityConfig.class)
            .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
            .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
            .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class));

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurity {}

    @Test
    void startsWithoutSocialKeysAndKeepsJwtAuthentication() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.google.client-id")).isNull();
            assertThat(context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.naver.client-id")).isNull();
            assertThat(context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.kakao.client-id")).isNull();
            var filters = context.getBean(SecurityFilterChain.class).getFilters();
            assertThat(filters).anyMatch(f -> f.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter"));
            assertThat(filters).noneMatch(f -> f.getClass().getSimpleName().equals("OAuth2LoginAuthenticationFilter"));
        });
    }

    @Test
    void googleProfileLoadsOnlyGoogleSettings() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=oauth-google",
                        "GOOGLE_CLIENT_ID=test-client", "GOOGLE_CLIENT_SECRET=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty(
                            "spring.security.oauth2.client.registration.google.client-id"))
                            .isEqualTo("test-client");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.security.oauth2.client.registration.naver.client-id")).isNull();
                    assertThat(context.getEnvironment().getProperty(
                            "spring.security.oauth2.client.registration.kakao.client-id")).isNull();
                });
    }

    @Test
    void enablesSocialLoginWhenRegistrationExists() {
        var registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client").clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google")
                .authorizationUri("https://example.com/authorize")
                .tokenUri("https://example.com/token")
                .userInfoUri("https://example.com/userinfo").userNameAttributeName("id").build();
        runner.withBean(ClientRegistrationRepository.class,
                () -> new InMemoryClientRegistrationRepository(registration)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SecurityFilterChain.class).getFilters())
                    .anyMatch(f -> f.getClass().getSimpleName().equals("OAuth2LoginAuthenticationFilter"));
        });
    }

    @Test
    void missingMapKeyForWalkingReturnsUnavailableWithoutDatabaseAccess() {
        var theaters = mock(TheaterRepository.class);
        var service = new KakaoMapService(theaters, "", mock(NearbyTheaterPerformance.class));
        assertThatThrownBy(() -> service.findNearbyTheaters(null, 37.5, 127.0, 10000, "WALK"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(theaters);
    }
}
