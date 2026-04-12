package org.openphc.cce.receiver.fhir;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Caches the OpenMRS FHIR R4 CapabilityStatement to determine which
 * resources support which operations (create, read, update, delete, patch, search-type).
 *
 * <p>Fetches {@code /metadata} on startup and refreshes periodically.
 * The routing layer uses this cache to decide whether a resource can go
 * through the FHIR endpoint or must fall back to REST.
 *
 * <p>Thread-safe: the capabilities map is replaced atomically via volatile reference.
 */
@Component
public class CapabilityStatementCache {

    private static final Logger log = LoggerFactory.getLogger(CapabilityStatementCache.class);

    private final RestClient fhirRestClient;
    private final ObjectMapper objectMapper;

    /** resourceType → set of supported interaction codes (e.g. "create", "read", "update") */
    private volatile Map<String, Set<String>> capabilities = Collections.emptyMap();
    private volatile Instant lastRefreshed = null;

    public CapabilityStatementCache(
            @Qualifier("fhirRestClient") RestClient fhirRestClient,
            ObjectMapper objectMapper) {
        this.fhirRestClient = fhirRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Refreshes the capability cache on startup and every 6 hours.
     * Uses {@code fixedDelay} so the next refresh starts 6 hours after the previous one completes.
     */
    @Scheduled(initialDelay = 0, fixedDelayString = "${openmrs.capability-refresh-ms:21600000}")
    public void refresh() {
        log.info("Refreshing CapabilityStatement from OpenMRS FHIR endpoint...");
        try {
            String json = fhirRestClient.get()
                    .uri("/metadata")
                    .retrieve()
                    .body(String.class);

            Map<String, Set<String>> parsed = parseCapabilityStatement(json);
            this.capabilities = Collections.unmodifiableMap(parsed);
            this.lastRefreshed = Instant.now();

            log.info("CapabilityStatement refreshed: {} resource types loaded", parsed.size());
            if (log.isDebugEnabled()) {
                parsed.forEach((type, ops) ->
                        log.debug("  {} → {}", type, ops));
            }
        } catch (RestClientException e) {
            log.warn("Failed to refresh CapabilityStatement: {}. Using cached version.", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error refreshing CapabilityStatement", e);
        }
    }

    /**
     * Checks whether the OpenMRS FHIR endpoint supports a given operation
     * for a specific resource type.
     *
     * @param resourceType FHIR resource type (e.g. "Patient", "ServiceRequest")
     * @param operation    interaction code (e.g. "create", "read", "update", "delete", "patch", "search-type")
     * @return true if the operation is supported via FHIR
     */
    public boolean canFhir(String resourceType, String operation) {
        Set<String> ops = capabilities.get(resourceType);
        return ops != null && ops.contains(operation);
    }

    /**
     * Returns all supported operations for a given resource type.
     *
     * @param resourceType FHIR resource type
     * @return unmodifiable set of interaction codes, or empty set if unknown
     */
    public Set<String> getSupportedOperations(String resourceType) {
        return capabilities.getOrDefault(resourceType, Collections.emptySet());
    }

    /**
     * Returns all known resource types from the CapabilityStatement.
     */
    public Set<String> getSupportedResourceTypes() {
        return capabilities.keySet();
    }

    /**
     * Returns when the cache was last successfully refreshed, or null if never.
     */
    public Instant getLastRefreshed() {
        return lastRefreshed;
    }

    /**
     * Returns the full capabilities map (for diagnostics / health endpoint).
     */
    public Map<String, Set<String>> getCapabilities() {
        return capabilities;
    }

    /**
     * Parses the CapabilityStatement JSON and extracts resource → interaction mappings.
     */
    private Map<String, Set<String>> parseCapabilityStatement(String json) {
        Map<String, Set<String>> result = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode restArray = root.path("rest");

            for (JsonNode rest : restArray) {
                JsonNode resources = rest.path("resource");
                for (JsonNode resource : resources) {
                    String type = resource.path("type").asText();
                    Set<String> interactions = new HashSet<>();

                    JsonNode interactionArray = resource.path("interaction");
                    for (JsonNode interaction : interactionArray) {
                        interactions.add(interaction.path("code").asText());
                    }

                    result.put(type, interactions);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse CapabilityStatement JSON", e);
        }
        return result;
    }
}
