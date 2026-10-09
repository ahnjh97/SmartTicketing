package smartticketing.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisQueryCacheTests {
    @Test
    void disabledQueryCacheDoesNotTouchRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisQueryCache cache = new RedisQueryCache(redis, false, 2_000, "isolated-cache-test");

        assertThat(cache.get("movies", "page-0", String.class)).isNull();
        cache.put("movies", "page-0", "ignored");

        verifyNoInteractions(redis);
    }
}
