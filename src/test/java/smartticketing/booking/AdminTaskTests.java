package smartticketing.booking;

import org.junit.jupiter.api.Test;
import smartticketing.service.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminTaskTests {
    private void finished(AdminTaskService tasks) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tasks.status().state().equals("RUNNING") && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(tasks.status().state()).isNotEqualTo("RUNNING");
    }
    @Test void maintenanceWaitsForInflightWritesAndRejectsNewOnes() throws Exception {
        var data = mock(AdminDataService.class); var gate = new AdminMaintenanceGate();
        var tasks = new AdminTaskService(gate, data);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try {
            assertThat(gate.enterWriteRequest()).isTrue();
            tasks.submit("test", task -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertThat(entered.await(100, TimeUnit.MILLISECONDS)).isFalse();
            gate.leaveWriteRequest();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.enterWriteRequest()).isFalse();
            assertThatThrownBy(() -> tasks.submit("duplicate", task -> {})).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            release.countDown(); finished(tasks);
            assertThat(gate.enterWriteRequest()).isTrue(); gate.leaveWriteRequest();
        } finally { release.countDown(); tasks.shutdown(); }
    }
    @Test void catalogDeletionCommitsShowtimeBatchesBeforeEachRootAndStopsOnFailure() throws Exception {
        var data = mock(AdminDataService.class);
        var request = new AdminDataService.DeleteRequest(new AdminDataService.Scope("theaters", "all", null, null, null, null, null), "snapshot", "삭제", true);
        when(data.deletionRoots(request)).thenReturn(List.of(1L, 2L));
        when(data.deletionShowtimes("theaters", 1)).thenReturn(List.of(10L, 11L), List.of());
        when(data.deletionShowtimes("theaters", 2)).thenReturn(List.of(20L));
        doThrow(new IllegalStateException("test failure")).when(data).deleteBatch("showtimes", List.of(20L), true);
        var tasks = new AdminTaskService(new AdminMaintenanceGate(), data);
        try {
            var accepted = tasks.delete(request); finished(tasks);
            assertThat(tasks.status().state()).isEqualTo("FAILED");
            assertThat(tasks.status().completed()).isEqualTo(1);
            var order = inOrder(data);
            order.verify(data).deleteBatch("showtimes", List.of(10L, 11L), true);
            order.verify(data).deleteBatch("theaters", List.of(1L), true);
            verify(data, never()).deleteBatch("theaters", List.of(2L), true);
            tasks.submit("next", task -> task.result("done")); finished(tasks);
            assertThat(tasks.status(accepted.id()).state()).isEqualTo("FAILED");
        } finally { tasks.shutdown(); }
    }
}
