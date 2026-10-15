package smartticketing.service;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AdminTaskFailureTests {
    @Test void failureBeforeMaintenanceStartsDoesNotLeaveTaskRunning() throws Exception {
        var gate=mock(AdminMaintenanceGate.class);
        doThrow(new IllegalStateException("maintenance could not start")).when(gate).maintain(any());
        var tasks=new AdminTaskService(gate,mock(AdminDataService.class));
        try {
            tasks.submit("test",task -> { throw new AssertionError("must not execute"); });
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(3).toNanos();
            while(tasks.status().state().equals("RUNNING") && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(tasks.status().state()).isEqualTo("FAILED");
        } finally { tasks.shutdown(); }
    }
}
