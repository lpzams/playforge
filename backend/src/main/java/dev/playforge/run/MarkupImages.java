package dev.playforge.run;

import dev.playforge.config.PlayforgeProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Marked screenshots of follow-up runs, one PNG per run, kept next to the workspace root (outside every game
 * repo, so they never reach a sandbox mirror or a commit).
 */
@Service
public class MarkupImages {

    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String PREFIX = "data:image/png;base64,";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final Path dir;

    public MarkupImages(PlayforgeProperties props) {
        Path ws = props.workspaceDir();
        this.dir = ws.resolveSibling(ws.getFileName() + "-attachments");
    }

    /**
     * Decodes a {@code data:image/png;base64,} URL.
     * @throws IllegalArgumentException when it is not a PNG of at most {@link #MAX_BYTES}
     */
    public static byte[] decode(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith(PREFIX)) {
            throw new IllegalArgumentException("markup.image must be a data:image/png;base64 URL");
        }
        String b64 = dataUrl.substring(PREFIX.length());
        if (b64.length() > (MAX_BYTES / 3 + 1) * 4) throw new IllegalArgumentException("markup.image is larger than 4 MB");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("markup.image is not valid base64");
        }
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("markup.image is larger than 4 MB");
        if (bytes.length < PNG.length || !Arrays.equals(bytes, 0, PNG.length, PNG, 0, PNG.length)) {
            throw new IllegalArgumentException("markup.image is not a PNG");
        }
        return bytes;
    }

    public void save(long runId, byte[] png) {
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, runId + "-", ".tmp");
            Files.write(tmp, png);
            Files.move(tmp, file(runId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not store the marked screenshot of run " + runId, e);
        }
    }

    public Optional<byte[]> load(long runId) {
        try {
            Path f = file(runId);
            return Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path file(long runId) {
        return dir.resolve(runId + ".png");
    }
}
