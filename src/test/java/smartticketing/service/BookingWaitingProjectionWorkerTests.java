package smartticketing.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class BookingWaitingProjectionWorkerTests {
    @Test void repairContinuesAfterFullBatchAndCyclesAfterLastPage() {
        var projection=mock(BookingWaitingProjection.class);
        var page=java.util.stream.LongStream.rangeClosed(1,100).boxed().toList();
        when(projection.activeShows(0,100)).thenReturn(page);
        when(projection.activeShows(100,100)).thenReturn(List.of(101L));
        var worker=new BookingWaitingProjectionWorker(projection,new AdminMaintenanceGate());
        worker.repair(); worker.repair(); worker.repair();
        verify(projection).refresh(101L);
        verify(projection,times(2)).activeShows(0,100);
    }
    @Test void failedRefreshIsRetriedBeforeAdvancingCursor() {
        var projection=mock(BookingWaitingProjection.class);
        when(projection.activeShows(0,100)).thenReturn(List.of(1L,2L));
        when(projection.activeShows(1,100)).thenReturn(List.of(2L));
        doThrow(new IllegalStateException()).doNothing().when(projection).refresh(2L);
        var worker=new BookingWaitingProjectionWorker(projection,new AdminMaintenanceGate());
        worker.repair(); worker.repair();
        verify(projection,times(2)).refresh(2L);
        verify(projection).refresh(1L);
    }
}
