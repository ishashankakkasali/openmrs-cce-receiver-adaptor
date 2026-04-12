package org.openphc.cce.receiver.model;

/**
 * Represents the result of routing and sending a single resource to OpenMRS.
 *
 * @param resourceType  the FHIR resource type that was processed
 * @param resourceId    the resource ID (UUID) returned by OpenMRS, or the original ID
 * @param route         which path was used: "fhir" or "rest"
 * @param status        outcome: "created", "updated", "deleted", "failed"
 * @param httpStatus    the HTTP status code from OpenMRS
 * @param responseBody  the raw response body from OpenMRS (truncated for logging)
 * @param errorMessage  error message if the operation failed, null on success
 */
public record RoutingResult(
        String resourceType,
        String resourceId,
        String route,
        String status,
        int httpStatus,
        String responseBody,
        String errorMessage
) {

    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    public static RoutingResult success(String resourceType, String resourceId,
                                         String route, String status, int httpStatus, String responseBody) {
        return new RoutingResult(resourceType, resourceId, route, status, httpStatus, responseBody, null);
    }

    public static RoutingResult failure(String resourceType, String resourceId,
                                         String route, int httpStatus, String responseBody, String errorMessage) {
        return new RoutingResult(resourceType, resourceId, route, "failed", httpStatus, responseBody, errorMessage);
    }
}
