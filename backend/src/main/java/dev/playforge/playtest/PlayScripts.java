package dev.playforge.playtest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls the playthrough module out of the AI player's reply. */
public final class PlayScripts {

    static final int MAX_CHARS = 20_000;
    private static final Pattern FENCE = Pattern.compile("```(?:js|javascript|mjs)?[ \\t]*\\r?\\n(.*?)\\r?\\n?```", Pattern.DOTALL);

    private PlayScripts() {
    }

    /** The first fenced block (or the bare reply) that default-exports the play function. */
    public static Optional<String> extract(String reply) {
        if (reply == null) return Optional.empty();
        Matcher m = FENCE.matcher(reply);
        while (m.find()) {
            String code = m.group(1).strip();
            if (usable(code)) return Optional.of(code);
        }
        String bare = reply.strip();
        return !bare.contains("```") && usable(bare) ? Optional.of(bare) : Optional.empty();
    }

    private static boolean usable(String code) {
        return code.contains("export default") && code.length() <= MAX_CHARS;
    }
}
