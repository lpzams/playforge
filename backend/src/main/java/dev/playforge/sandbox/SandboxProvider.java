package dev.playforge.sandbox;

public interface SandboxProvider {
    Sandbox acquire(String projectId);
}
