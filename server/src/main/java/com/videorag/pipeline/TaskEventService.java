package com.videorag.pipeline;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TaskEventService {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long mediaId, ProgressEvent initial) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list =
                emitters.computeIfAbsent(mediaId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(mediaId, emitter));
        emitter.onTimeout(() -> remove(mediaId, emitter));
        emitter.onError(error -> remove(mediaId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(initial, MediaType.APPLICATION_JSON));
        } catch (IOException error) {
            remove(mediaId, emitter);
            emitter.complete();
        }
        return emitter;
    }

    public void publish(Long mediaId, ProgressEvent event) {
        List<SseEmitter> list = emitters.get(mediaId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException error) {
                remove(mediaId, emitter);
                emitter.complete();
            }
        }
    }

    private void remove(Long mediaId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(mediaId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) {
            emitters.remove(mediaId);
        }
    }

    public record ProgressEvent(Long mediaId, String status, Integer progress,
                                String stage, String message) {
    }
}
