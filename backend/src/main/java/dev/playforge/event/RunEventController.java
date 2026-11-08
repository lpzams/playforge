package dev.playforge.event;

import dev.playforge.run.RunStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * SSE stream of a run's events. Replays history after {@code Last-Event-ID}, then streams live events,
 * and completes after RUN_FINISHED.
 */
@RestController
public class RunEventController {

    private final RunEventPublisher publisher;
    private final RunStore store;

    public RunEventController(RunEventPublisher publisher, RunStore store) {
        this.publisher = publisher;
        this.store = store;
    }

    @GetMapping(path = "/api/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long runId,
                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        try {
            store.getRun(runId);
        } catch (NoSuchElementException e) {
            // ResponseStatusException -> plain 404 (a JSON error body can't be written for text/event-stream)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        SseEmitter emitter = new SseEmitter(0L);
        Stream stream = new Stream(emitter, parseSeq(lastEventId));

        // Hold the stream lock while subscribing and replaying: live events that arrive meanwhile wait,
        // then are de-duplicated by seq, so nothing is lost or sent twice.
        synchronized (stream) {
            stream.subscription = publisher.subscribe(runId, stream::send);
            for (RunEvent e : publisher.history(runId, stream.lastSent)) {
                stream.send(e);
            }
        }
        emitter.onCompletion(stream::unsubscribe);
        emitter.onTimeout(stream::unsubscribe);
        emitter.onError(t -> stream.unsubscribe());
        return emitter;
    }

    private static long parseSeq(String header) {
        if (header == null || header.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Stream {
        final SseEmitter emitter;
        long lastSent;
        boolean done;
        AutoCloseable subscription;

        Stream(SseEmitter emitter, long lastSent) {
            this.emitter = emitter;
            this.lastSent = lastSent;
        }

        synchronized void send(RunEvent e) {
            if (done || e.seq() <= lastSent) return;
            try {
                emitter.send(SseEmitter.event().id(Long.toString(e.seq())).name(e.type().name()).data(e));
                lastSent = e.seq();
                if (e.type() == RunEventType.RUN_FINISHED) {
                    done = true;
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException ex) {
                done = true;
                emitter.completeWithError(ex);
            }
        }

        void unsubscribe() {
            AutoCloseable s = subscription;
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // removing a listener cannot meaningfully fail
                }
            }
        }
    }
}
