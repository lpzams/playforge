package dev.playforge.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Append-only per-run event log. Assigns contiguous seq numbers (1..n) per run, persists each event,
 * then pushes it to live subscribers in seq order.
 */
@Service
public class RunEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RunEventPublisher.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RunEventMapper mapper;
    private final ObjectMapper json;
    private final Map<Long, RunChannel> channels = new ConcurrentHashMap<>();

    public RunEventPublisher(RunEventMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    private static final class RunChannel {
        long lastSeq = -1; // -1 = not yet loaded from DB
        final List<Consumer<RunEvent>> listeners = new CopyOnWriteArrayList<>();
    }

    private RunChannel channel(long runId) {
        return channels.computeIfAbsent(runId, id -> new RunChannel());
    }

    public RunEvent publish(long runId, RunEventType type, String source, Map<String, Object> payload) {
        RunChannel ch = channel(runId);
        synchronized (ch) {
            if (ch.lastSeq < 0) ch.lastSeq = mapper.maxSeq(runId);
            RunEvent event = new RunEvent(runId, ch.lastSeq + 1, Instant.now(), type, source,
                    payload == null ? Map.of() : payload);
            RunEventRow row = new RunEventRow();
            row.setRunId(runId);
            row.setSeq(event.seq());
            row.setTs(event.ts());
            row.setType(type);
            row.setSource(source);
            row.setPayloadJson(write(event.payload()));
            mapper.insert(row);
            ch.lastSeq = event.seq();
            for (Consumer<RunEvent> l : ch.listeners) {
                try {
                    l.accept(event);
                } catch (RuntimeException e) {
                    log.warn("event listener failed for run {}: {}", runId, e.toString());
                }
            }
            return event;
        }
    }

    public List<RunEvent> history(long runId, long afterSeq) {
        return mapper.selectList(new LambdaQueryWrapper<RunEventRow>()
                        .eq(RunEventRow::getRunId, runId)
                        .gt(RunEventRow::getSeq, afterSeq)
                        .orderByAsc(RunEventRow::getSeq))
                .stream()
                .map(r -> new RunEvent(r.getRunId(), r.getSeq(), r.getTs(), r.getType(), r.getSource(), read(r.getPayloadJson())))
                .toList();
    }

    public AutoCloseable subscribe(long runId, Consumer<RunEvent> listener) {
        RunChannel ch = channel(runId);
        ch.listeners.add(listener);
        return () -> ch.listeners.remove(listener);
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("event payload is not serializable", e);
        }
    }

    private Map<String, Object> read(String text) {
        try {
            return json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt event payload", e);
        }
    }
}
