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
        // Reconnect through the admission/auth filters instead of retaining an unlimited connection.
        SseEmitter emitter = new SseEmitter(60_000L);
        CLIENTS.compute(userId,(id,clients) -> {
            if(clients==null) clients=new CopyOnWriteArrayList<>();
            clients.add(emitter);return clients;
        });

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
            } catch (IOException | IllegalStateException e) {
                remove(userId, emitter);
            }
        }
    }

    private static void remove(Long userId, SseEmitter emitter) {
        CLIENTS.computeIfPresent(userId,(id,emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
