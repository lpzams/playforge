package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.Sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A sandbox backed by one pool container; closing it destroys the container. */
public class DockerSandbox implements Sandbox {

    private final String containerId;
    private final ContainerPool pool;
    private final DockerOps ops;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DockerSandbox(String containerId, ContainerPool pool, DockerOps ops) {
        this.containerId = containerId;
        this.pool = pool;
        this.ops = ops;
    }

    public String containerId() {
        return containerId;
    }

    @Override
    public Path workdir() {
        throw new UnsupportedOperationException("docker sandbox has no host working directory; use exec");
    }

    @Override
    public void syncIn(Path hostDir) {
        ops.copyIn(containerId, hostDir);
    }

    @Override
    public void syncOut(Path hostDir) {
        ops.copyOut(containerId, hostDir);
    }

    @Override
    public ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel,
                           Consumer<String> stdoutLine) {
        byte[] in = stdin == null ? null : stdin.getBytes(StandardCharsets.UTF_8);
        return ops.exec(containerId, cmd, in, timeout, cancel, stdoutLine);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) pool.discard(containerId);
    }
}
