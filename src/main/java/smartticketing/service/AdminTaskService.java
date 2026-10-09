package smartticketing.service;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One maintenance task at a time. Each catalog batch commits independently. */
@Service
public class AdminTaskService {
    public record Status(String id, String label, String state, long completed, long total, String detail, Object result) {}
    private volatile Status status = new Status("", "", "IDLE", 0, 0, "", null);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("admin-maintenance").factory());
    private final AdminMaintenanceGate gate;
    private final AdminDataService data;
    private final Map<String, Status> history = new ConcurrentHashMap<>();
    public AdminTaskService(AdminMaintenanceGate gate, AdminDataService data) { this.gate = gate; this.data = data; }
    public Status status() { return status; }
    public Status status(String id) {
        if (id == null || id.equals(status.id())) return status;
        Status previous = history.get(id);
        if (previous == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "서버가 재시작되었거나 작업 기록이 만료되었습니다. 남은 데이터를 확인해주세요.");
        return previous;
    }
    public synchronized Status submit(String label, Consumer<AdminTaskService> work) {
        if ("RUNNING".equals(status.state())) throw new ResponseStatusException(HttpStatus.CONFLICT, "진행 중인 관리자 작업이 있습니다.");
        status = new Status(UUID.randomUUID().toString(), label, "RUNNING", 0, 0, "기존 요청 종료를 기다리는 중", null);
        Status accepted = status;
        executor.submit(() -> {
            try {
                gate.maintain(() -> work.accept(this));
                finish(new Status(status.id(), label, "COMPLETED", status.completed(), status.total(), "완료", status.result()));
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("관리자 작업 실패 id={}", status.id(), failure);
                String detail = failure instanceof ResponseStatusException e ? e.getReason() : "작업이 중단되었습니다. 완료한 부분은 유지됩니다. 남은 데이터를 확인한 뒤 다시 실행해주세요.";
                finish(new Status(status.id(), label, "FAILED", status.completed(), status.total(), detail, null));
            }
        });
        return accepted;
    }
    private synchronized void finish(Status completed) {
        if (history.size() >= 32) history.clear();
        history.put(completed.id(), completed);
        status = completed;
    }
    public void progress(long completed, long total, String detail) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("작업 중단");
        status = new Status(status.id(), status.label(), "RUNNING", completed, total, detail, status.result());
    }
    public void result(Object result) { status = new Status(status.id(), status.label(), status.state(), status.completed(), status.total(), status.detail(), result); }
    public Status delete(AdminDataService.DeleteRequest request) {
        return submit("데이터 삭제", task -> {
            var roots = data.deletionRoots(request);
            int completed = 0; long shows = 0;
            if ("showtimes".equals(request.scope().kind())) {
                while (completed < roots.size()) {
                    int end = Math.min(completed + 20, roots.size());
                    task.progress(completed, roots.size(), "회차·좌석 삭제 중");
                    data.deleteBatch("showtimes", roots.subList(completed, end), request.includeBookings());
                    completed = end;
                    task.progress(completed, roots.size(), "회차 삭제 중");
                }
                task.result(Map.of("targetCount", roots.size()));
                return;
            }
            for (long root : roots) {
                task.progress(completed, roots.size(), "대상 #" + root + " 회차·좌석 삭제 중 (" + shows + "회차 완료)");
                if (!"showtimes".equals(request.scope().kind())) {
                    List<Long> batch;
                    while (!(batch = data.deletionShowtimes(request.scope().kind(), root)).isEmpty()) {
                        data.deleteBatch("showtimes", batch, request.includeBookings());
                        shows += batch.size();
                        task.progress(completed, roots.size(), "대상 #" + root + " 회차·좌석 삭제 중 (" + shows + "회차 완료)");
                    }
                }
                data.deleteBatch(request.scope().kind(), List.of(root), request.includeBookings());
                task.progress(++completed, roots.size(), "대상 " + completed + "개 삭제 완료");
            }
            task.result(Map.of("targetCount", roots.size()));
        });
    }
    @PreDestroy public void shutdown() {
        executor.shutdownNow();
        try { executor.awaitTermination(35, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
