package org.openphc.cce.receiver.model;

public record RoutingResult(
        String resourceType,
        String resourceId,
        String route,
        String status,
        int httpStatus,
        String responseBody,
        String errorMessage
) {
    public static RoutingResult success(String resourceType, String resourceId, String route, int httpStatus, String responseBody) {
        return new RoutingResult(resourceType, resourceId, route, "created", httpStatus, responseBody, null);
    }

    public static RoutingResult updated(String resourceType, String resourceId, String route, int httpStatus, String responseBody) {
        return new RoutingResult(resourceType, resourceId, route, "updated", httpStatus, responseBody, null);
    }

    /** The resource already exists in OpenMRS (e.g. a redelivered referral); nothing was written. */
    public static RoutingResult duplicate(String resourceType, String resourceId, String route) {
        return new RoutingResult(resourceType, resourceId, route, "duplicate", 200, null, null);
    }

    public static RoutingResult failure(String resourceType, String route, int httpStatus, String errorMessage) {
        return new RoutingResult(resourceType, null, route, "failed", httpStatus, null, errorMessage);
    }
}
