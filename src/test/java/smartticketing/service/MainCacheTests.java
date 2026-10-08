package smartticketing.service;

import org.junit.jupiter.api.Test;
import smartticketing.repository.MovieRepository;
import smartticketing.dto.movie.MainChartResponseDto;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MainCacheTests {
    @Test void redisOffKeepsLocalCache() {
        var repository=mock(MovieRepository.class);
        var redis=new RedisQueryCache(null,false,2000,"test-disabled");
        var service=new MainService(repository,"2026-10-08",redis,60000);
        var first=service.getMainChart();
        assertThat(service.getMainChart()).isSameAs(first);
        verify(repository).sumActiveAudienceCount();
    }
    @Test void localCacheUsesFutureExpiryForDatabaseAndRedisLoads() {
        for(boolean hit:List.of(false,true)) {
            var repository=mock(MovieRepository.class); var redis=mock(RedisQueryCache.class);
            if(hit) when(redis.get(eq("main"),anyString(),eq(MainChartResponseDto.class)))
                    .thenReturn(new MainChartResponseDto(List.of(),List.of()));
            var service=new MainService(repository,"2026-10-08",redis,60000);
            var first=service.getMainChart(); clearInvocations(redis,repository);
            assertThat(service.getMainChart()).isSameAs(first);
            verifyNoInteractions(redis,repository);
        }
    }
    @Test void localCacheReloadsAfterTtl() throws InterruptedException {
        var repository=mock(MovieRepository.class);
        var service=new MainService(repository,"2026-10-08",new RedisQueryCache(null,false,2000,"test-disabled"),100);
        var first=service.getMainChart();
        Thread.sleep(180);
        assertThat(service.getMainChart()).isNotSameAs(first);
        verify(repository,times(2)).sumActiveAudienceCount();
    }
}
