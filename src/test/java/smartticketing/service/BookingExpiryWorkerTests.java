package smartticketing.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingExpiryWorkerTests {
    private final LocalDateTime now = LocalDateTime.of(2026,10,8,12,0);

    @Test void drainsMoreThanOneHundredWithoutRetryingFailedRowsInTheSameScan() {
        var holds = mock(BookingHoldService.class);
        var candidates = candidates(205);
        configure(holds,candidates);
        when(holds.expire(anyLong())).thenReturn(true);
        when(holds.expire(1L)).thenThrow(new IllegalStateException());
        when(holds.expiryBacklog()).thenReturn(new BookingHoldService.ExpiryBacklog(1,30000));
        var worker = new BookingExpiryWorker(holds,new AdminMaintenanceGate());
        ReflectionTestUtils.setField(worker,"runBudgetMs",60000L);
        worker.recover();
        assertThat(worker.lastRun().attempted()).isEqualTo(205);
        assertThat(worker.lastRun().released()).isEqualTo(204);
        assertThat(worker.lastRun().failed()).isEqualTo(1);
        assertThat(worker.lastRun().remaining()).isEqualTo(1);
        assertThat(worker.lastRun().oldestDelayMs()).isEqualTo(30000);
        verify(holds,times(1)).expire(1L);
        verify(holds).expire(205L);
    }

    @Test void boundedRunsContinuePastFailuresAndRetryThemOnTheNextScan() {
        var holds = mock(BookingHoldService.class);
        configure(holds,candidates(5));
        when(holds.expire(anyLong())).thenThrow(new IllegalStateException());
        when(holds.expiryBacklog()).thenReturn(new BookingHoldService.ExpiryBacklog(5,30000));
        var worker = new BookingExpiryWorker(holds,new AdminMaintenanceGate());
        ReflectionTestUtils.setField(worker,"batchSize",2);
        ReflectionTestUtils.setField(worker,"maxBatches",1);
        ReflectionTestUtils.setField(worker,"runBudgetMs",60000L);
        worker.recover(); worker.recover(); worker.recover();
        for (long id=1;id<=5;id++) verify(holds,times(1)).expire(id);
        worker.recover();
        verify(holds,times(2)).expire(1L);
    }

    private List<BookingHoldService.ExpiryCandidate> candidates(int count) {
        return java.util.stream.LongStream.rangeClosed(1,count)
                .mapToObj(id -> new BookingHoldService.ExpiryCandidate(id,now.minusSeconds(30))).toList();
    }

    private void configure(BookingHoldService holds,List<BookingHoldService.ExpiryCandidate> candidates) {
        when(holds.now()).thenReturn(now);
        when(holds.expiredBatch(eq(now),nullable(BookingHoldService.ExpiryCandidate.class),anyInt())).thenAnswer(call -> {
            BookingHoldService.ExpiryCandidate cursor = call.getArgument(1);
            int limit = call.getArgument(2);
            return candidates.stream().filter(c -> cursor==null || c.groupId()>cursor.groupId()).limit(limit).toList();
        });
    }
}
