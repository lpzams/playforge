package dev.playforge.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @TempDir
    Path tmp;

    @Test
    void redactsKnownSecretsInNestedPayloads() {
        SecretRedactor r = new SecretRedactor(List.of("sk-ant-oat01-ABCDEFGHIJKLMNOP"));
        Map<String, Object> out = r.redact(Map.of(
                "summary", "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-ABCDEFGHIJKLMNOP\nPATH=/usr/bin",
                "nested", Map.of("list", List.of("x sk-ant-oat01-ABCDEFGHIJKLMNOP y")),
                "n", 3));
        assertThat(out.toString()).doesNotContain("sk-ant-oat01-ABCDEFGHIJKLMNOP").contains("[REDACTED]").contains("PATH=/usr/bin");
        assertThat(out.get("n")).isEqualTo(3);
    }

    @Test
    void shortValuesAreIgnoredToAvoidMangling() {
        SecretRedactor r = new SecretRedactor(List.of("abc", ""));
        assertThat(r.redact(Map.of("t", "abc def"))).containsEntry("t", "abc def");
    }

    @Test
    void collectsTokensFromCodexAuthFile() throws Exception {
        Path auth = tmp.resolve("auth.json");
        Files.writeString(auth, "{\"OPENAI_API_KEY\":null,\"tokens\":{\"id_token\":\"eyJhbGciOiJSUzI1NiJ9.payload-long-enough\","
                + "\"access_token\":\"access-token-value-0123456789\",\"refresh_token\":\"refresh-token-value-0123456789\","
                + "\"account_id\":\"acc\"},\"last_refresh\":\"2026-11-09T00:00:00Z\"}");
        SecretRedactor r = SecretRedactor.fromSources("tok-from-env-0123456789", auth);
        String out = r.redact(Map.of("s", "a access-token-value-0123456789 b refresh-token-value-0123456789 c tok-from-env-0123456789")).get("s").toString();
        assertThat(out).isEqualTo("a [REDACTED] b [REDACTED] c [REDACTED]");
    }

    @Test
    void missingAuthFileIsFine() {
        SecretRedactor r = SecretRedactor.fromSources(null, tmp.resolve("nope.json"));
        assertThat(r.redact(Map.of("s", "hello"))).containsEntry("s", "hello");
    }
}
