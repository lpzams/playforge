package dev.playforge.process;

public class ProcessTimeoutException extends RuntimeException {
    public ProcessTimeoutException(String message) {
        super(message);
    }
}
