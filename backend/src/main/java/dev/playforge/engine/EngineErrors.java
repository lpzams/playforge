package dev.playforge.engine;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared rules for turning CLI failures into {@link EngineException}s. */
public final class EngineErrors {

    private static final Pattern TRANSIENT = Pattern.compile("rate limit|usage limit|overloaded|\\b429\\b");

    private EngineErrors() {
    }

    /** Rate limits, usage limits and overload are worth retrying later; anything else is not. */
    public static boolean isRetryable(String message) {
        return message != null && TRANSIENT.matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    public static EngineException fromErrorText(String engine, String message) {
        return new EngineException(engine + " reported an error: " + message, isRetryable(message), null);
    }
}
