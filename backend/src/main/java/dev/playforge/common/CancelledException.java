package dev.playforge.common;

public class CancelledException extends RuntimeException {
    public CancelledException() {
        super("cancelled");
    }
}
