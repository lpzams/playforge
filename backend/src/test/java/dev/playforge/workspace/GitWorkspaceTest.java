package dev.playforge.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitWorkspaceTest {

    @TempDir
    Path dir;

    @Test
    void initAndCommit() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            assertThat(ws.head()).isEmpty();
            Files.writeString(dir.resolve("a.txt"), "hello");
            String hash = ws.commitAll("first");
            assertThat(hash).matches("[0-9a-f]{40}");
            assertThat(ws.head()).contains(hash);
        }
    }

    @Test
    void diffIncludesUntracked() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve("a.txt"), "hello");
            String head = ws.commitAll("first");
            Files.writeString(dir.resolve("b.txt"), "新文件");
            String diff = ws.diffWorkingTree(head);
            assertThat(diff).contains("b.txt").contains("新文件").doesNotContain("a.txt");
        }
    }

    @Test
    void diffAgainstEmptyTreeWhenNoBase() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve("a.txt"), "hello");
            assertThat(ws.diffWorkingTree(null)).contains("a.txt").contains("+hello");
        }
    }

    @Test
    void commitWithoutChangesReturnsHead() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve("a.txt"), "hello");
            String first = ws.commitAll("first");
            assertThat(ws.commitAll("second")).isEqualTo(first);
        }
    }

    @Test
    void discardUncommittedChangesRestoresHeadAndRemovesUntracked() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve(".gitignore"), "node_modules/\n");
            Files.writeString(dir.resolve("a.txt"), "hello");
            String head = ws.commitAll("first");
            Files.writeString(dir.resolve("a.txt"), "changed");
            Files.createDirectories(dir.resolve("src"));
            Files.writeString(dir.resolve("src/leftover.js"), "x");
            Files.createDirectories(dir.resolve("node_modules"));
            Files.writeString(dir.resolve("node_modules/dep.js"), "keep me");

            ws.discardUncommittedChanges();

            assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("hello");
            assertThat(dir.resolve("src/leftover.js")).doesNotExist();
            assertThat(dir.resolve("node_modules/dep.js")).exists();
            assertThat(ws.diffWorkingTree(head)).isEmpty();
        }
    }

    @Test
    void discardWithoutHeadRemovesUntracked() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve("leftover.js"), "x");
            ws.discardUncommittedChanges();
            assertThat(dir.resolve("leftover.js")).doesNotExist();
        }
    }

    @Test
    void commitIgnoresRepositoryHooks() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Path hook = dir.resolve(".git/hooks/pre-commit");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\nexit 1\n");
            hook.toFile().setExecutable(true);
            Files.writeString(dir.resolve("a.txt"), "hello");
            assertThat(ws.commitAll("first")).matches("[0-9a-f]{40}");
        }
    }

    @Test
    void commitStagesDeletions() throws Exception {
        try (GitWorkspace ws = GitWorkspace.openOrInit(dir)) {
            Files.writeString(dir.resolve("a.txt"), "hello");
            String first = ws.commitAll("first");
            Files.delete(dir.resolve("a.txt"));
            String second = ws.commitAll("delete");
            assertThat(second).isNotEqualTo(first);
            assertThat(ws.diffWorkingTree(second)).isEmpty();
        }
    }
}
