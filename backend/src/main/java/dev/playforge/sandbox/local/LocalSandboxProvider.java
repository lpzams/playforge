package dev.playforge.sandbox.local;

import dev.playforge.common.CancellationToken;
import dev.playforge.config.PlayforgeProperties;
import dev.playforge.process.ExecResult;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** Development sandbox: works directly in the host workspace directory; syncing is a no-op. */
@Component
@ConditionalOnProperty(name = "playforge.sandbox.provider", havingValue = "local", matchIfMissing = true)
public class LocalSandboxProvider implements SandboxProvider {

    private final PlayforgeProperties props;

    public LocalSandboxProvider(PlayforgeProperties props) {
        this.props = props;
    }

    @Override
    public Sandbox acquire(String projectId) {
        Path dir = props.workspaceDir().resolve(projectId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Sandbox() {
            @Override
            public Path workdir() {
                return dir;
            }

            @Override
            public void syncIn(Path hostDir) {
            }

            @Override
            public void syncOut(Path hostDir) {
            }

            @Override
            public ExecResult exec(List<String> cmd, String stdin, Duration timeout, CancellationToken cancel,
                                   Consumer<String> stdoutLine) {
                throw new UnsupportedOperationException(
                        "the local sandbox cannot run CLI engines; set playforge.sandbox.provider=docker");
            }

            @Override
            public void exportDir(String relativePath, Path hostDir) {
                Path src = dir.resolve(relativePath).normalize();
                if (!src.startsWith(dir) || !Files.isDirectory(src)) {
                    throw new IllegalStateException("no such directory in sandbox: " + relativePath);
                }
                java.io.ByteArrayOutputStream tar = new java.io.ByteArrayOutputStream();
                dev.playforge.sandbox.docker.TarSync.pack(src, tar);
                dev.playforge.sandbox.docker.TarSync.unpackMirror(new java.io.ByteArrayInputStream(tar.toByteArray()), hostDir);
            }

            @Override
            public void close() {
            }
        };
    }
}
