package dev.playforge.sandbox.docker;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** The Docker operations the sandbox needs. Working directory inside containers is /workspace. */
public interface DockerOps {

    /** True when the Docker daemon answers. */
    boolean available();

    /** Starts a detached container and returns its id. */
    String runDetached(DockerRunSpec spec);

    /** {@code docker exec -i -w /workspace <id> cmd...}; stdin may be null. */
    ExecResult exec(String containerId, List<String> cmd, byte[] stdin, Duration timeout,
                    CancellationToken cancel, Consumer<String> stdoutLine);

    /** Makes /workspace mirror hostDir (excluding node_modules, dist, .git). */
    void copyIn(String containerId, Path hostDir);

    /** Makes hostDir mirror /workspace (excluding node_modules, dist, .git). */
    void copyOut(String containerId, Path hostDir);

    /**
     * Makes hostDir mirror /workspace/&lt;relativePath&gt;.
     * @throws IllegalArgumentException if relativePath is absolute or contains ".."
     * @throws IllegalStateException    if the directory does not exist in the container
     */
    void copyDirOut(String containerId, String relativePath, Path hostDir);

    /** {@code docker rm -f}; a missing container is not an error. */
    void remove(String containerId);

    boolean healthy(String containerId);

    /** Force-removes every container carrying all of these labels; returns how many were removed. */
    int removeLabeled(java.util.Map<String, String> labels);
}
