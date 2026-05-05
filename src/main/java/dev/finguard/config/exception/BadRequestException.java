package dev.finguard.config.exception;

/**
 * Thrown when a client request is malformed or contains invalid data.
 *
 * <p>Mapped to HTTP 400 by {@link GlobalExceptionHandler}.</p>
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
