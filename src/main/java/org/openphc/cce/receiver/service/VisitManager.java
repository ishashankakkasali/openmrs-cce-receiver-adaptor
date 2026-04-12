package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Visit lifecycle for Encounter resources to ensure they appear
 * in the OpenMRS O3 Visits UI.
 *
 * <p><b>Problem:</b> In OpenMRS O3, encounters only appear under
 * "Visits → All encounters" when they are linked to a Visit via the
 * FHIR {@code partOf} reference. When the adaptor creates an Encounter
 * without a Visit association, it becomes invisible in the O3 Visits UI
 * even though the Encounter and its attached observations exist in the
 * database.
 *
 * <p><b>Solution:</b> Before an Encounter is POSTed to the FHIR endpoint,
 * this component:
 * <ol>
 *   <li>Checks if the Encounter already has a {@code partOf} reference — if
 *       so, leaves it unchanged</li>
 *   <li>Extracts the patient UUID from the Encounter's {@code subject.reference}</li>
 *   <li>Queries OpenMRS REST API for an active (open) Visit for the patient
 *       on the same date as the encounter</li>
 *   <li>If no matching Visit exists, creates a new "Facility Visit" via the
 *       REST API</li>
 *   <li>Sets {@code partOf.reference} to {@code Encounter/<visitUuid>} so
 *       the FHIR module links the Encounter to the Visit</li>
 * </ol>
 *
 * <p><b>OpenMRS data model context:</b> In OpenMRS, a Visit is a container
 * for Encounters. In the FHIR representation, both Visits and Encounters are
 * modeled as FHIR {@code Encounter} resources. A Visit-type Encounter has a
 * "Facility Visit" type and no {@code partOf}. A clinical Encounter has a
 * specific type (e.g., "Visit Note") and links to the Visit via {@code partOf}.
 *
 * <p><b>Thread safety:</b> Uses a short-lived per-patient cache to avoid
 * creating duplicate visits when a Bundle contains multiple encounters for
 * the same patient.
 */
@Component
public class VisitManager {

    private static final Logger log = LoggerFactory.getLogger(VisitManager.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DiscoveredConfig discoveredConfig;

    /**
     * Short-lived cache: patientUuid → visitUuid for the current processing batch.
     * Prevents duplicate visit creation when a Bundle has multiple encounters
     * for the same patient. Cleared after each inbound request completes.
     */
    private final ConcurrentHashMap<String, String> visitCache = new ConcurrentHashMap<>();

    public VisitManager(
            @Qualifier("openmrsRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            DiscoveredConfig discoveredConfig) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.discoveredConfig = discoveredConfig;
    }

    /**
     * Ensures the given Encounter JSON has a {@code partOf} reference
     * linking it to a Visit. If no Visit exists for the patient, one is
     * created automatically.
     *
     * <p>Only modifies the JSON if:
     * <ul>
     *   <li>The resource is an Encounter (caller should check before calling)</li>
     *   <li>The Encounter does not already have a {@code partOf} field</li>
     *   <li>The Encounter has a {@code subject.reference} with a patient UUID</li>
     *   <li>A valid visit type UUID has been discovered</li>
     * </ul>
     *
     * @param encounterJson the FHIR Encounter JSON to enrich
     * @return the modified JSON with {@code partOf} added, or the original
     *         JSON if no modification was needed or possible
     */
    public String ensureVisitLinked(String encounterJson) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(encounterJson);

            // Skip if already has partOf — caller explicitly set it
            if (root.has("partOf") && !root.get("partOf").isNull()) {
                log.debug("Encounter already has partOf — skipping Visit linkage");
                return encounterJson;
            }

            // Skip encounter-tagged Visits (they ARE the Visit, not clinical encounters)
            if (isVisitEncounter(root)) {
                log.debug("Resource is a Visit-type encounter — skipping Visit linkage");
                return encounterJson;
            }

            // Extract patient UUID from subject.reference
            String patientUuid = extractPatientUuid(root);
            if (patientUuid == null) {
                log.warn("Cannot link Visit: no patient UUID in Encounter subject reference");
                return encounterJson;
            }

            // Check if visit type has been discovered
            String visitTypeUuid = discoveredConfig.getVisitTypeUuid();
            if (visitTypeUuid == null || visitTypeUuid.isBlank()) {
                log.warn("Cannot link Visit: no visit type UUID discovered");
                return encounterJson;
            }

            // Extract encounter datetime for Visit start
            String encounterDatetime = extractEncounterDatetime(root);

            // Find or create a Visit for this patient
            String visitUuid = findOrCreateVisit(patientUuid, visitTypeUuid, encounterDatetime);
            if (visitUuid == null) {
                log.warn("Could not find or create Visit for patient {} — Encounter will be unlinked",
                        patientUuid);
                return encounterJson;
            }

            // Set partOf reference to the Visit
            ObjectNode partOf = objectMapper.createObjectNode();
            partOf.put("reference", "Encounter/" + visitUuid);
            partOf.put("type", "Encounter");
            root.set("partOf", partOf);

            log.info("Linked Encounter to Visit {} for patient {}", visitUuid, patientUuid);
            return objectMapper.writeValueAsString(root);

        } catch (Exception e) {
            log.warn("Failed to link Encounter to Visit: {} — proceeding without linkage",
                    e.getMessage());
            return encounterJson;
        }
    }

    /**
     * Clears the per-request visit cache. Should be called after each
     * inbound request (Bundle) is fully processed.
     */
    public void clearCache() {
        visitCache.clear();
    }

    /**
     * Determines whether the Encounter is actually a Visit (container)
     * rather than a clinical encounter. In OpenMRS FHIR, Visits typically
     * have no encounter type or have a visit-related type tag.
     */
    private boolean isVisitEncounter(ObjectNode root) {
        // If it has no "type" array → it's a Visit
        JsonNode typeArray = root.get("type");
        if (typeArray == null || !typeArray.isArray() || typeArray.isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * Extracts the patient UUID from the Encounter's {@code subject.reference}.
     * Handles both {@code Patient/uuid} and full URL formats.
     */
    private String extractPatientUuid(ObjectNode root) {
        String reference = root.path("subject").path("reference").asText("");
        if (reference.isBlank()) return null;

        // Handle "Patient/uuid" format
        if (reference.contains("/")) {
            String id = reference.substring(reference.lastIndexOf('/') + 1);
            if (isUuid(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * Extracts the encounter datetime from period.start or other date fields.
     * Falls back to current time if not available.
     */
    private String extractEncounterDatetime(ObjectNode root) {
        String periodStart = root.path("period").path("start").asText("");
        if (!periodStart.isBlank()) return periodStart;

        // No period — use current time
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Finds an active (open) Visit for the patient, or creates one if none exists.
     *
     * <p>Visit matching strategy:
     * <ol>
     *   <li>Check in-memory cache (for same-Bundle deduplication)</li>
     *   <li>Query OpenMRS REST API for active visits (no stopDatetime)</li>
     *   <li>If no active visit → create a new Facility Visit</li>
     * </ol>
     */
    private String findOrCreateVisit(String patientUuid, String visitTypeUuid, String encounterDatetime) {
        // 1. Check cache first (same-Bundle deduplication)
        String cached = visitCache.get(patientUuid);
        if (cached != null) {
            log.debug("Using cached Visit {} for patient {}", cached, patientUuid);
            return cached;
        }

        // 2. Query OpenMRS for active visits
        String visitUuid = findActiveVisit(patientUuid);
        if (visitUuid != null) {
            visitCache.put(patientUuid, visitUuid);
            return visitUuid;
        }

        // 3. Create a new Visit
        visitUuid = createVisit(patientUuid, visitTypeUuid, encounterDatetime);
        if (visitUuid != null) {
            visitCache.put(patientUuid, visitUuid);
        }
        return visitUuid;
    }

    /**
     * Queries OpenMRS REST API for an active (open) visit for the patient.
     * An active visit has no {@code stopDatetime}.
     *
     * @return Visit UUID if found, null otherwise
     */
    private String findActiveVisit(String patientUuid) {
        try {
            String response = restClient.get()
                    .uri("/visit?patient={patient}&v=default&includeInactive=false",
                            patientUuid)
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode visit : results) {
                    // Active visit = no stopDatetime
                    if (visit.path("stopDatetime").isNull() || visit.path("stopDatetime").isMissingNode()) {
                        String uuid = visit.path("uuid").asText(null);
                        String display = visit.path("display").asText("?");
                        log.info("Found active Visit for patient {}: {} ({})", patientUuid, uuid, display);
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query visits for patient {}: {}", patientUuid, e.getMessage());
        }

        return null;
    }

    /**
     * Creates a new Facility Visit for the patient via the OpenMRS REST API.
     *
     * @param patientUuid      the patient UUID
     * @param visitTypeUuid    the visit type UUID (e.g., Facility Visit)
     * @param encounterDatetime the encounter datetime for visit start
     * @return UUID of the created visit, or null on failure
     */
    private String createVisit(String patientUuid, String visitTypeUuid, String encounterDatetime) {
        try {
            // Build the visit start datetime in OpenMRS REST format
            String startDatetime = normalizeToRestDatetime(encounterDatetime);

            Map<String, Object> visitPayload = new java.util.LinkedHashMap<>();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", visitTypeUuid);
            visitPayload.put("startDatetime", startDatetime);

            // Add location if available
            String locationUuid = discoveredConfig.getLocationUuid();
            if (locationUuid != null && !locationUuid.isBlank()) {
                visitPayload.put("location", locationUuid);
            }

            String body = objectMapper.writeValueAsString(visitPayload);

            log.debug("Creating Visit for patient {}: {}", patientUuid, body);

            String response = restClient.post()
                    .uri("/visit")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(response);
            String uuid = result.path("uuid").asText(null);
            String display = result.path("display").asText("?");

            log.info("Created Visit for patient {}: {} ({})", patientUuid, uuid, display);
            return uuid;

        } catch (Exception e) {
            log.error("Failed to create Visit for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    /**
     * Normalizes an ISO-8601 datetime string to a format the OpenMRS REST API accepts.
     * OpenMRS REST expects: "yyyy-MM-dd'T'HH:mm:ss.SSSZ" (e.g., 2026-04-05T07:00:00.000+0000)
     */
    private String normalizeToRestDatetime(String datetime) {
        try {
            // Try parsing as Instant
            Instant instant = Instant.parse(datetime);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    .withZone(ZoneOffset.UTC)
                    .format(instant);
        } catch (Exception e) {
            // Try parsing as offset datetime
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(datetime);
                return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                        .withZone(ZoneOffset.UTC)
                        .format(odt.toInstant());
            } catch (Exception e2) {
                // Return as-is and let OpenMRS try to parse it
                log.debug("Could not normalize datetime '{}', using as-is", datetime);
                return datetime;
            }
        }
    }

    private static boolean isUuid(String s) {
        return s != null && s.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }
}
