package dev.playforge.sandbox;

import dev.playforge.common.CancellationToken;
import dev.playforge.process.ExecResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** Isolated place where agents edit the files of one project. */
public interface Sandbox extends AutoCloseable {

    /** Host path of the working directory. Only the local sandbox supports it; Docker sandboxes throw. */
    Path workdir();

    /** Make the sandbox match the host workspace (mirror: files absent on the host are removed). */
    void syncIn(Path hostDir);

    /** Make the host workspace match the sandbox (mirror). */
    void syncOut(Path hostDir);

    /** Runs a command in the sandbox working directory; {@code stdin} may be null. */
    ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel, Consumer<String> stdoutLine);

    /**
     * Mirrors a directory of the sandbox working directory (e.g. "dist") to a host directory.
     * @throws IllegalStateException if the directory does not exist in the sandbox
     */
    void exportDir(String relativePath, Path hostDir);

    @Override
    void close();
}
