package org.openphc.cce.receiver.exception;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Centralized exception handler that maps custom exceptions to consistent
 * JSON error responses.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FhirParsingException.class)
    public ResponseEntity<Map<String, Object>> handleFhirParsingException(FhirParsingException ex) {
        log.warn("FHIR parsing error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "FHIR_PARSING_ERROR", ex.getMessage());
    }

    @ExceptionHandler(ResourceTransformException.class)
    public ResponseEntity<Map<String, Object>> handleResourceTransformException(ResourceTransformException ex) {
        log.warn("Resource transform error for {}: {}", ex.getResourceType(), ex.getMessage());
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFORM_ERROR", ex.getMessage());
    }

    @ExceptionHandler(OpenMrsClientException.class)
    public ResponseEntity<Map<String, Object>> handleOpenMrsClientException(OpenMrsClientException ex) {
        log.error("OpenMRS client error ({}): {}", ex.getStatusCode(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_GATEWAY, "OPENMRS_CLIENT_ERROR", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = Map.of(
                "error", Map.of(
                        "code", code,
                        "message", message
                ),
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString()
        );
        return ResponseEntity.status(status).body(body);
    }
}
