package dev.playforge.sandbox.local;

import dev.playforge.config.PlayforgeProperties;
import dev.playforge.sandbox.Sandbox;
import dev.playforge.sandbox.SandboxProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** P1 sandbox: works directly in the host workspace directory; syncing is a no-op. */
@Component
public class LocalSandboxProvider implements SandboxProvider {

    private final Path root;

    public LocalSandboxProvider(PlayforgeProperties props) {
        this.root = props.workspaceRoot();
    }

    @Override
    public Sandbox acquire(String projectId) {
        Path dir = root.resolve(projectId);
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
            public void close() {
            }
        };
    }
}
