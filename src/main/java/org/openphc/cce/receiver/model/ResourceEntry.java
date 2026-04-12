package org.openphc.cce.receiver.model;

/**
 * Represents a single resource entry extracted from an incoming FHIR payload
 * (either a standalone resource or an entry within a Bundle).
 *
 * <p>Used by the {@code BundleSplitter} to normalize both standalone and Bundle
 * payloads into a uniform list of route-able entries.
 *
 * @param resourceType  FHIR resource type (e.g. "Patient", "ServiceRequest")
 * @param resourceJson  raw JSON string of the individual resource
 * @param method        HTTP method from Bundle entry request (POST, PUT, DELETE) or "POST" for standalone
 * @param fullUrl       Bundle entry fullUrl (for cross-reference resolution), may be null for standalone
 */
public record ResourceEntry(
        String resourceType,
        String resourceJson,
        String method,
        String fullUrl
) {

    /**
     * Maps the HTTP method to a FHIR interaction code for CapabilityStatement lookup.
     *
     * @return interaction code: "create", "update", "delete", or "read"
     */
    public String toInteractionCode() {
        return switch (method.toUpperCase()) {
            case "POST" -> "create";
            case "PUT" -> "update";
            case "DELETE" -> "delete";
            case "PATCH" -> "patch";
            default -> "read";
        };
    }
}
