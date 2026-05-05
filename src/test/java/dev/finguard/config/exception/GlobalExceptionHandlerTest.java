package dev.finguard.config.exception;

import com.opencsv.exceptions.CsvValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Each test verifies the correct HTTP status, type URI, title, and
 * detail message for a specific exception type.</p>
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ================================================================
    // Business exceptions
    // ================================================================

    @Nested
    @DisplayName("Business Exceptions")
    class BusinessExceptions {

        @Test
        @DisplayName("ResourceNotFoundException → 404 with resource details")
        void resourceNotFound_returnsNotFoundWithDetails() {
            var ex = new ResourceNotFoundException("Alert", "id", 42L);

            ProblemDetail result = handler.handleResourceNotFound(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(result.getTitle()).isEqualTo("Resource Not Found");
            assertThat(result.getDetail()).contains("Alert", "id", "42");
            assertThat(result.getType().toString()).endsWith("resource-not-found");
            assertThat(result.getProperties()).containsEntry("resource", "Alert");
            assertThat(result.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("ResourceNotFoundException with simple message → 404")
        void resourceNotFound_simpleMessage_returnsNotFound() {
            var ex = new ResourceNotFoundException("No such item");

            ProblemDetail result = handler.handleResourceNotFound(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(result.getDetail()).isEqualTo("No such item");
            assertThat(result.getProperties()).doesNotContainKey("resource");
        }

        @Test
        @DisplayName("BadRequestException → 400 with detail")
        void badRequest_returnsBadRequestWithMessage() {
            var ex = new BadRequestException("File path is required");

            ProblemDetail result = handler.handleBadRequest(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(result.getTitle()).isEqualTo("Bad Request");
            assertThat(result.getDetail()).isEqualTo("File path is required");
            assertThat(result.getType().toString()).endsWith("bad-request");
        }

        @Test
        @DisplayName("ServiceUnavailableException → 503 with service name")
        void serviceUnavailable_returns503WithServiceName() {
            var ex = new ServiceUnavailableException("Ollama", "Connection refused on port 11434");

            ProblemDetail result = handler.handleServiceUnavailable(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(result.getTitle()).isEqualTo("Service Unavailable");
            assertThat(result.getDetail()).contains("Ollama", "Connection refused");
            assertThat(result.getProperties()).containsEntry("service", "Ollama");
        }

        @Test
        @DisplayName("ProcessingException → 422 with detail")
        void processingError_returnsUnprocessableEntity() {
            var ex = new ProcessingException("CSV has 8 columns but 11 expected");

            ProblemDetail result = handler.handleProcessingError(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(result.getTitle()).isEqualTo("Processing Error");
            assertThat(result.getDetail()).contains("8 columns");
        }
    }

    // ================================================================
    // Framework exceptions
    // ================================================================

    @Nested
    @DisplayName("Framework Exceptions")
    class FrameworkExceptions {

        @Test
        @DisplayName("MethodArgumentNotValidException → 400 with field errors map")
        void validationError_returnsBadRequestWithFieldErrors() throws NoSuchMethodException {
            // Build a BindingResult with two field errors
            var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError("request", "experimentName",
                    "Experiment name is required"));
            bindingResult.addError(new FieldError("request", "config",
                    "Detection config is required"));

            MethodParameter param = new MethodParameter(
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyMethod"), -1);

            var ex = new MethodArgumentNotValidException(param, bindingResult);

            ProblemDetail result = handler.handleValidationErrors(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(result.getTitle()).isEqualTo("Validation Error");
            assertThat(result.getType().toString()).endsWith("validation-error");

            @SuppressWarnings("unchecked")
            var fieldErrors = (java.util.Map<String, String>) result.getProperties().get("fieldErrors");
            assertThat(fieldErrors)
                    .containsEntry("experimentName", "Experiment name is required")
                    .containsEntry("config", "Detection config is required");
        }

        @Test
        @DisplayName("MethodArgumentTypeMismatchException for enum → 400 with allowed values")
        void typeMismatch_forEnum_includesAllowedValues() {
            var ex = new MethodArgumentTypeMismatchException(
                    "INVALID", TestEnum.class, "config", null,
                    new IllegalArgumentException("No enum constant"));

            ProblemDetail result = handler.handleTypeMismatch(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(result.getTitle()).isEqualTo("Invalid Parameter");
            assertThat(result.getDetail()).contains("config", "INVALID");
            assertThat(result.getProperties()).containsEntry("parameter", "config");
            assertThat(result.getProperties()).containsEntry("rejectedValue", "INVALID");
            assertThat(result.getProperties().get("allowedValues").toString())
                    .contains("VALUE_A", "VALUE_B");
        }

        @Test
        @DisplayName("MissingServletRequestParameterException → 400 with param name")
        void missingParam_returnsBadRequestWithParamName() {
            var ex = new MissingServletRequestParameterException("config", "DetectionConfig");

            ProblemDetail result = handler.handleMissingParam(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(result.getTitle()).isEqualTo("Missing Parameter");
            assertThat(result.getDetail()).contains("config", "DetectionConfig");
            assertThat(result.getProperties()).containsEntry("parameter", "config");
        }

        @Test
        @DisplayName("MaxUploadSizeExceededException → 413")
        void maxUploadSize_returnsPayloadTooLarge() {
            var ex = new MaxUploadSizeExceededException(500_000_000L);

            ProblemDetail result = handler.handleMaxUploadSize(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
            assertThat(result.getTitle()).isEqualTo("File Too Large");
            assertThat(result.getDetail()).contains("500MB");
        }
    }

    // ================================================================
    // Infrastructure exceptions
    // ================================================================

    @Nested
    @DisplayName("Infrastructure Exceptions")
    class InfrastructureExceptions {

        @Test
        @DisplayName("CsvValidationException → 422 with CSV detail")
        void csvValidation_returnsUnprocessableEntity() {
            var ex = new CsvValidationException("Unexpected column count at line 15");

            ProblemDetail result = handler.handleCsvValidation(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(result.getTitle()).isEqualTo("CSV Validation Error");
            assertThat(result.getDetail()).contains("malformed", "line 15");
        }

        @Test
        @DisplayName("IOException → 500 without leaking internal path")
        void ioError_returnsInternalErrorSafeMessage() {
            var ex = new IOException("/var/data/secret-path/file.csv (No such file)");

            ProblemDetail result = handler.handleIOException(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(result.getTitle()).isEqualTo("I/O Error");
            // Should NOT leak the actual file path
            assertThat(result.getDetail()).doesNotContain("/var/data/secret-path");
            assertThat(result.getDetail()).contains("I/O error");
        }
    }

    // ================================================================
    // Catch-all
    // ================================================================

    @Nested
    @DisplayName("Catch-All Handler")
    class CatchAll {

        @Test
        @DisplayName("Unexpected RuntimeException → 500 with safe message")
        void unexpectedError_returnsInternalErrorWithoutLeaking() {
            var ex = new RuntimeException("NullPointerException at UserService.java:42");

            ProblemDetail result = handler.handleUnexpected(ex);

            assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(result.getTitle()).isEqualTo("Internal Server Error");
            // Should NOT leak the actual exception message
            assertThat(result.getDetail()).doesNotContain("NullPointerException");
            assertThat(result.getDetail()).contains("unexpected error");
        }
    }

    // ================================================================
    // Test helpers
    // ================================================================

    /** Dummy method used to create a MethodParameter for MethodArgumentNotValidException. */
    @SuppressWarnings("unused")
    private Object dummyMethod() { return null; }

    /** Test enum for type mismatch testing. */
    enum TestEnum { VALUE_A, VALUE_B }
}
