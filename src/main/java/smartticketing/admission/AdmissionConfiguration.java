package smartticketing.admission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class AdmissionConfiguration {
    // Keep existing maintenance jobs off the admission lane.
    @Bean("taskScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("maintenance-");
        return scheduler;
    }
    @Bean("admissionTaskScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler admissionTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("admission-");
        scheduler.setDaemon(true);
        return scheduler;
    }
    @Bean(destroyMethod = "close")
    public AdmissionStore admissionStore(AdmissionSettings settings,
            @Value("${app.admission.redis-host:${spring.data.redis.host:localhost}}") String host,
            @Value("${app.admission.redis-port:${spring.data.redis.port:6379}}") int port,
            @Value("${app.admission.redis-password:${spring.data.redis.password:}}") String password,
            @Value("${app.admission.namespace:site-admission}") String namespace) {
        if (!settings.enabled()) return new AdmissionStore(null, settings, "{site-admission}", () -> {});
        var server = new RedisStandaloneConfiguration(host, port);
        if (!password.isBlank()) server.setPassword(password);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build();
        var connection = new LettuceConnectionFactory(server, client);
        connection.afterPropertiesSet();
        return new AdmissionStore(new StringRedisTemplate(connection), settings, "{"+namespace+"}", connection::destroy);
    }
}
