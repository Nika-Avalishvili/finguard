package dev.finguard.config.exception;

/**
 * Thrown when an internal processing step fails (CSV parsing, model inference, etc.).
 *
 * <p>Mapped to HTTP 422 (Unprocessable Entity) by {@link GlobalExceptionHandler}.</p>
 */
public class ProcessingException extends RuntimeException {

    public ProcessingException(String message) {
        super(message);
    }

    public ProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
