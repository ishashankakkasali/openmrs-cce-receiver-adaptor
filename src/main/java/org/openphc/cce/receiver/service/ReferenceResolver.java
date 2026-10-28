package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Iterator;
import java.util.Map;

@Component
public class ReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceResolver.class);
    private final RestClient restClient;
    private final ProviderResolver providerResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static final Map<String, String> RESOURCE_TYPE_TO_ENDPOINT = Map.ofEntries(
            Map.entry("Patient", "/patient"),
            Map.entry("Practitioner", "/provider"),
            Map.entry("Location", "/location"),
            Map.entry("Encounter", "/encounter"),
            Map.entry("Medication", "/drug"),
            Map.entry("Organization", "/location"),
            Map.entry("Observation", "/obs"),
            Map.entry("Condition", "/condition")
    );

    public ReferenceResolver(@Qualifier("openmrsRestClient") RestClient restClient,
                             ProviderResolver providerResolver) {
        this.restClient = restClient;
        this.providerResolver = providerResolver;
    }

    public ObjectNode resolveReferences(ObjectNode resourceNode, SourceIdMappingStore mappingStore) {
        walkAndResolve(resourceNode, mappingStore);
        return resourceNode;
    }

    private void walkAndResolve(JsonNode node, SourceIdMappingStore mappingStore) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("reference")) {
                String ref = obj.path("reference").asText("");
                String resolved = resolveReference(ref, mappingStore);
                if (resolved != null && !resolved.equals(ref)) {
                    obj.put("reference", resolved);
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                walkAndResolve(fields.next().getValue(), mappingStore);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                walkAndResolve(child, mappingStore);
            }
        }
    }

    private String resolveReference(String reference, SourceIdMappingStore mappingStore) {
        if (reference == null || reference.isBlank()) return reference;

        // Check cross-reference map first (urn:uuid: references within Bundle)
        if (reference.startsWith("urn:uuid:") && mappingStore != null) {
            String mapped = mappingStore.getOpenMrsUuid(reference);
            if (mapped != null) {
                log.debug("Resolved cross-reference {} → {}", reference, mapped);
                return mapped;
            }
        }

        // Parse ResourceType/identifier
        String[] parts = reference.split("/");
        if (parts.length < 2) return reference;

        String resourceType = parts[parts.length - 2];
        String identifier = parts[parts.length - 1];

        // Check if this is a cross-reference fullUrl
        if (mappingStore != null) {
            String mapped = mappingStore.getOpenMrsUuid(reference);
            if (mapped != null) {
                return resourceType + "/" + mapped;
            }
        }

        // If identifier looks like a UUID, verify it in OpenMRS
        if (identifier.matches(UUID_PATTERN)) {
            String endpoint = RESOURCE_TYPE_TO_ENDPOINT.getOrDefault(resourceType, "/" + resourceType.toLowerCase());
            if (verifyExists(endpoint, identifier)) {
                return reference; // valid OpenMRS UUID — keep as-is
            }
            // Not found — fall through to search
        }

        // Search OpenMRS for this reference
        String resolved = searchForReference(resourceType, identifier);
        if (resolved != null) {
            return resourceType + "/" + resolved;
        }

        log.warn("Unable to resolve reference: {}", reference);
        return reference;
    }

    private boolean verifyExists(String endpoint, String uuid) {
        try {
            restClient.get()
                    .uri(endpoint + "/{uuid}", uuid)
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String searchForReference(String resourceType, String identifier) {
        // Practitioners map to providers by exact identifier only — a partial "q=" hit would make an
        // unrelated provider the orderer
        if ("Practitioner".equals(resourceType)) {
            return providerResolver.findExternal(identifier);
        }

        String endpoint = RESOURCE_TYPE_TO_ENDPOINT.getOrDefault(resourceType, "/" + resourceType.toLowerCase());

        try {
            String searchParam = "Patient".equals(resourceType) ? "identifier" : "q";
            String response = restClient.get()
                    .uri(endpoint + "?{param}={value}&v=default", searchParam, identifier)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                if (uuid != null) {
                    log.debug("Resolved {} reference '{}' → '{}'", resourceType, identifier, uuid);
                    return uuid;
                }
            }
        } catch (Exception e) {
            log.warn("Reference search failed for {}/{}: {}", resourceType, identifier, e.getMessage());
        }
        return null;
    }
}
