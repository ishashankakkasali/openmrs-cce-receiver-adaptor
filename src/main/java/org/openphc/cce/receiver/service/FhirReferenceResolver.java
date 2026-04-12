package org.openphc.cce.receiver.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resolves external identifiers in FHIR resources to OpenMRS-compatible UUIDs
 * before forwarding to the OpenMRS FHIR endpoint.
 *
 * <h3>Problem</h3>
 * Source systems (CCE, SPICE, etc.) use their own identifier schemes in FHIR
 * references — e.g. {@code "Patient/260402-NEW1-7777"} (UPI value) or
 * {@code "Practitioner/HLC-PRAC-2025-00005"} (facility code). OpenMRS FHIR
 * requires references to use server-assigned UUIDs.
 *
 * <h3>Solution</h3>
 * This resolver walks the JSON tree of every outbound FHIR resource, finds
 * {@code "reference": "Type/id"} fields where {@code id} is <b>not</b> a UUID,
 * and searches OpenMRS to resolve the actual UUID:
 * <ul>
 *   <li>{@code Patient/&lt;x&gt;} → {@code GET /Patient?identifier=x}</li>
 *   <li>{@code Practitioner/&lt;x&gt;} → {@code GET /Practitioner?identifier=x}</li>
 *   <li>{@code Location/&lt;x&gt;} → {@code GET /Location?name=x} (fallback)</li>
 * </ul>
 *
 * <p>For Encounters specifically, this resolver also maps the {@code type}
 * coding display name (e.g. {@code "VISIT_ENCOUNTER"}) to the matching
 * OpenMRS encounter-type UUID and system.
 *
 * <p>Resolved references are cached in-memory to avoid repeated lookups.
 * Resolution is <b>best-effort</b>: if a reference cannot be resolved the
 * original value is forwarded as-is and OpenMRS handles the error.
 *
 * @see OpenMrsFhirClient
 */
@Component
public class FhirReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(FhirReferenceResolver.class);

    /** Standard UUID format: 8-4-4-4-12 hex digits. */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** FHIR relative reference: ResourceType/id. */
    private static final Pattern REF_PATTERN = Pattern.compile("^([A-Z][a-zA-Z]+)/(.+)$");

    /** OpenMRS encounter-type coding system URI. */
    private static final String ENCOUNTER_TYPE_SYSTEM =
            "http://fhir.openmrs.org/code-system/encounter-type";

    /** OpenMRS-native concept coding system URI. */
    private static final String OPENMRS_CONCEPT_SYSTEM = "http://openmrs.org/concepts";

    /**
     * Fields in a FHIR resource that contain {@code CodeableConcept} values
     * whose {@code coding[]} arrays should be resolved to OpenMRS concept UUIDs.
     */
    private static final Set<String> CODEABLE_CONCEPT_FIELDS = Set.of(
            // Primary concept codes
            "code", "medicationCodeableConcept", "vaccineCode",
            // Observation value & site
            "valueCodeableConcept", "bodySite", "method",
            // Status & severity (coded in some resources)
            "clinicalStatus", "verificationStatus", "severity",
            // Encounter / general
            "serviceType", "reasonCode"
    );

    /**
     * Array fields whose elements are objects that themselves contain
     * {@code CodeableConcept} fields (e.g. {@code Observation.component[].code}).
     * Each element is recursively checked for {@link #CODEABLE_CONCEPT_FIELDS}.
     */
    private static final Set<String> CONCEPT_BEARING_ARRAYS = Set.of(
            "component"  // Observation.component[].code, component[].valueCodeableConcept
    );

    /** Sentinel value cached for identifiers that could not be resolved. */
    private static final String NOT_FOUND = "__NOT_FOUND__";

    private final RestClient fhirRestClient;
    private final RestClient openmrsRestClient;
    private final ObjectMapper objectMapper;
    private final ConceptResolver conceptResolver;

    /** Cache: original reference → resolved reference (or {@link #NOT_FOUND}). */
    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /** Cache: encounter-type search term (lowercase) → "uuid|name" (or {@link #NOT_FOUND}). */
    private final Map<String, String> encounterTypeCache = new ConcurrentHashMap<>();

    public FhirReferenceResolver(
            @Qualifier("fhirRestClient") RestClient fhirRestClient,
            @Qualifier("openmrsRestClient") RestClient openmrsRestClient,
            ObjectMapper objectMapper,
            ConceptResolver conceptResolver) {
        this.fhirRestClient = fhirRestClient;
        this.openmrsRestClient = openmrsRestClient;
        this.objectMapper = objectMapper;
        this.conceptResolver = conceptResolver;
    }

    // ────────────────────────── public API ──────────────────────────

    /**
     * Resolves non-UUID references and resource-specific codes in the given
     * FHIR resource JSON.
     *
     * @param resourceType the FHIR resource type (e.g. "Encounter")
     * @param resourceJson raw FHIR JSON from the source system
     * @return JSON with resolved references (or the original if resolution fails)
     */
    public String resolve(String resourceType, String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!(root instanceof ObjectNode mutableRoot)) {
                return resourceJson;
            }

            // 1. Walk the tree and resolve all "reference" fields
            resolveReferencesInNode(mutableRoot);

            // 2. Resolve concept codes (LOINC/SNOMED → OpenMRS UUID)
            resolveConceptCodes(mutableRoot);

            // 3. Resource-specific resolutions
            if ("Encounter".equals(resourceType)) {
                resolveEncounterType(mutableRoot);
            }

            return objectMapper.writeValueAsString(mutableRoot);

        } catch (Exception e) {
            log.warn("Reference resolution failed — forwarding as-is: {}", e.getMessage());
            return resourceJson;
        }
    }

    // ────────────────────── reference resolution ────────────────────

    /**
     * Recursively walks the JSON tree and resolves every
     * {@code "reference": "Type/non-uuid"} to {@code "Type/uuid"}.
     */
    private void resolveReferencesInNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            // If this object has a "reference" field, try to resolve it
            JsonNode refNode = obj.get("reference");
            if (refNode != null && refNode.isTextual()) {
                String original = refNode.asText();
                String resolved = resolveReference(original);
                if (!resolved.equals(original)) {
                    obj.put("reference", resolved);
                }
            }

            // Recurse into child fields (snapshot field names to avoid CME)
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                resolveReferencesInNode(obj.get(field));
            }

        } else if (node.isArray()) {
            for (JsonNode child : node) {
                resolveReferencesInNode(child);
            }
        }
    }

    /**
     * Resolves a single FHIR reference string.
     *
     * @return the resolved reference, or the original if it's already a UUID
     *         or cannot be resolved
     */
    String resolveReference(String reference) {
        // Check cache first
        String cached = referenceCache.get(reference);
        if (cached != null) {
            return NOT_FOUND.equals(cached) ? reference : cached;
        }

        Matcher m = REF_PATTERN.matcher(reference);
        if (!m.matches()) {
            return reference; // not a Type/id reference
        }

        String type = m.group(1);
        String id = m.group(2);

        // UUIDs don't need resolution
        if (UUID_PATTERN.matcher(id).matches()) {
            return reference;
        }

        // Search OpenMRS
        String uuid = searchResource(type, id);
        if (uuid != null) {
            String resolved = type + "/" + uuid;
            referenceCache.put(reference, resolved);
            log.info("Resolved reference: {} → {}", reference, resolved);
            return resolved;
        }

        // Cache the miss to avoid re-querying
        referenceCache.put(reference, NOT_FOUND);
        log.warn("Could not resolve reference: {} — forwarding as-is", reference);
        return reference;
    }

    /**
     * Searches OpenMRS FHIR for a resource by identifier, falling back to
     * name-based search for Location/Organization.
     */
    private String searchResource(String resourceType, String identifier) {
        // Primary strategy: search by identifier
        String uuid = searchByIdentifier(resourceType, identifier);
        if (uuid != null) {
            return uuid;
        }

        // Fallback for Location / Organization: search by name
        if ("Location".equals(resourceType) || "Organization".equals(resourceType)) {
            uuid = searchByName(resourceType, identifier);
        }

        return uuid;
    }

    private String searchByIdentifier(String resourceType, String identifier) {
        try {
            String response = fhirRestClient.get()
                    .uri("/{type}?identifier={id}&_count=1", resourceType, identifier)
                    .retrieve()
                    .body(String.class);
            return extractFirstId(response);
        } catch (Exception e) {
            log.debug("FHIR identifier search failed for {}/{}: {}",
                    resourceType, identifier, e.getMessage());
            return null;
        }
    }

    private String searchByName(String resourceType, String name) {
        try {
            String response = fhirRestClient.get()
                    .uri("/{type}?name={name}&_count=1", resourceType, name)
                    .retrieve()
                    .body(String.class);
            return extractFirstId(response);
        } catch (Exception e) {
            log.debug("FHIR name search failed for {}/{}: {}",
                    resourceType, name, e.getMessage());
            return null;
        }
    }

    /** Extracts the {@code id} of the first entry in a FHIR search Bundle. */
    private String extractFirstId(String bundleJson) {
        try {
            JsonNode bundle = objectMapper.readTree(bundleJson);
            int total = bundle.path("total").asInt(0);
            if (total > 0 && bundle.has("entry")) {
                return bundle.path("entry").get(0)
                        .path("resource").path("id").asText(null);
            }
        } catch (Exception e) {
            log.debug("Could not extract ID from search result: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────── concept code resolution ──────────────────

    /**
     * Resolves FHIR concept codes (LOINC, SNOMED, etc.) in known
     * {@code CodeableConcept} fields by adding an OpenMRS-native coding
     * entry with the resolved concept UUID.
     *
     * <p>This ensures the OpenMRS FHIR module can map the resource to the
     * correct internal concept without needing its own LOINC/SNOMED mappings
     * at the FHIR layer.
     *
     * <p>Handles both root-level fields (e.g. {@code Observation.code}) and
     * nested fields inside known arrays (e.g. {@code Observation.component[].code}).
     */
    private void resolveConceptCodes(ObjectNode root) {
        // 1. Resolve root-level CodeableConcept fields
        resolveCodeableConceptFields(root);

        // 2. Resolve nested CodeableConcept fields in known array structures
        //    e.g. Observation.component[].code, component[].valueCodeableConcept
        for (String arrayField : CONCEPT_BEARING_ARRAYS) {
            JsonNode array = root.get(arrayField);
            if (array != null && array.isArray()) {
                for (JsonNode element : array) {
                    if (element instanceof ObjectNode obj) {
                        resolveCodeableConceptFields(obj);
                    }
                }
            }
        }
    }

    /**
     * Resolves CODEABLE_CONCEPT_FIELDS within a given JSON object node.
     * Used for both root-level and nested (e.g. component[]) resolution.
     */
    private void resolveCodeableConceptFields(ObjectNode node) {
        for (String field : CODEABLE_CONCEPT_FIELDS) {
            JsonNode codeableConcept = node.get(field);
            if (codeableConcept == null || !codeableConcept.isObject()) {
                continue;
            }

            JsonNode codingArray = codeableConcept.get("coding");
            if (codingArray == null || !codingArray.isArray() || codingArray.isEmpty()) {
                continue;
            }

            // Skip if already has OpenMRS-native coding (UUID code without system,
            // or with a known OpenMRS system URI)
            if (hasOpenMrsCoding(codingArray)) {
                continue;
            }

            // Try to resolve via ConceptResolver
            try {
                String uuid = conceptResolver.resolve(codingArray);
                if (uuid != null) {
                    String existingCode = codingArray.get(0).path("code").asText("");
                    String existingSystem = codingArray.get(0).path("system").asText("");
                    // Add native coding if:
                    //  - resolved UUID differs from existing code (e.g. LOINC→CIEL lookup), OR
                    //  - existing coding has a system URI (OpenMRS needs system-less UUID coding;
                    //    e.g. http://ciel.org with a CIEL concept UUID still needs a bare coding)
                    boolean needsNativeCoding = !uuid.equals(existingCode)
                            || !existingSystem.isEmpty();

                    if (needsNativeCoding) {
                        // Add OpenMRS-native coding entry as the FIRST element.
                        // OpenMRS FHIR module expects concept UUID without a system URI;
                        // it does NOT recognise "http://openmrs.org/concepts" as a system.
                        ObjectNode openmrsCoding = objectMapper.createObjectNode();
                        openmrsCoding.put("code", uuid);
                        String display = codingArray.get(0).path("display").asText("");
                        if (!display.isEmpty()) {
                            openmrsCoding.put("display", display);
                        }
                        // Insert at position 0 so OpenMRS processes it first
                        ((ArrayNode) codingArray).insert(0, openmrsCoding);
                        log.info("Resolved concept code for field '{}': {} → {}",
                                field, codingArray.get(1).path("code").asText(), uuid);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not resolve concept code for field '{}': {}",
                        field, e.getMessage());
            }
        }
    }

    /**
     * Checks whether a coding array already contains an OpenMRS-native entry.
     * This includes:
     * <ul>
     *   <li>Coding with system {@code http://openmrs.org/concepts} or {@code http://openmrs.org}</li>
     *   <li>UUID-only coding (no system, code matches UUID pattern)</li>
     *   <li>CIEL-style 32-36 char hex codes (no hyphens)</li>
     * </ul>
     */
    private boolean hasOpenMrsCoding(JsonNode codingArray) {
        for (JsonNode coding : codingArray) {
            String system = coding.path("system").asText("");
            String code = coding.path("code").asText("");
            if (OPENMRS_CONCEPT_SYSTEM.equals(system) || "http://openmrs.org".equals(system)) {
                return true;
            }
            // UUID-only coding (no system) is the OpenMRS canonical format
            if (system.isEmpty() && UUID_PATTERN.matcher(code).matches()) {
                return true;
            }
            // CIEL-style 36-char hex UUIDs (no hyphens)
            if (system.isEmpty() && code.matches("^[0-9a-fA-F]{32,36}$")) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────── encounter-type resolution ──────────────────

    /**
     * Resolves {@code Encounter.type[].coding[]} display names to OpenMRS
     * encounter-type UUIDs.
     *
     * <p>Matching strategy (in order):
     * <ol>
     *   <li>Exact match (case-insensitive, underscores → spaces)</li>
     *   <li>Prefix match after stripping common suffixes ("Encounter", "Type")</li>
     *   <li>Contains match (either direction)</li>
     * </ol>
     */
    private void resolveEncounterType(ObjectNode root) {
        JsonNode typeArray = root.get("type");
        if (typeArray == null || !typeArray.isArray()) {
            return;
        }

        for (JsonNode typeElement : typeArray) {
            JsonNode codingArray = typeElement.get("coding");
            if (codingArray == null || !codingArray.isArray()) {
                continue;
            }

            for (JsonNode coding : codingArray) {
                if (!(coding instanceof ObjectNode codingObj)) {
                    continue;
                }

                String system = codingObj.path("system").asText("");
                String code = codingObj.path("code").asText("");
                String display = codingObj.path("display").asText("");

                // Already resolved — proper system + UUID code
                if (ENCOUNTER_TYPE_SYSTEM.equals(system)
                        && UUID_PATTERN.matcher(code).matches()) {
                    continue;
                }

                // Use display or code as search term
                String searchTerm = display.isEmpty() ? code : display;
                if (searchTerm.isEmpty()) {
                    continue;
                }

                String[] resolved = lookupEncounterType(searchTerm);
                if (resolved != null) {
                    codingObj.put("system", ENCOUNTER_TYPE_SYSTEM);
                    codingObj.put("code", resolved[0]);    // UUID
                    codingObj.put("display", resolved[1]); // canonical name
                    log.info("Resolved encounter type '{}' → {} ({})",
                            searchTerm, resolved[1], resolved[0]);
                } else {
                    log.warn("Could not resolve encounter type: '{}'", searchTerm);
                }
            }
        }
    }

    /**
     * Looks up an encounter type by name in OpenMRS.
     *
     * @param searchTerm display name from the source system
     * @return {@code [uuid, canonicalName]} or {@code null}
     */
    String[] lookupEncounterType(String searchTerm) {
        String key = searchTerm.toLowerCase();

        String cached = encounterTypeCache.get(key);
        if (cached != null) {
            if (NOT_FOUND.equals(cached)) {
                return null;
            }
            String[] parts = cached.split("\\|", 2);
            return parts.length == 2 ? parts : null;
        }

        try {
            String response = openmrsRestClient.get()
                    .uri("/encountertype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (!results.isArray() || results.isEmpty()) {
                encounterTypeCache.put(key, NOT_FOUND);
                return null;
            }

            // Normalize: underscores → spaces
            String normalized = searchTerm.replace('_', ' ').trim();

            // 1. Exact match (case-insensitive)
            for (JsonNode et : results) {
                String name = et.path("display").asText("");
                if (name.equalsIgnoreCase(searchTerm) || name.equalsIgnoreCase(normalized)) {
                    return cacheEncounterType(key, et);
                }
            }

            // 2. Prefix match after stripping common suffixes
            String stripped = normalized.replaceAll("(?i)\\s*(encounter|type)\\s*$", "").trim();
            if (!stripped.isEmpty() && !stripped.equalsIgnoreCase(normalized)) {
                for (JsonNode et : results) {
                    String name = et.path("display").asText("");
                    if (name.toLowerCase().startsWith(stripped.toLowerCase())) {
                        return cacheEncounterType(key, et);
                    }
                }
            }

            // 3. Contains match (either direction)
            String normLower = normalized.toLowerCase();
            for (JsonNode et : results) {
                String name = et.path("display").asText("").toLowerCase();
                if (name.contains(normLower) || normLower.contains(name)) {
                    return cacheEncounterType(key, et);
                }
            }

        } catch (Exception e) {
            log.debug("Encounter type lookup failed for '{}': {}", searchTerm, e.getMessage());
        }

        encounterTypeCache.put(key, NOT_FOUND);
        return null;
    }

    private String[] cacheEncounterType(String cacheKey, JsonNode encounterType) {
        String uuid = encounterType.path("uuid").asText();
        String name = encounterType.path("display").asText();
        encounterTypeCache.put(cacheKey, uuid + "|" + name);
        return new String[]{uuid, name};
    }

    // ────────────────────── for testing ──────────────────────────────

    /** Visible for testing — clears all caches. */
    void clearCaches() {
        referenceCache.clear();
        encounterTypeCache.clear();
    }
}
