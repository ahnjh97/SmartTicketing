package smartticketing.admission;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
public class AdmissionScheduler {
    private final AdmissionStore store;
    private boolean failed;
    public AdmissionScheduler(AdmissionStore store) { this.store = store; }
    @Scheduled(fixedDelay = 1000, scheduler = "admissionTaskScheduler")
    public void promote() {
        try {
            store.execute("tick", "");
            if (failed) LoggerFactory.getLogger(getClass()).info("Admission Redis recovered");
            failed = false;
        } catch (RuntimeException e) {
            if (!failed) LoggerFactory.getLogger(getClass()).warn("Admission unavailable; entry remains closed", e);
            failed = true;
        }
    }
}
