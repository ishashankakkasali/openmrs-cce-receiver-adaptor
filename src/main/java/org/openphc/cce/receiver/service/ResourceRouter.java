package org.openphc.cce.receiver.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openphc.cce.receiver.fhir.CapabilityStatementCache;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Routes individual {@link ResourceEntry} items to the appropriate OpenMRS endpoint
 * (FHIR or REST) based on the CapabilityStatement cache.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Classify each entry as FHIR-capable or REST-fallback</li>
 *   <li>Respect dependency ordering: Encounters before Orders, Patients first</li>
 *   <li>Track cross-reference UUIDs between Bundle entries</li>
 *   <li>Aggregate per-resource results</li>
 * </ul>
 */
@Service
public class ResourceRouter {

    private static final Logger log = LoggerFactory.getLogger(ResourceRouter.class);

    /**
     * Resource types that must be created before other resources can reference them.
     * Lower number = higher priority (created first).
     */
    private static final Map<String, Integer> DEPENDENCY_ORDER = Map.of(
            "Patient", 1,
            "Practitioner", 2,
            "Location", 3,
            "Medication", 4,
            "Encounter", 5
            // Everything else defaults to 10
    );

    /** Resource types that require REST fallback for creates (OpenMRS order model) */
    private static final Set<String> ORDER_TYPES = Set.of("ServiceRequest", "MedicationRequest");

    private final CapabilityStatementCache capabilityCache;
    private final OpenMrsFhirClient fhirClient;
    private final OpenMrsRestClient restClient;
    private final MeterRegistry meterRegistry;
    private final SourceIdMappingStore mappingStore;
    private final ObjectMapper objectMapper;

    public ResourceRouter(
            CapabilityStatementCache capabilityCache,
            OpenMrsFhirClient fhirClient,
            OpenMrsRestClient restClient,
            MeterRegistry meterRegistry,
            SourceIdMappingStore mappingStore,
            ObjectMapper objectMapper) {
        this.capabilityCache = capabilityCache;
        this.fhirClient = fhirClient;
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
        this.mappingStore = mappingStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Routes a list of resource entries to OpenMRS, respecting dependency order
     * and CapabilityStatement constraints.
     *
     * @param entries the resource entries to route
     * @return list of routing results, one per entry
     */
    public List<RoutingResult> route(List<ResourceEntry> entries) {
        // Sort by dependency order so Patients/Encounters are created before Orders/Observations
        List<ResourceEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> DEPENDENCY_ORDER.getOrDefault(e.resourceType(), 10)));

        log.info("Routing {} entries (sorted by dependency order)", sorted.size());

        // UUID mapping: fullUrl → server-assigned UUID (for cross-reference resolution)
        Map<String, String> uuidMap = new HashMap<>();
        List<RoutingResult> results = new ArrayList<>();

        for (ResourceEntry entry : sorted) {
            MDC.put("resourceType", entry.resourceType());
            MDC.put("method", entry.method());

            try {
                RoutingResult result = routeEntry(entry, uuidMap);
                results.add(result);

                // Track the UUID mapping for subsequent references
                if (result.isSuccess() && entry.fullUrl() != null && result.resourceId() != null) {
                    uuidMap.put(entry.fullUrl(), result.resourceId());
                }

                trackMetrics(result);
            } catch (Exception e) {
                log.error("Failed to route {} entry: {}", entry.resourceType(), e.getMessage(), e);
                results.add(RoutingResult.failure(
                        entry.resourceType(), null, "unknown", 500, null, e.getMessage()));
            } finally {
                MDC.remove("resourceType");
                MDC.remove("method");
            }
        }

        int succeeded = (int) results.stream().filter(RoutingResult::isSuccess).count();
        log.info("Routing complete: {}/{} succeeded", succeeded, results.size());

        return results;
    }

    /**
     * Routes a single resource entry to OpenMRS via FHIR or REST.
     *
     * <p>Before routing, the source-system ID is resolved via
     * {@link SourceIdMappingStore}:
     * <ul>
     *   <li>If a mapping exists → the resource is an <b>update</b>: the source ID
     *       is replaced with the OpenMRS UUID and the method is set to PUT.</li>
     *   <li>If no mapping exists → the resource is new: the source ID is stripped
     *       and the method is set to POST. On success, the mapping is stored.</li>
     * </ul>
     */
    private RoutingResult routeEntry(ResourceEntry entry, Map<String, String> uuidMap) {
        String resourceJson = resolveReferences(entry.resourceJson(), uuidMap);

        // --- Source-ID mapping: detect create vs update ---
        String sourceId = extractSourceId(resourceJson);
        String method = entry.method();

        if (sourceId != null && !sourceId.isBlank()) {
            Optional<String> openMrsUuid = mappingStore.getOpenMrsUuid(entry.resourceType(), sourceId);

            if ("DELETE".equalsIgnoreCase(method)) {
                if (openMrsUuid.isPresent()) {
                    resourceJson = replaceIdInJson(resourceJson, openMrsUuid.get());
                    log.info("Source ID '{}' mapped to OpenMRS UUID {} → DELETE",
                            sourceId, openMrsUuid.get());
                } else {
                    log.warn("DELETE for unknown source ID '{}' — no mapping found", sourceId);
                    return RoutingResult.failure(entry.resourceType(), null, "unknown", 404, null,
                            "Cannot delete: no mapping found for source ID " + sourceId);
                }
            } else {
                if (openMrsUuid.isPresent()) {
                    resourceJson = replaceIdInJson(resourceJson, openMrsUuid.get());
                    method = "PUT";
                    log.info("Source ID '{}' mapped to OpenMRS UUID {} → PUT update",
                            sourceId, openMrsUuid.get());
                } else {
                    resourceJson = removeIdFromJson(resourceJson);
                    method = "POST";
                    log.info("Source ID '{}' has no mapping → POST create", sourceId);
                }
            }
        }
        // --- End source-ID mapping ---

        String interactionCode = interactionCodeForMethod(method);

        // Route to FHIR or REST based on CapabilityStatement
        RoutingResult result;
        if (capabilityCache.canFhir(entry.resourceType(), interactionCode)) {
            log.debug("Routing {} via FHIR (operation={})", entry.resourceType(), interactionCode);
            result = fhirClient.send(entry.resourceType(), resourceJson, method);
        } else {
            log.debug("Routing {} via REST fallback (FHIR lacks '{}' support)",
                    entry.resourceType(), interactionCode);
            result = restClient.send(entry.resourceType(), resourceJson, method);
        }

        // Store mapping and cross-reference on successful create
        if (result.isSuccess() && sourceId != null && !sourceId.isBlank()
                && result.resourceId() != null) {
            if ("POST".equalsIgnoreCase(method)) {
                mappingStore.store(entry.resourceType(), sourceId, result.resourceId());
            }
            // Add cross-reference for other resources in the same Bundle
            uuidMap.put(entry.resourceType() + "/" + sourceId,
                    entry.resourceType() + "/" + result.resourceId());
        }

        return result;
    }

    /**
     * Resolves temporary Bundle references (e.g., "urn:uuid:xxx") to server-assigned UUIDs
     * from previously created resources.
     */
    private String resolveReferences(String resourceJson, Map<String, String> uuidMap) {
        String resolved = resourceJson;
        for (Map.Entry<String, String> mapping : uuidMap.entrySet()) {
            String tempRef = mapping.getKey();
            String serverUuid = mapping.getValue();
            if (resolved.contains(tempRef)) {
                resolved = resolved.replace(tempRef, serverUuid);
                log.debug("Resolved reference: {} → {}", tempRef, serverUuid);
            }
        }
        return resolved;
    }

    private void trackMetrics(RoutingResult result) {
        Counter.builder("cce.receiver.resources.routed")
                .tag("resourceType", result.resourceType())
                .tag("route", result.route())
                .tag("status", result.status())
                .register(meterRegistry).increment();
    }

    // ---- JSON helpers ----

    private String extractSourceId(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            String id = root.path("id").asText(null);
            return (id != null && !id.isEmpty()) ? id : null;
        } catch (Exception e) {
            log.debug("Could not extract ID from resource JSON: {}", e.getMessage());
            return null;
        }
    }

    private String replaceIdInJson(String resourceJson, String newId) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(resourceJson);
            root.put("id", newId);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to replace ID in JSON: {}", e.getMessage());
            return resourceJson;
        }
    }

    private String removeIdFromJson(String resourceJson) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(resourceJson);
            root.remove("id");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to remove ID from JSON: {}", e.getMessage());
            return resourceJson;
        }
    }

    private String interactionCodeForMethod(String method) {
        return switch (method.toUpperCase()) {
            case "POST" -> "create";
            case "PUT" -> "update";
            case "DELETE" -> "delete";
            case "PATCH" -> "patch";
            default -> "read";
        };
    }
}
