package dev.playforge.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes known credential values from engine output before it is persisted or streamed. Containers hold the
 * Claude token (env) and Codex tokens (auth.json), so an agent running {@code env} would otherwise leak them.
 */
public class SecretRedactor {

    static final String MASK = "[REDACTED]";
    /** Shorter values are skipped: masking them would mangle ordinary text. */
    static final int MIN_SECRET_LENGTH = 12;

    private final List<String> secrets;

    public SecretRedactor(List<String> secrets) {
        this.secrets = secrets.stream()
                .filter(s -> s != null && s.length() >= MIN_SECRET_LENGTH)
                .distinct()
                .sorted((a, b) -> b.length() - a.length()) // longest first, in case one contains another
                .toList();
    }

    /** Secrets = the Claude token plus every token value in the Codex auth file (when readable). */
    public static SecretRedactor fromSources(String claudeToken, Path codexAuthFile) {
        List<String> values = new ArrayList<>();
        if (claudeToken != null) values.add(claudeToken);
        try {
            if (codexAuthFile != null && Files.isRegularFile(codexAuthFile)) {
                JsonNode root = new ObjectMapper().readTree(Files.readString(codexAuthFile));
                if (root.path("OPENAI_API_KEY").isTextual()) values.add(root.get("OPENAI_API_KEY").asText());
                root.path("tokens").forEach(n -> {
                    if (n.isTextual()) values.add(n.asText());
                });
            }
        } catch (Exception ignored) {
            // unreadable auth file: nothing to collect from it
        }
        return new SecretRedactor(values);
    }

    public Map<String, Object> redact(Map<String, Object> payload) {
        if (secrets.isEmpty() || payload == null) return payload;
        Map<String, Object> out = new LinkedHashMap<>();
        payload.forEach((k, v) -> out.put(k, redactValue(v)));
        return out;
    }

    private Object redactValue(Object v) {
        if (v instanceof String s) {
            String r = s;
            for (String secret : secrets) r = r.replace(secret, MASK);
            return r;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new HashMap<>();
            m.forEach((k, x) -> out.put(k, redactValue(x)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(this::redactValue).toList();
        return v;
    }
}
