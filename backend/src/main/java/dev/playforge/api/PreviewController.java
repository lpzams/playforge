package dev.playforge.api;

import dev.playforge.config.PlayforgeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Serves a project's last playtested build from {@code previews/<projectId>/}. Every request path is decoded,
 * resolved and checked to stay inside that directory; anything else is a plain 404.
 */
@RestController
public class PreviewController {

    private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9]{12}");

    private final PlayforgeProperties props;

    public PreviewController(PlayforgeProperties props) {
        this.props = props;
    }

    /** Without the trailing slash the game's relative asset URLs would resolve against /preview/. */
    @GetMapping("/preview/{projectId}")
    public ResponseEntity<Void> redirect(@PathVariable String projectId) {
        if (!PROJECT_ID.matcher(projectId).matches()) return ResponseEntity.notFound().build();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/preview/" + projectId + "/")).build();
    }

    @GetMapping("/preview/{projectId}/**")
    public ResponseEntity<Resource> file(@PathVariable String projectId, HttpServletRequest request) {
        if (!PROJECT_ID.matcher(projectId).matches()) return ResponseEntity.notFound().build();

        String prefix = request.getContextPath() + "/preview/" + projectId + "/";
        String uri = request.getRequestURI();
        if (!uri.startsWith(prefix)) return ResponseEntity.notFound().build();
        String rel = URLDecoder.decode(uri.substring(prefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html";

        Path root = props.previewDir().resolve(projectId).normalize();
        Path file;
        try {
            file = root.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            return ResponseEntity.notFound().build();
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();

        MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file));
    }
}
