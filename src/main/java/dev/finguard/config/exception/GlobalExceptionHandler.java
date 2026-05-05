package dev.finguard.config.exception;

import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for all REST controllers.
 *
 * <p>Converts exceptions to RFC 9457 Problem Detail responses with consistent
 * structure across the entire API. Each handler produces a {@link ProblemDetail}
 * with a type URI, human-readable title, detail message, and timestamp.</p>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 - Problem Details for HTTP APIs</a>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://finguard.dev/errors/";

    // ================================================================
    // Business exceptions
    // ================================================================

    /**
     * Resource not found — 404.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        log.debug("Resource not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "resource-not-found"));
        problem.setTitle("Resource Not Found");
        problem.setProperty("timestamp", Instant.now());

        if (ex.getResourceName() != null) {
            problem.setProperty("resource", ex.getResourceName());
        }
        return problem;
    }

    /**
     * Bad request — 400.
     */
    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        log.debug("Bad request: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "bad-request"));
        problem.setTitle("Bad Request");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Service unavailable (Ollama down, models not loaded, etc.) — 503.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("Service unavailable: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "service-unavailable"));
        problem.setTitle("Service Unavailable");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("service", ex.getServiceName());
        return problem;
    }

    /**
     * Unprocessable entity (CSV parsing failure, model training failure, etc.) — 422.
     */
    @ExceptionHandler(ProcessingException.class)
    public ProblemDetail handleProcessingError(ProcessingException ex) {
        log.warn("Processing failed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "processing-error"));
        problem.setTitle("Processing Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ================================================================
    // Spring / framework exceptions
    // ================================================================

    /**
     * Bean validation failures (e.g., @Valid on request body) — 400.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        log.debug("Validation failed: {}", ex.getMessage());

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(URI.create(TYPE_PREFIX + "validation-error"));
        problem.setTitle("Validation Error");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    /**
     * Type mismatch on request params — typically invalid enum values — 400.
     *
     * <p>Example: {@code ?config=INVALID} when the parameter expects {@code DetectionConfig}.</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String rejectedValue = ex.getValue() != null ? ex.getValue().toString() : "null";
        Class<?> requiredType = ex.getRequiredType();
        String allowedValues = "";

        if (requiredType != null && requiredType.isEnum()) {
            Object[] constants = requiredType.getEnumConstants();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(constants[i]);
            }
            allowedValues = sb.toString();
        }

        String detail = String.format("Parameter '%s' has invalid value '%s'", paramName, rejectedValue);
        if (!allowedValues.isEmpty()) {
            detail += ". Allowed values: " + allowedValues;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create(TYPE_PREFIX + "invalid-parameter"));
        problem.setTitle("Invalid Parameter");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parameter", paramName);
        problem.setProperty("rejectedValue", rejectedValue);
        if (!allowedValues.isEmpty()) {
            problem.setProperty("allowedValues", allowedValues);
        }
        return problem;
    }

    /**
     * Invalid request parameter value (e.g. non-numeric page number) — 400.
     */
    @ExceptionHandler(InvalidParameterException.class)
    public ProblemDetail handleInvalidParameter(InvalidParameterException ex) {
        log.debug("Invalid parameter: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "invalid-parameter"));
        problem.setTitle("Invalid Parameter");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parameter", ex.getParameterName());
        problem.setProperty("rejectedValue", ex.getRejectedValue());
        return problem;
    }

    /**
     * Missing required request parameter — 400.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        String detail = String.format("Required parameter '%s' of type '%s' is missing",
                ex.getParameterName(), ex.getParameterType());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create(TYPE_PREFIX + "missing-parameter"));
        problem.setTitle("Missing Parameter");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parameter", ex.getParameterName());
        return problem;
    }

    /**
     * Missing multipart request part (e.g., file field absent in upload) — 400.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail handleMissingPart(MissingServletRequestPartException ex) {
        String detail = String.format("Required request part '%s' is missing", ex.getRequestPartName());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create(TYPE_PREFIX + "missing-parameter"));
        problem.setTitle("Missing Parameter");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parameter", ex.getRequestPartName());
        return problem;
    }

    /**
     * File too large — 413.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("File upload too large: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size (500MB)");
        problem.setType(URI.create(TYPE_PREFIX + "file-too-large"));
        problem.setTitle("File Too Large");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ================================================================
    // Infrastructure exceptions
    // ================================================================

    /**
     * CSV parsing errors — 422.
     */
    @ExceptionHandler(CsvValidationException.class)
    public ProblemDetail handleCsvValidation(CsvValidationException ex) {
        log.warn("CSV validation failed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "CSV file is malformed or contains invalid data: " + ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "csv-validation-error"));
        problem.setTitle("CSV Validation Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Client disconnected mid-response (e.g., browser navigated away during long polling).
     * This is expected and harmless — no response can be sent, so just log at DEBUG.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected before response could be written: {}", ex.getMessage());
        // Nothing to write — the connection is already gone.
    }

    /**
     * I/O errors (file read/write, network issues) — 500.
     */
    @ExceptionHandler(IOException.class)
    public ProblemDetail handleIOException(IOException ex) {
        // Broken pipe means the client closed the connection — not a server error.
        if (isBrokenPipe(ex)) {
            log.debug("Client disconnected (broken pipe): {}", ex.getMessage());
            return null;
        }
        log.error("I/O error: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An I/O error occurred while processing the request");
        problem.setType(URI.create(TYPE_PREFIX + "io-error"));
        problem.setTitle("I/O Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Spring framework status exceptions (e.g. NoResourceFoundException 404,
     * MethodNotAllowedException 405) — forward the embedded HTTP status.
     *
     * <p>Without this handler the catch-all would convert all of these to 500.</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        log.debug("Response status exception: {} {}", ex.getStatusCode(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getMessage());
        problem.setType(URI.create(TYPE_PREFIX + "request-error"));
        problem.setTitle("Request Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ================================================================
    // Catch-all
    // ================================================================

    private boolean isBrokenPipe(IOException ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"));
    }

    /**
     * Catch-all for unexpected exceptions — 500.
     *
     * <p>Logs the full stack trace and returns a safe message to the client
     * (never leaking internal details).</p>
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please check the server logs for details.");
        problem.setType(URI.create(TYPE_PREFIX + "internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
