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
