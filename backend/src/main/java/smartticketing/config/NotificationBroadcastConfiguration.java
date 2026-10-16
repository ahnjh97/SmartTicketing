package smartticketing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import smartticketing.service.NotificationPublisher;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.notifications.redis-enabled", havingValue = "true")
public class NotificationBroadcastConfiguration {
    @Bean
    RedisMessageListenerContainer notificationListenerContainer(
            @org.springframework.beans.factory.annotation.Qualifier("redisConnectionFactory") RedisConnectionFactory factory,
            NotificationPublisher publisher) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(publisher, new ChannelTopic(publisher.channel()));
        return container;
    }
}
