package com.SmartTicketing.SmartTicketing.config;

import com.SmartTicketing.SmartTicketing.auth.CustomOAuth2UserService;
import com.SmartTicketing.SmartTicketing.auth.OAuth2SuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private final CustomOAuth2UserService oauth2UserService;
    private final OAuth2SuccessHandler successHandler;

    public SecurityConfig(
            CustomOAuth2UserService oauth2UserService,
            OAuth2SuccessHandler successHandler
    ) {
        this.oauth2UserService = oauth2UserService;
        this.successHandler = successHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .cors(Customizer.withDefaults())

                .formLogin(
                        AbstractHttpConfigurer::disable
                )

                .httpBasic(
                        AbstractHttpConfigurer::disable
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/check-login-id",
                                "/api/auth/password-reset",
                                "/api/auth/social-signup-info",
                                "/api/auth/social-signup",
                                "/oauth2/**",
                                "/login/**",
                                "/api/admin/**",
                                "/error"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        .anyRequest()
                        .authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(
                                Customizer.withDefaults()
                        )
                );

        if (
                registrations.getIfAvailable()
                        != null
        ) {
            http.oauth2Login(oauth2 ->
                    oauth2
                            .userInfoEndpoint(
                                    userInfo ->
                                            userInfo.userService(
                                                    oauth2UserService
                                            )
                            )
                            .successHandler(
                                    successHandler
                            )
            );
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(
                List.of(
                        "http://localhost:5173"
                )
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of("*")
        );

        configuration.setAllowCredentials(
                true
        );

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}