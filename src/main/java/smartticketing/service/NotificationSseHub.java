package smartticketing.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class NotificationSseHub {
    private static final Map<Long, List<SseEmitter>> CLIENTS = new ConcurrentHashMap<>();

    public static SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        CLIENTS.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable remove = () -> remove(userId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            remove(userId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public static void publish(Long userId) {
        if (userId == null) return;
        var emitters = CLIENTS.get(userId);
        if (emitters == null) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data("changed"));
            } catch (IOException e) {
                remove(userId, emitter);
            }
        }
    }

    private static void remove(Long userId, SseEmitter emitter) {
        var emitters = CLIENTS.get(userId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) CLIENTS.remove(userId, emitters);
    }
}
