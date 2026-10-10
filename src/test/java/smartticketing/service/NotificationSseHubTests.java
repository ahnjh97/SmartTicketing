package smartticketing.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class NotificationSseHubTests {
    @Test void localDeliveryRemovesClosedClientsAndContinuesToOtherClients() throws Exception {
        var clients=(Map<Long,List<SseEmitter>>)ReflectionTestUtils.getField(NotificationSseHub.class,"CLIENTS");
        long user=Long.MAX_VALUE;
        var closed=mock(SseEmitter.class);var active=mock(SseEmitter.class);
        doThrow(new IllegalStateException("closed")).when(closed).send(any(SseEmitter.SseEventBuilder.class));
        clients.put(user,new CopyOnWriteArrayList<>(List.of(closed,active)));
        try {
            NotificationSseHub.publish(user);
            verify(active).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(clients.get(user)).containsExactly(active);
            NotificationSseHub.publish(user);
            verify(active,times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(closed).send(any(SseEmitter.SseEventBuilder.class));
        } finally { clients.remove(user); }
    }
}
