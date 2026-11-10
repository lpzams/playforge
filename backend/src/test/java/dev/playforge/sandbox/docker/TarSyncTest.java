package dev.playforge.sandbox.docker;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TarSyncTest {

    @TempDir
    Path tmp;

    Path dir(String name) throws Exception {
        return Files.createDirectories(tmp.resolve(name));
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static byte[] pack(Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TarSync.pack(dir, out);
        return out.toByteArray();
    }

    static List<String> entryNames(byte[] tar) throws Exception {
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) if (!e.isDirectory()) names.add(e.getName());
        }
        return names;
    }

    static byte[] tarWith(String entryName, boolean preserveAbsolute) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            byte[] data = "evil".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry e = new TarArchiveEntry(entryName, preserveAbsolute);
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    @Test
    void roundTripPreservesFilesAndChinese() throws Exception {
        Path src = dir("src");
        write(src.resolve("src/main.js"), "console.log('hi')");
        write(src.resolve("中文.txt"), "你好");
        Files.createDirectories(src.resolve("assets"));
        Path dst = dir("dst");

        TarSync.unpackMirror(new ByteArrayInputStream(pack(src)), dst);

        assertThat(Files.readString(dst.resolve("src/main.js"))).isEqualTo("console.log('hi')");
        assertThat(Files.readString(dst.resolve("中文.txt"))).isEqualTo("你好");
        assertThat(dst.resolve("assets")).isDirectory();
    }

    @Test
    void packExcludesTopLevelDirs() throws Exception {
        Path src = dir("src");
        write(src.resolve("node_modules/a.js"), "a");
        write(src.resolve("dist/b.js"), "b");
        write(src.resolve(".git/config"), "c");
        write(src.resolve("src/node_modules_note.js"), "d");

        assertThat(entryNames(pack(src))).containsExactly("src/node_modules_note.js");
    }

    @Test
    void mirrorDeletesStaleFilesButKeepsExcluded() throws Exception {
        Path src = dir("src");
        write(src.resolve("new.js"), "new");
        Path dst = dir("dst");
        write(dst.resolve("old.js"), "old");
        write(dst.resolve("node_modules/dep.js"), "dep");
        write(dst.resolve(".git/HEAD"), "ref");

        TarSync.unpackMirror(new ByteArrayInputStream(pack(src)), dst);

        assertThat(dst.resolve("old.js")).doesNotExist();
        assertThat(dst.resolve("node_modules/dep.js")).exists();
        assertThat(dst.resolve(".git/HEAD")).exists();
        assertThat(dst.resolve("new.js")).exists();
    }

    @Test
    void rejectsTraversalEntryAndLeavesTargetUntouched() throws Exception {
        Path dst = dir("dst");
        write(dst.resolve("keep.js"), "keep");

        assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(tarWith("../evil.txt", false)), dst))
                .isInstanceOf(SecurityException.class);
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
        assertThat(dst.resolve("keep.js")).exists();
    }

    @Test
    void rejectsNestedTraversalEntry() throws Exception {
        Path dst = dir("dst");
        assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(tarWith("src/../../evil.txt", false)), dst))
                .isInstanceOf(SecurityException.class);
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteEntry() throws Exception {
        Path dst = dir("dst");
        assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(tarWith("/etc/evil", true)), dst))
                .isInstanceOf(SecurityException.class);
    }

    /** Case-insensitive NTFS: ".GIT/config" would overwrite the host repo's .git/config. */
    @Test
    void rejectsGitDirVariantsAndExcludedDirsInAnyCase() throws Exception {
        Path dst = dir("dst");
        write(dst.resolve(".git/config"), "original");
        for (String name : java.util.List.of(".GIT/config", ".git./config", ".git /config", "./.Git/hooks/pre-commit",
                "src/.git/config", "GIT~1/config", "NODE_MODULES/x.js", "Dist/x.js", "dist./x.js")) {
            assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(tarWith(name, false)), dst))
                    .as(name).isInstanceOf(SecurityException.class);
        }
        assertThat(Files.readString(dst.resolve(".git/config"))).isEqualTo("original");
    }

    @Test
    void rejectsWindowsHostileNames() throws Exception {
        Path dst = dir("dst");
        for (String name : java.util.List.of("src/a:b.js", "CON", "src/aux.js", "src/what?.js")) {
            assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(tarWith(name, false)), dst))
                    .as(name).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void acceptsDotSlashPrefixedEntriesFromGnuTar() throws Exception {
        Path dst = dir("dst");
        TarSync.unpackMirror(new ByteArrayInputStream(tarWith("./src/main.js", false)), dst);
        assertThat(Files.readString(dst.resolve("src/main.js"))).isEqualTo("evil");
    }

    @Test
    void rejectsSymlinkEntry() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            TarArchiveEntry link = new TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("/etc");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
        }
        Path dst = dir("dst");
        assertThatThrownBy(() -> TarSync.unpackMirror(new ByteArrayInputStream(out.toByteArray()), dst))
                .isInstanceOf(SecurityException.class);
    }
}
