package org.openphc.cce.receiver.exception;

/**
 * Thrown when an incoming payload cannot be parsed as a valid FHIR R4 resource.
 */
public class FhirParsingException extends RuntimeException {

    public FhirParsingException(String message) {
        super(message);
    }

    public FhirParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
