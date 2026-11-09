package dev.playforge.process;

import dev.playforge.common.CancellationToken;
import dev.playforge.common.CancelledException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ProcessRunnerTest {

    final ProcessRunner runner = new ProcessRunner();

    static List<String> child(String... args) {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", System.getProperty("java.class.path"),
                EchoMain.class.getName()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    static final Duration T = Duration.ofSeconds(30);

    @Test
    void streamsLinesInOrder() {
        List<String> lines = new CopyOnWriteArrayList<>();
        ExecResult r = runner.run(child("lines", "3"), Map.of(), null, T, new CancellationToken(), lines::add);
        assertThat(lines).containsExactly("l1", "l2", "l3");
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void stdinIsDelivered() {
        String text = "中文\"引号\"\n第二行 $HOME";
        List<String> lines = new CopyOnWriteArrayList<>();
        runner.run(child("echo"), Map.of(), text.getBytes(StandardCharsets.UTF_8), T, new CancellationToken(), lines::add);
        assertThat(String.join("\n", lines)).isEqualTo(text);
    }

    @Test
    void nonZeroExitAndStderrTail() {
        ExecResult r = runner.run(child("fail"), Map.of(), null, T, new CancellationToken(), l -> { });
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stderrTail()).contains("boom");
    }

    @Test
    void extraEnvReachesChildButNotArgs() {
        List<String> cmd = child("env", "PF_TEST_SECRET");
        List<String> lines = new CopyOnWriteArrayList<>();
        runner.run(cmd, Map.of("PF_TEST_SECRET", "s3cret"), null, T, new CancellationToken(), lines::add);
        assertThat(lines).containsExactly("s3cret");
        assertThat(cmd).noneMatch(a -> a.contains("s3cret"));
    }

    @Test
    void timeoutKillsProcess() {
        List<String> lines = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(child("sleep"), Map.of(), null, Duration.ofMillis(1500),
                new CancellationToken(), lines::add))
                .isInstanceOf(ProcessTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        long pid = Long.parseLong(lines.getFirst().trim());
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
    }

    @Test
    void cancelKillsProcess() {
        CancellationToken token = new CancellationToken();
        List<String> lines = new CopyOnWriteArrayList<>();
        try (var ex = Executors.newSingleThreadScheduledExecutor()) {
            ex.schedule(token::cancel, 1500, TimeUnit.MILLISECONDS);
            assertThatThrownBy(() -> runner.run(child("sleep"), Map.of(), null, T, token, lines::add))
                    .isInstanceOf(CancelledException.class);
        }
        long pid = Long.parseLong(lines.getFirst().trim());
        await().atMost(Duration.ofSeconds(5)).until(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
    }

    @Test
    void longLineDoesNotCrash() {
        List<String> lines = new CopyOnWriteArrayList<>();
        runner.run(child("longline", "2000000"), Map.of(), null, T, new CancellationToken(), lines::add);
        assertThat(lines).singleElement().satisfies(l -> assertThat(l).hasSize(2_000_000));
    }

    @Test
    void binaryRoundTrip() {
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExecResult r = runner.runBinary(child("echo"), new ByteArrayInputStream(data), out, T, new CancellationToken());
        assertThat(r.exitCode()).isZero();
        assertThat(out.toByteArray()).isEqualTo(data);
    }
}
