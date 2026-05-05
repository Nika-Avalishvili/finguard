package dev.finguard.config.exception;

/**
 * Thrown when a required service (Ollama, ML models, vector store) is not available.
 *
 * <p>Mapped to HTTP 503 by {@link GlobalExceptionHandler}.</p>
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

    public ServiceUnavailableException(String serviceName, String message) {
        super(String.format("Service '%s' unavailable: %s", serviceName, message));
        this.serviceName = serviceName;
    }

    public ServiceUnavailableException(String serviceName, String message, Throwable cause) {
        super(String.format("Service '%s' unavailable: %s", serviceName, message), cause);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
