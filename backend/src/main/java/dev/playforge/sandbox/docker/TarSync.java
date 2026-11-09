package dev.playforge.sandbox.docker;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves a project tree between host and sandbox as a tar stream. Top-level node_modules, dist and .git
 * are never transferred. Unpacking mirrors the archive (stale files are deleted) and refuses any entry
 * that could write outside the target directory.
 */
public final class TarSync {

    static final Set<String> EXCLUDED_DIRS = Set.of("node_modules", "dist", ".git");

    private TarSync() {
    }

    public static void pack(Path dir, OutputStream out) {
        try {
            TarArchiveOutputStream tar = new TarArchiveOutputStream(out, "UTF-8");
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (d.equals(dir)) return FileVisitResult.CONTINUE;
                    if (d.getParent().equals(dir) && EXCLUDED_DIRS.contains(d.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    tar.putArchiveEntry(new TarArchiveEntry(d.toFile(), name(dir, d) + "/"));
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE; // skip symlinks/special files
                    TarArchiveEntry e = new TarArchiveEntry(f.toFile(), name(dir, f));
                    tar.putArchiveEntry(e);
                    Files.copy(f, tar);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
            tar.finish();
            tar.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void unpackMirror(InputStream in, Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("playforge-sync", ".tar");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            validate(tmp, root);           // pass 1: nothing is touched unless every entry is safe
            clean(root);
            extract(tmp, root);            // pass 2
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    private static void validate(Path tarFile, Path root) throws IOException {
        try (TarArchiveInputStream tar = open(tarFile)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.isSymbolicLink() || e.isLink()) {
                    throw new SecurityException("link entries are not allowed: " + e.getName());
                }
                if (!e.isDirectory() && !e.isFile()) {
                    throw new SecurityException("unsupported entry type: " + e.getName());
                }
                target(root, e.getName());
            }
        }
    }

    private static void extract(Path tarFile, Path root) throws IOException {
        try (TarArchiveInputStream tar = open(tarFile)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                Path target = target(root, e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Deletes everything in root except the excluded top-level directories. */
    private static void clean(Path root) throws IOException {
        Files.createDirectories(root);
        try (Stream<Path> top = Files.list(root)) {
            for (Path p : top.toList()) {
                if (Files.isDirectory(p) && EXCLUDED_DIRS.contains(p.getFileName().toString())) continue;
                deleteRecursively(p);
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(p)) {
                for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        } else {
            Files.delete(p);
        }
    }

    private static Path target(Path root, String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("absolute path in archive: " + name);
        }
        for (String seg : n.split("/")) {
            if (seg.equals("..")) throw new SecurityException("path traversal in archive: " + name);
        }
        Path target = root.resolve(n).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("entry escapes target directory: " + name);
        }
        return target;
    }

    private static TarArchiveInputStream open(Path tarFile) throws IOException {
        return new TarArchiveInputStream(Files.newInputStream(tarFile), "UTF-8");
    }

    private static String name(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
