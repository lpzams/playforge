package dev.playforge.sandbox;

import java.nio.file.Path;

/** Isolated place where agents edit the files of one project. */
public interface Sandbox extends AutoCloseable {
    Path workdir();

    /** Copy the host workspace into the sandbox. */
    void syncIn(Path hostDir);

    /** Copy the files in the sandbox back to the host workspace. */
    void syncOut(Path hostDir);

    @Override
    void close();
}
