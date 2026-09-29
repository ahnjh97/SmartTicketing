package com.SmartTicketing.SmartTicketing.config;

import com.SmartTicketing.SmartTicketing.auth.CustomOAuth2UserService;
import com.SmartTicketing.SmartTicketing.auth.OAuth2SuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.Customizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.*;
import org.springframework.web.cors.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SecurityConfig {
    private final CustomOAuth2UserService oauth2UserService; private final OAuth2SuccessHandler successHandler;
    public SecurityConfig(CustomOAuth2UserService u,OAuth2SuccessHandler s){oauth2UserService=u;successHandler=s;}
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http)throws Exception{
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(a->a
                .requestMatchers("/api/auth/signup","/api/auth/login","/api/auth/check-login-id","/oauth2/**","/login/**","/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(o->o.userInfoEndpoint(u->u.userService(oauth2UserService)).successHandler(successHandler))
            .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults()));
        return http.build();
    }
    @Bean public PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean public SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret){byte[] bytes=secret.getBytes(StandardCharsets.UTF_8);if(bytes.length<32)throw new IllegalArgumentException("JWT_SECRET은 최소 32바이트 이상이어야 합니다.");return new SecretKeySpec(bytes,"HmacSHA256");}
    @Bean public JwtEncoder jwtEncoder(SecretKey key){return new NimbusJwtEncoder(new ImmutableSecret<>(key));}
    @Bean public JwtDecoder jwtDecoder(SecretKey key){return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();}
    @Bean public CorsConfigurationSource corsConfigurationSource(){
        CorsConfiguration c=new CorsConfiguration();c.setAllowedOrigins(List.of("http://localhost:5173"));c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("*"));c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",c);return source;
    }
}
