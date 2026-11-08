package dev.playforge.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A project's git repository on the host. One repo per game project. */
public final class GitWorkspace implements AutoCloseable {

    private static final PersonIdent IDENT = new PersonIdent("PlayForge", "playforge@localhost");

    private final Git git;

    private GitWorkspace(Git git) {
        this.git = git;
    }

    public static GitWorkspace openOrInit(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Files.isDirectory(dir.resolve(".git"))) return new GitWorkspace(Git.open(dir.toFile()));
            return new GitWorkspace(Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new IllegalStateException("git init failed in " + dir, e);
        }
    }

    public Optional<String> head() {
        try {
            ObjectId id = git.getRepository().resolve("HEAD");
            return Optional.ofNullable(id).map(ObjectId::getName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Unified diff from {@code baseCommit} (null = empty tree) to the working tree, including untracked files. */
    public String diffWorkingTree(String baseCommit) {
        Repository repo = git.getRepository();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter fmt = new DiffFormatter(out)) {
            fmt.setRepository(repo);
            fmt.setPathFilter(new NotIgnoredFilter(1));
            fmt.format(fmt.scan(baseTree(repo, baseCommit), new FileTreeIterator(repo)));
            fmt.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stages everything (incl. deletions) and commits. Returns the current head when nothing changed. */
    public String commitAll(String message) {
        try {
            Optional<String> head = head();
            Status status = git.status().call();
            if (head.isPresent() && status.isClean()) return head.get();
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            return git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT).setSign(false).setNoVerify(true)
                    .call().getName();
        } catch (GitAPIException e) {
            throw new IllegalStateException("git commit failed", e);
        }
    }

    /**
     * Restores tracked files to HEAD and deletes untracked, non-ignored files (ignored ones such as
     * node_modules are kept). Used before a new run so leftovers of a failed run are not folded into it.
     */
    public void discardUncommittedChanges() {
        try {
            if (head().isPresent()) git.reset().setMode(ResetCommand.ResetType.HARD).call();
            git.clean().setCleanDirectories(true).setIgnore(true).call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("git reset/clean failed", e);
        }
    }

    private static AbstractTreeIterator baseTree(Repository repo, String commit) throws IOException {
        if (commit == null) return new EmptyTreeIterator();
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser p = new CanonicalTreeParser();
            p.reset(reader, walk.parseCommit(repo.resolve(commit)).getTree().getId());
            return p;
        }
    }

    @Override
    public void close() {
        git.close();
    }
}
