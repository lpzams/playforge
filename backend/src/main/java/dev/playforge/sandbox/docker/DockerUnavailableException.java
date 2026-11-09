package dev.playforge.sandbox.docker;

public class DockerUnavailableException extends RuntimeException {
    public DockerUnavailableException(String message) {
        super(message);
    }
}
