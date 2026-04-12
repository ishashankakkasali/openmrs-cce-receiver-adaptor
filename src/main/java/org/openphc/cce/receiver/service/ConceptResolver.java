package org.openphc.cce.receiver.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openphc.cce.receiver.exception.ResourceTransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolves FHIR CodeableConcept codes (LOINC, SNOMED, CIEL, etc.) to
 * OpenMRS concept UUIDs by querying the OpenMRS REST concept API.
 *
 * <p>When the incoming FHIR payload uses standard terminology codes
 * (e.g., LOINC {@code 85354-9}), those codes cannot be used directly
 * with the OpenMRS REST API — which expects internal concept UUIDs.
 * This resolver bridges that gap by:
 * <ol>
 *   <li>Checking if the code is already an OpenMRS UUID (36-char format)</li>
 *   <li>Searching the OpenMRS concept dictionary by source mapping</li>
 *   <li>Caching results to avoid repeated lookups</li>
 * </ol>
 *
 * <p>Maps known FHIR code system URIs to OpenMRS concept source names:
 * <ul>
 *   <li>{@code http://loinc.org} → {@code LOINC}</li>
 *   <li>{@code http://snomed.info/sct} → {@code SNOMED CT}</li>
 *   <li>{@code http://hl7.org/fhir/sid/icd-10} → {@code ICD-10-WHO}</li>
 *   <li>{@code https://openconceptlab.org/orgs/CIEL/sources/CIEL} → {@code CIEL}</li>
 * </ul>
 */
@Service
public class ConceptResolver {

    private static final Logger log = LoggerFactory.getLogger(ConceptResolver.class);

    /** Regex pattern for OpenMRS UUIDs (standard 36-char UUID format). */
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /** OpenMRS also uses 36-char hex-only UUIDs (no hyphens for some concept UUIDs). */
    private static final String OPENMRS_UUID_PATTERN = "^[0-9a-fA-F]{32,36}$";

    /**
     * Maps FHIR code system URIs to OpenMRS concept source names.
     * These must match the source names configured in the OpenMRS concept dictionary.
     */
    private static final Map<String, String> SYSTEM_TO_SOURCE = Map.ofEntries(
            Map.entry("http://loinc.org", "LOINC"),
            Map.entry("http://snomed.info/sct", "SNOMED CT"),
            Map.entry("http://hl7.org/fhir/sid/icd-10", "ICD-10-WHO"),
            Map.entry("http://hl7.org/fhir/sid/icd-10-cm", "ICD-10-CM"),
            Map.entry("https://icd.who.int", "ICD-11"),
            Map.entry("http://npc.rw", "NPC"),
            Map.entry("http://npc.rw/npc", "NPC"),
            Map.entry("http://www.ichi.org/", "ICHI"),
            Map.entry("http://www.nlm.nih.gov/research/umls/rxnorm", "RxNorm"),
            Map.entry("https://openconceptlab.org/orgs/CIEL/sources/CIEL", "CIEL"),
            Map.entry("http://ciel.org", "CIEL"),
            Map.entry("urn:oid:2.16.840.1.113883.3.7201", "CIEL"),
            Map.entry("http://openmrs.org/concepts", "OPENMRS")
    );

    /** Known OpenMRS concept system URIs — codes from these systems are used as-is. */
    private static final Set<String> OPENMRS_SYSTEMS = Set.of(
            "http://openmrs.org/concepts",
            "http://openmrs.org"
    );

    /** In-memory cache: "source:code" → OpenMRS concept UUID. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private final RestClient openmrsRestClient;
    private final ObjectMapper objectMapper;

    public ConceptResolver(
            @Qualifier("openmrsRestClient") RestClient openmrsRestClient,
            ObjectMapper objectMapper) {
        this.openmrsRestClient = openmrsRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves a FHIR {@code CodeableConcept} coding array to an OpenMRS concept UUID.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Look for a coding with an OpenMRS system URI — use code directly</li>
     *   <li>If any code looks like a UUID, use it directly</li>
     *   <li>For known external systems (LOINC, SNOMED, CIEL), query OpenMRS concept API</li>
     *   <li>Fall back to returning the first code as-is (best effort)</li>
     * </ol>
     *
     * @param codingNode the {@code coding} JSON array from a FHIR CodeableConcept
     * @return resolved OpenMRS concept UUID, or the original code if resolution fails
     */
    public String resolve(JsonNode codingNode) {
        if (codingNode == null || !codingNode.isArray() || codingNode.isEmpty()) {
            return null;
        }

        // Strategy 1: Look for an OpenMRS-native coding
        for (JsonNode coding : codingNode) {
            String system = coding.path("system").asText(null);
            String code = coding.path("code").asText(null);

            if (system != null && OPENMRS_SYSTEMS.contains(system) && code != null) {
                log.debug("Found OpenMRS-native coding: system={}, code={}", system, code);
                return code;
            }
        }

        // Strategy 2: Check if any code is already a UUID
        for (JsonNode coding : codingNode) {
            String code = coding.path("code").asText(null);
            if (code != null && isOpenMrsUuid(code)) {
                log.debug("Code appears to be an OpenMRS UUID: {}", code);
                return code;
            }
        }

        // Strategy 3: Query OpenMRS for each known external system
        for (JsonNode coding : codingNode) {
            String system = coding.path("system").asText(null);
            String code = coding.path("code").asText(null);

            if (system != null && code != null && SYSTEM_TO_SOURCE.containsKey(system)) {
                String source = SYSTEM_TO_SOURCE.get(system);
                Optional<String> resolved = lookupBySourceMapping(source, code);
                if (resolved.isPresent()) {
                    log.info("Resolved {}:{} → OpenMRS UUID {}", source, code, resolved.get());
                    return resolved.get();
                }
            }
        }

        // Strategy 4: Fail fast — unresolved codes will be rejected by OpenMRS REST API
        String rawCode = codingNode.get(0).path("code").asText("unknown");
        String rawSystem = codingNode.get(0).path("system").asText("unspecified");
        String display = codingNode.get(0).path("display").asText("");

        log.error("Cannot resolve concept to OpenMRS UUID: system={}, code={}, display={}. "
                + "Ensure the OpenMRS concept dictionary has a mapping for this code.",
                rawSystem, rawCode, display);

        throw new ResourceTransformException("Concept",
                String.format("Unresolvable concept: system=%s, code=%s, display=%s. "
                        + "No matching concept found in OpenMRS. Add a concept source mapping "
                        + "(e.g., LOINC, SNOMED CT, CIEL) in the OpenMRS concept dictionary, "
                        + "or include an OpenMRS-native coding "
                        + "(system=http://openmrs.org/concepts) in the FHIR payload.",
                        rawSystem, rawCode, display));
    }

    /**
     * Resolves a single code with a known system to an OpenMRS concept UUID.
     * Useful for fields like route, frequency, doseUnits where the system is implicit.
     *
     * @param system the FHIR code system URI (e.g., "http://snomed.info/sct")
     * @param code   the code value
     * @return resolved OpenMRS concept UUID, or the original code if resolution fails
     */
    public String resolve(String system, String code) {
        if (code == null) {
            return null;
        }

        // Already a UUID?
        if (isOpenMrsUuid(code)) {
            return code;
        }

        // OpenMRS-native system?
        if (system != null && OPENMRS_SYSTEMS.contains(system)) {
            return code;
        }

        // Lookup by source mapping
        if (system != null && SYSTEM_TO_SOURCE.containsKey(system)) {
            String source = SYSTEM_TO_SOURCE.get(system);
            return lookupBySourceMapping(source, code).orElse(code);
        }

        return code;
    }

    /**
     * Queries the OpenMRS REST API to find a concept by its source mapping.
     * Uses the endpoint: {@code GET /concept?source={source}&code={code}}.
     *
     * <p>Results are cached to avoid redundant HTTP calls for the same code.
     *
     * @param source OpenMRS concept source name (e.g., "LOINC", "SNOMED CT")
     * @param code   the external code (e.g., "85354-9")
     * @return the OpenMRS concept UUID if found
     */
    private Optional<String> lookupBySourceMapping(String source, String code) {
        String cacheKey = source + ":" + code;

        // Check cache first
        String cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for {}:{} → {}", source, code, cached);
            return Optional.of(cached);
        }

        try {
            String responseBody = openmrsRestClient.get()
                    .uri("/concept?source={source}&code={code}", source, code)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("results");

            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                if (uuid != null) {
                    cache.put(cacheKey, uuid);
                    log.info("Resolved concept {}:{} → {} via OpenMRS API", source, code, uuid);
                    return Optional.of(uuid);
                }
            }

            log.warn("No concept found in OpenMRS for {}:{}. "
                    + "Ensure the concept source mapping exists in the dictionary.", source, code);
            return Optional.empty();

        } catch (RestClientResponseException e) {
            log.error("OpenMRS concept lookup failed for {}:{} — HTTP {}: {}",
                    source, code, e.getStatusCode().value(), e.getResponseBodyAsString());
            return Optional.empty();
        } catch (Exception e) {
            log.error("OpenMRS concept lookup failed for {}:{} — {}", source, code, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks if a string looks like an OpenMRS UUID.
     * Accepts both standard UUIDs (with hyphens) and OpenMRS-style hex strings.
     */
    private boolean isOpenMrsUuid(String value) {
        return value != null && (value.matches(UUID_PATTERN) || value.matches(OPENMRS_UUID_PATTERN));
    }

    /**
     * Returns the current cache size (for diagnostics).
     */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * Clears the concept resolution cache.
     */
    public void clearCache() {
        cache.clear();
        log.info("Concept resolution cache cleared");
    }
}
