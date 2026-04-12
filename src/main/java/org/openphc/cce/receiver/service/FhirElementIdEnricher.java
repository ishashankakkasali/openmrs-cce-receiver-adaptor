package org.openphc.cce.receiver.service;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enriches FHIR resource JSON by adding {@code id} (UUID) fields to array-based
 * sub-elements that map to OpenMRS database entities.
 *
 * <p><b>Problem:</b> The OpenMRS FHIR module (v1.2.2) maps FHIR element {@code id}
 * fields to database {@code uuid} columns but does NOT auto-generate them.
 * When a POST request contains sub-elements (e.g. {@code Patient.name[0]}) without
 * an {@code id}, Hibernate throws:
 * <pre>
 *   ConstraintViolationException: Column 'uuid' cannot be null
 * </pre>
 *
 * <p><b>Solution:</b> Before sending a FHIR resource to OpenMRS, this enricher
 * traverses known array fields that map to separate DB tables and injects a
 * random UUID {@code id} on any element missing one.
 *
 * <p>This is applied to all resource types going through the FHIR path (not REST).
 */
@Component
public class FhirElementIdEnricher {

    private static final Logger log = LoggerFactory.getLogger(FhirElementIdEnricher.class);

    /**
     * FHIR array fields known to map to separate OpenMRS DB tables
     * that require a UUID.
     */
    private static final Set<String> ARRAY_FIELDS_NEEDING_IDS = Set.of(
        // Patient
        "identifier", "name", "address", "telecom", "contact", "communication",
        // Encounter
        "participant", "location", "type", "diagnosis",
        // Observation
        "component", "performer", "referenceRange",
        // AllergyIntolerance
        "reaction",
        // Practitioner
        "qualification",
        // General
        "coding", "category", "interpretation", "note"
    );

    private final ObjectMapper objectMapper;

    public FhirElementIdEnricher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches a FHIR resource JSON by adding {@code id} UUID fields
     * to sub-elements that may need them for OpenMRS DB persistence.
     *
     * @param resourceJson the raw FHIR JSON
     * @return enriched JSON with id fields added where missing
     */
    public String enrich(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!(root instanceof ObjectNode rootNode)) {
                return resourceJson;
            }

            int added = addMissingIds(rootNode, 0);
            if (added > 0) {
                log.debug("Added {} missing element id(s) to {} resource",
                        added, rootNode.path("resourceType").asText("unknown"));
                return objectMapper.writeValueAsString(rootNode);
            }

            return resourceJson;
        } catch (Exception e) {
            log.warn("Failed to enrich FHIR element IDs: {}. Sending original payload.",
                    e.getMessage());
            return resourceJson;
        }
    }

    /**
     * Recursively traverses the JSON tree and adds {@code id} UUIDs
     * to object elements within known array fields.
     *
     * @param node the current JSON node
     * @param depth recursion depth (capped at 10 to prevent stack overflow)
     * @return number of IDs added
     */
    private int addMissingIds(ObjectNode node, int depth) {
        if (depth > 10) {
            return 0;
        }

        int count = 0;
        var fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode child = node.get(fieldName);

            if (child.isArray() && ARRAY_FIELDS_NEEDING_IDS.contains(fieldName)) {
                for (JsonNode element : child) {
                    if (element instanceof ObjectNode objElement) {
                        if (!objElement.has("id") || objElement.get("id").isNull()
                                || objElement.get("id").asText("").isBlank()) {
                            objElement.put("id", UUID.randomUUID().toString());
                            count++;
                        }
                        // Recurse into the element to handle nested arrays
                        count += addMissingIds(objElement, depth + 1);
                    }
                }
            } else if (child instanceof ObjectNode childObj) {
                // Recurse into nested objects (e.g., code, medicationCodeableConcept)
                count += addMissingIds(childObj, depth + 1);
            }
        }

        return count;
    }
}
