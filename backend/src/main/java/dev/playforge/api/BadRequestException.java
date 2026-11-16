package dev.playforge.api;

/** The request is well-formed JSON but its content is invalid (answered with 400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
