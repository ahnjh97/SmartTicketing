package smartticketing.admission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AdmissionSettings(
        @Value("${app.admission.enabled:false}") boolean enabled,
        @Value("${app.admission.capacity:100}") int capacity,
        @Value("${app.admission.batch-size:10}") int batchSize,
        @Value("${app.admission.active-seconds:600}") int activeSeconds,
        @Value("${app.admission.wait-seconds:90}") int waitSeconds,
        @Value("${app.admission.max-waiting:20000}") int maxWaiting,
        @Value("${app.admission.secure-cookie:false}") boolean secureCookie) {
    public AdmissionSettings {
        if (capacity < 1 || capacity > 10000 || batchSize < 1 || batchSize > 200
                || activeSeconds < 60 || waitSeconds < 30 || maxWaiting < 1 || maxWaiting > 1000000)
            throw new IllegalArgumentException("Invalid admission limits");
    }
}
