package org.openphc.cce.receiver.exception;

/**
 * Thrown when FHIR-to-REST transformation fails for a resource type.
 */
public class ResourceTransformException extends RuntimeException {

    private final String resourceType;

    public ResourceTransformException(String resourceType, String message) {
        super(message);
        this.resourceType = resourceType;
    }

    public ResourceTransformException(String resourceType, String message, Throwable cause) {
        super(message, cause);
        this.resourceType = resourceType;
    }

    public String getResourceType() {
        return resourceType;
    }
}
