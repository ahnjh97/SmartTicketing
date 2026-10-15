package smartticketing.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmartBookingSummaryCacheTests {
    @Test void disabledCacheDoesNotContactRedis() {
        var redis=mock(StringRedisTemplate.class);
        var cache=new SmartBookingSummaryCache(redis,false,"test");
        assertThat(cache.read(List.of(1L),2)).isEmpty();
        cache.putAll(2,Map.of());cache.invalidate(List.of(1L));verifyNoInteractions(redis);
    }
    @SuppressWarnings("unchecked")
    @Test void multiGetRejectsOldDataAndRedisFailureFallsBackWithoutRepeatedCalls() {
        var redis=mock(StringRedisTemplate.class);
        var values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        var json=JsonMapper.builder().build();
        var zones=Arrays.stream(smartticketing.entity.enums.SeatPosition.values()).map(z->new SmartBookingSummaryCache.Zone(z,false,0,List.<Long>of())).toList();
        when(values.multiGet(anyCollection())).thenReturn(List.of(
                json.writeValueAsString(new SmartBookingSummaryCache.Snapshot(System.currentTimeMillis(),zones)),
                json.writeValueAsString(new SmartBookingSummaryCache.Snapshot(System.currentTimeMillis()-10000,zones))));
        var cache=new SmartBookingSummaryCache(redis,true,"test");
        assertThat(cache.read(List.of(1L,2L),2)).containsOnlyKeys(1L);
        when(values.multiGet(anyCollection())).thenThrow(new RuntimeException("offline"));
        assertThat(cache.read(List.of(1L),2)).isEmpty();
        assertThat(cache.read(List.of(1L),2)).isEmpty();
        verify(values,times(2)).multiGet(anyCollection());
    }
    @Test void invalidationWaitsForCommitAndCoversEveryPartySize() {
        var redis=mock(StringRedisTemplate.class);
        var cache=new SmartBookingSummaryCache(redis,true,"test");
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidate(List.of(7L));verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
            verify(redis).delete(argThat((Collection<String> keys)->keys.size()==6));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
