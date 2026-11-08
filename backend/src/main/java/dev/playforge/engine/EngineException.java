package dev.playforge.engine;

import java.time.Duration;
import java.util.Optional;

public class EngineException extends Exception {

    private final boolean retryable;
    private final Duration retryAfter;

    public EngineException(String message, boolean retryable, Duration retryAfter) {
        super(message);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
