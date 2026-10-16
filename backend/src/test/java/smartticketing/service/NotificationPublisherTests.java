package smartticketing.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationPublisherTests {
    @SuppressWarnings("unchecked")
    private Map<Long,List<SseEmitter>> clients() {
        return (Map<Long,List<SseEmitter>>) ReflectionTestUtils.getField(NotificationSseHub.class,"CLIENTS");
    }

    @Test void remoteWakeupIsDeliveredLocallyWithoutRebroadcastAndOwnEchoIsIgnored() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var publisher = new NotificationPublisher(redis,true,"jdbc:mysql://shared/lab");
        var client = mock(SseEmitter.class);
        clients().put(991L,new CopyOnWriteArrayList<>(List.of(client)));
        try {
            publisher.onMessage(message(UUID.randomUUID()+":991"),null);
            verify(client).send(any(SseEmitter.SseEventBuilder.class));
            verifyNoInteractions(redis);
            publisher.onMessage(message(ReflectionTestUtils.getField(publisher,"origin")+":991"),null);
            publisher.onMessage(message("broken"),null);
            verifyNoMoreInteractions(client);
        } finally { clients().remove(991L); }
    }

    @Test void redisFailureDoesNotFailCommittedWorkOrSuppressLocalDelivery() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var publisher = new NotificationPublisher(redis,true,"jdbc:mysql://shared/lab");
        doThrow(new IllegalStateException("offline")).when(redis).convertAndSend(anyString(),anyString());
        var client = mock(SseEmitter.class);
        clients().put(992L,new CopyOnWriteArrayList<>(List.of(client)));
        try {
            assertThatCode(() -> publisher.publish(992L)).doesNotThrowAnyException();
            verify(client).send(any(SseEmitter.SseEventBuilder.class));
        } finally { clients().remove(992L); }
    }

    @Test void notificationsAreNotSentBeforeCommitOrOnRollback() {
        var received = new ArrayList<Long>();
        TransactionSynchronizationManager.initSynchronization();
        try {
            NotificationService.publishAfterCommit(1L,received::add);
            assertThat(received).isEmpty();
            var callbacks=TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(c -> c.afterCompletion(1)); // rollback does not call afterCommit
            assertThat(received).isEmpty();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        TransactionSynchronizationManager.initSynchronization();
        try {
            NotificationService.publishAfterCommit(2L,received::add);
            TransactionSynchronizationManager.getSynchronizations().forEach(c -> c.afterCommit());
            assertThat(received).containsExactly(2L);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void disabledBroadcastDoesNotAccessRedisAndNamespacesAreIsolated() {
        var redis=mock(StringRedisTemplate.class);
        var publisher=new NotificationPublisher(redis,false,"db-a");
        publisher.publish(993L);
        verifyNoInteractions(redis);
        assertThat(publisher.channel()).isEqualTo(new NotificationPublisher(redis,true,"db-a").channel());
        assertThat(publisher.channel()).isNotEqualTo(new NotificationPublisher(redis,true,"db-b").channel());
    }

    private Message message(String body) {
        var message=mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
