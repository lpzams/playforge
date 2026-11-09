package dev.playforge.process;

import dev.playforge.common.CancellationToken;
import dev.playforge.common.CancelledException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs external processes: writes stdin, streams stdout, keeps a stderr tail, and on timeout or
 * cancellation kills the whole process tree before throwing.
 */
@Component
public class ProcessRunner {

    private static final int STDERR_TAIL_BYTES = 8 * 1024;

    /** Text mode: stdout delivered line by line (UTF-8). {@code stdin} may be null. */
    public ExecResult run(List<String> cmd, Map<String, String> extraEnv, byte[] stdin, Duration timeout,
                          CancellationToken cancel, Consumer<String> stdoutLine) {
        return execute(cmd, extraEnv, stdin == null ? null : new ByteArrayInputStream(stdin), timeout, cancel, out -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdoutLine.accept(line);
            }
        });
    }

    /** Binary mode: stdin copied from {@code stdin}, stdout copied verbatim to {@code stdout}. */
    public ExecResult runBinary(List<String> cmd, InputStream stdin, OutputStream stdout, Duration timeout,
                                CancellationToken cancel) {
        return execute(cmd, Map.of(), stdin, timeout, cancel, out -> out.transferTo(stdout));
    }

    @FunctionalInterface
    private interface StdoutHandler {
        void handle(InputStream out) throws IOException;
    }

    private ExecResult execute(List<String> cmd, Map<String, String> extraEnv, InputStream stdin, Duration timeout,
                               CancellationToken cancel, StdoutHandler stdoutHandler) {
        cancel.throwIfCancelled();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(extraEnv);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + cmd.getFirst(), e);
        }
        cancel.onCancel(() -> killTree(process));

        Thread writer = Thread.ofVirtual().start(() -> {
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) stdin.transferTo(in);
            } catch (IOException ignored) {
                // child exited before reading all input; its exit code tells the story
            }
        });
        TailBuffer stderr = new TailBuffer(STDERR_TAIL_BYTES);
        Thread errReader = Thread.ofVirtual().start(() -> {
            try (InputStream err = process.getErrorStream()) {
                err.transferTo(stderr);
            } catch (IOException ignored) {
                // stream closed when the process was killed
            }
        });
        Throwable[] stdoutFailure = new Throwable[1];
        Thread outReader = Thread.ofVirtual().start(() -> {
            try (InputStream out = process.getInputStream()) {
                stdoutHandler.handle(out);
            } catch (IOException ignored) {
                // stream closed when the process was killed
            } catch (RuntimeException e) {
                stdoutFailure[0] = e;
                killTree(process);
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new CancelledException();
        }
        if (!finished) {
            killTree(process);
            if (cancel.isCancelled()) throw new CancelledException();
            throw new ProcessTimeoutException(cmd.getFirst() + " timed out after " + timeout.toMillis() + " ms");
        }
        join(outReader);
        join(errReader);
        join(writer);
        if (cancel.isCancelled()) throw new CancelledException();
        if (stdoutFailure[0] instanceof RuntimeException re) throw re;
        return new ExecResult(process.exitValue(), stderr.toString());
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void join(Thread t) {
        try {
            t.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** OutputStream keeping only the last N bytes. */
    private static final class TailBuffer extends OutputStream {
        private final byte[] buf;
        private int start;
        private int size;

        TailBuffer(int capacity) {
            this.buf = new byte[capacity];
        }

        @Override
        public synchronized void write(int b) {
            int idx = (start + size) % buf.length;
            buf[idx] = (byte) b;
            if (size < buf.length) size++;
            else start = (start + 1) % buf.length;
        }

        @Override
        public synchronized String toString() {
            byte[] out = new byte[size];
            for (int i = 0; i < size; i++) out[i] = buf[(start + i) % buf.length];
            return new String(out, StandardCharsets.UTF_8);
        }
    }
}
