package dev.finguard.config.exception;

/**
 * Thrown when a request parameter has a value that cannot be parsed or is out of range.
 *
 * <p>Mapped to HTTP 400 by {@link GlobalExceptionHandler}.</p>
 */
public class InvalidParameterException extends RuntimeException {

    private final String parameterName;
    private final String rejectedValue;

    public InvalidParameterException(String parameterName, String rejectedValue) {
        super(String.format("Parameter '%s' has invalid value '%s'", parameterName, rejectedValue));
        this.parameterName = parameterName;
        this.rejectedValue = rejectedValue;
    }

    public String getParameterName() {
        return parameterName;
    }

    public String getRejectedValue() {
        return rejectedValue;
    }
}