package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.BlockedRequest;
import dev.playforge.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A sandbox backed by one pool container; closing it destroys the container. */
public class DockerSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final String containerId;
    private final ContainerPool pool;
    private final DockerOps ops;
    private final EgressGateway egress;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String ip;
    /** Position in the gateway's log up to which this sandbox's refusals were reported. */
    private int logLine;

    public DockerSandbox(String containerId, ContainerPool pool, DockerOps ops) {
        this(containerId, pool, ops, null);
    }

    /** @param egress the gateway this container's traffic goes through, or null when the network is open */
    public DockerSandbox(String containerId, ContainerPool pool, DockerOps ops, EgressGateway egress) {
        this.containerId = containerId;
        this.pool = pool;
        this.ops = ops;
        this.egress = egress;
        if (egress != null) {
            // Refusals logged before this sandbox existed (an earlier container may have had the same IP) don't count.
            try {
                logLine = egress.lineCount();
            } catch (RuntimeException e) {
                log.warn("could not read the egress log length: {}", e.getMessage());
            }
        }
    }

    /**
     * Refusals logged since the previous call (or since the sandbox was handed out). Steps run one after another in
     * a sandbox, so "since the previous call" is the step that just ended; {@code since} is not needed.
     */
    @Override
    public synchronized List<BlockedRequest> blockedSince(Instant since) {
        if (egress == null) return List.of();
        try {
            if (ip == null || ip.isEmpty()) ip = ops.containerIp(containerId, egress.network());
            EgressGateway.Slice slice = egress.blocked(ip, logLine);
            logLine = slice.next();
            return slice.blocked();
        } catch (RuntimeException e) {
            log.warn("could not read refused requests of {}: {}", containerId, e.getMessage());
            return List.of();
        }
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
    public void exportDir(String relativePath, Path hostDir) {
        ops.copyDirOut(containerId, relativePath, hostDir);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) pool.discard(containerId);
    }
}
