package dev.playforge.process;

/** Exit code plus the last 8 KB of stderr. */
public record ExecResult(int exitCode, String stderrTail) {}
