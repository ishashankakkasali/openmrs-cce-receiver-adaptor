package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.ReferralProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VisitManager {

    private static final Logger log = LoggerFactory.getLogger(VisitManager.class);
    private static final DateTimeFormatter OPENMRS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static final String VISIT_LOCATION_TAG = "Visit Location";

    /** Visit location derived from the referral department; locations do not move, so cached. */
    private volatile String resolvedVisitLocationUuid;
    private volatile String resolvedIntakeVisitTypeUuid;

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final ReferralProperties referralProperties;
    private final PatientProvisioner patientProvisioner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisitManager(@Qualifier("openmrsRestClient") RestClient restClient,
                        DiscoveredConfig discoveredConfig,
                        ReferralProperties referralProperties,
                        PatientProvisioner patientProvisioner) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
        this.referralProperties = referralProperties;
        this.patientProvisioner = patientProvisioner;
    }

    public ObjectNode ensureVisit(ObjectNode encounterNode, Map<String, String> perRequestVisitCache) {
        // Skip if visit/partOf already set
        if (!encounterNode.path("partOf").isMissingNode() ||
                encounterNode.has("visit")) {
            return encounterNode;
        }

        // Skip if no type[] (visit-type encounter)
        if (encounterNode.path("type").isMissingNode() || !encounterNode.path("type").isArray()) {
            return encounterNode;
        }

        String patientUuid = extractPatientUuid(encounterNode);
        if (patientUuid == null) {
            log.warn("Cannot link Visit — no patient UUID found in Encounter");
            return encounterNode;
        }

        // Check per-request cache
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, encounterNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        if (visitUuid != null) {
            encounterNode.put("visit", visitUuid);
            log.debug("Linked Encounter to Visit: {}", visitUuid);
        }

        return encounterNode;
    }

    public String ensureEncounterForOrder(ObjectNode orderNode, Map<String, String> perRequestVisitCache) {
        // Check if encounter already set
        JsonNode encounterRef = orderNode.path("encounter");
        if (!encounterRef.isMissingNode()) {
            String ref = encounterRef.path("reference").asText("");
            if (!ref.isBlank()) return null; // already has encounter
        }

        String patientUuid = extractPatientUuid(orderNode);
        if (patientUuid == null) {
            log.warn("Cannot create encounter for order — no patient UUID");
            return null;
        }

        // Find or create visit
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, orderNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        // Always create a NEW encounter (never reuse)
        return createEncounter(patientUuid, visitUuid);
    }

    private String findOrCreateVisit(String patientUuid, ObjectNode resourceNode) {
        // Search for active visit
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=default", patientUuid)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                log.debug("Found active visit for patient {}: {}", patientUuid, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Failed to search visits for patient {}: {}", patientUuid, e.getMessage());
        }

        // Create new visit
        return createVisit(patientUuid, resourceNode);
    }

    private String createVisit(String patientUuid, ObjectNode resourceNode) {
        try {
            String startDatetime = extractStartDatetime(resourceNode);

            ObjectNode visitPayload = objectMapper.createObjectNode();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", discoveredConfig.getVisitTypeUuid());
            visitPayload.put("startDatetime", startDatetime);

            if (discoveredConfig.getLocationUuid() != null) {
                visitPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/visit")
                    .body(visitPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created new Visit for patient {}: {}", patientUuid, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create Visit for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    private String createEncounter(String patientUuid, String visitUuid) {
        try {
            String now = ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);

            // Get encounter type UUID for "Consultation"
            String encounterTypeUuid = discoveredConfig.getEncounterTypeCache().get("Consultation");

            ObjectNode encounterPayload = objectMapper.createObjectNode();
            encounterPayload.put("patient", patientUuid);
            encounterPayload.put("encounterDatetime", now);

            if (encounterTypeUuid != null) {
                encounterPayload.put("encounterType", encounterTypeUuid);
            }
            if (visitUuid != null) {
                encounterPayload.put("visit", visitUuid);
            }
            if (discoveredConfig.getLocationUuid() != null) {
                encounterPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/encounter")
                    .body(encounterPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created encounter for standalone order: {}", uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create encounter for order: {}", e.getMessage());
            return null;
        }
    }

    /** Resolves (or provisions) the OpenMRS patient UUID the resource's subject refers to. */
    public String resolvePatientUuid(ObjectNode node) {
        return extractPatientUuid(node);
    }

    private String extractPatientUuid(ObjectNode node) {
        // Phase 1: collect candidate identifiers and run lookup-only resolution.
        String refCandidate = null;
        String ref = node.path("subject").path("reference").asText("");
        if (ref.isBlank()) {
            ref = node.path("patient").path("reference").asText("");
        }
        if (!ref.isBlank()) {
            String[] parts = ref.split("/");
            String tail = parts[parts.length - 1];
            if (isUuid(tail)) return tail;
            refCandidate = tail;
            String resolved = patientProvisioner.lookup(refCandidate);
            if (resolved != null) return resolved;
        }

        String idCandidate = null;
        String idSystem = null;
        JsonNode identifier = node.path("subject").path("identifier");
        if (identifier.isMissingNode()) {
            identifier = node.path("patient").path("identifier");
        }
        if (!identifier.isMissingNode()) {
            String idValue = identifier.path("value").asText("");
            if (!idValue.isBlank()) {
                if (isUuid(idValue)) return idValue;
                idCandidate = idValue;
                idSystem = identifier.path("system").asText("");
                String resolved = patientProvisioner.lookup(idCandidate);
                if (resolved != null) return resolved;
            }
        }

        // Phase 2: every lookup failed — auto-provision a placeholder patient
        // using the best available candidate (prefer subject.identifier.value
        // since it carries an explicit system, fall back to the reference tail).
        String createValue = idCandidate != null ? idCandidate : refCandidate;
        if (createValue != null) {
            return patientProvisioner.lookupOrCreate(createValue, idSystem);
        }
        return null;
    }

    private boolean isUuid(String s) {
        return s != null && s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }


    private String extractStartDatetime(ObjectNode node) {
        // Try period.start from encounter
        String start = node.path("period").path("start").asText("");
        if (!start.isBlank()) return start;

        // Fallback to current time
        return ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);
    }

    // ------------------------------------------------------------------
    // Referral-specific flow (SPICE Referral In)
    // ------------------------------------------------------------------

    /**
     * Encounter creation for referrals: a "Referral In" encounter filed in an already-closed
     * intake visit, so the patient has no active visit until they arrive — the clerk starting the
     * visit is the check-in, not the delivery of the referral. If the patient already has an
     * active visit (an intake visit would overlap it), the encounter goes into that visit.
     * Returns the encounter UUID, or {@code null} if creation failed (in which case the caller
     * should fall back to the generic flow to avoid losing the order entirely).
     */
    public String ensureReferralEncounterForOrder(ObjectNode orderNode) {
        // NOTE: unlike the generic ensureEncounterForOrder, the referral flow
        // ALWAYS creates a fresh "Referral In" encounter — even when the inbound
        // ServiceRequest already carries an encounter reference.

        String patientUuid = extractPatientUuid(orderNode);
        if (patientUuid == null) {
            log.warn("Cannot create referral encounter — no patient UUID");
            return null;
        }

        String referralLocationUuid = referralProperties.getLocationUuid();
        String referralEncounterTypeUuid = referralProperties.getEncounterTypeUuid();

        if (isBlank(referralLocationUuid) || isBlank(referralEncounterTypeUuid)) {
            log.warn("Referral flow enabled but location/encounter-type UUID missing — "
                    + "falling back to generic encounter for patient {}", patientUuid);
            return null;
        }

        // In the past so neither the encounter nor the visit's stop (when + 1s) is in the future
        ZonedDateTime when = ZonedDateTime.now().withNano(0).minusSeconds(2);
        String visitUuid = createIntakeVisit(patientUuid, resolveVisitLocation(referralLocationUuid), when);
        if (visitUuid == null) {
            visitUuid = findActiveVisit(patientUuid);
        }
        return createReferralEncounter(patientUuid, visitUuid,
                referralEncounterTypeUuid, referralLocationUuid, when);
    }

    /**
     * Creates an already-closed visit of the intake visit type holding the referral. Returns
     * {@code null} if there is no intake visit type or creation fails (typically: it would overlap
     * the patient's active visit).
     */
    private String createIntakeVisit(String patientUuid, String locationUuid, ZonedDateTime when) {
        String intakeVisitTypeUuid = resolveIntakeVisitTypeUuid();
        if (isBlank(intakeVisitTypeUuid)) return null;
        try {
            ObjectNode visitPayload = objectMapper.createObjectNode();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", intakeVisitTypeUuid);
            visitPayload.put("location", locationUuid);
            visitPayload.put("startDatetime", when.format(OPENMRS_DATE_FORMAT));
            visitPayload.put("stopDatetime", when.plusSeconds(1).format(OPENMRS_DATE_FORMAT));

            String response = restClient.post()
                    .uri("/visit")
                    .body(visitPayload.toString())
                    .retrieve()
                    .body(String.class);
            String uuid = objectMapper.readTree(response).path("uuid").asText(null);
            log.info("Created closed Referral Intake visit for patient {}: {}", patientUuid, uuid);
            return uuid;
        } catch (Exception e) {
            log.info("No Referral Intake visit for patient {} ({}) — using the active visit if any",
                    patientUuid, e.getMessage());
            return null;
        }
    }

    /**
     * The facility a referral visit is held at: the configured visit location, else the nearest
     * location at or above the referral department tagged "Visit Location" — what O3 itself uses
     * when a visit is started from the department (the encounter stays at the department).
     */
    private String resolveVisitLocation(String departmentUuid) {
        if (!isBlank(referralProperties.getVisitLocationUuid())) {
            return referralProperties.getVisitLocationUuid();
        }
        String cached = resolvedVisitLocationUuid;
        if (cached != null) return cached;

        String current = departmentUuid;
        try {
            for (int depth = 0; current != null && depth < 10; depth++) {
                JsonNode location = objectMapper.readTree(restClient.get()
                        .uri("/location/{uuid}?v=custom:(uuid,tags:(display),parentLocation:(uuid))", current)
                        .retrieve()
                        .body(String.class));
                for (JsonNode tag : location.path("tags")) {
                    if (VISIT_LOCATION_TAG.equalsIgnoreCase(tag.path("display").asText())) {
                        resolvedVisitLocationUuid = current;
                        log.info("Referral visits are held at visit location {} (department {})", current, departmentUuid);
                        return current;
                    }
                }
                current = location.path("parentLocation").path("uuid").asText(null);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve the visit location of {}: {}", departmentUuid, e.getMessage());
            return departmentUuid;
        }
        log.warn("No \"{}\"-tagged location at or above {} — holding referral visits there", VISIT_LOCATION_TAG, departmentUuid);
        resolvedVisitLocationUuid = departmentUuid;
        return departmentUuid;
    }

    private String findActiveVisit(String patientUuid) {
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=custom:(uuid)", patientUuid)
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("uuid").asText(null);
            }
        } catch (Exception e) {
            log.warn("Failed to search active visits for patient {}: {}", patientUuid, e.getMessage());
        }
        return null;
    }

    /**
     * The configured intake visit type, else the visit type named {@code intake-visit-type-name}
     * (retired ones included), created when missing. Cached: visit types do not change.
     */
    private String resolveIntakeVisitTypeUuid() {
        if (!isBlank(referralProperties.getIntakeVisitTypeUuid())) {
            return referralProperties.getIntakeVisitTypeUuid();
        }
        String cached = resolvedIntakeVisitTypeUuid;
        if (cached != null) return cached;

        String name = referralProperties.getIntakeVisitTypeName();
        try {
            // includeAll: the type is retired so that it stays out of O3's start-visit picker
            JsonNode results = objectMapper.readTree(restClient.get()
                    .uri("/visittype?includeAll=true&v=custom:(uuid,name)")
                    .retrieve()
                    .body(String.class)).path("results");
            for (JsonNode visitType : results) {
                if (name.equalsIgnoreCase(visitType.path("name").asText(""))) {
                    resolvedIntakeVisitTypeUuid = visitType.path("uuid").asText(null);
                    log.info("Discovered '{}' visit type: {}", name, resolvedIntakeVisitTypeUuid);
                    return resolvedIntakeVisitTypeUuid;
                }
            }
            resolvedIntakeVisitTypeUuid = createIntakeVisitType(name);
            return resolvedIntakeVisitTypeUuid;
        } catch (Exception e) {
            log.warn("Failed to discover the '{}' visit type: {}", name, e.getMessage());
            return null;
        }
    }

    /** Creates the intake visit type, retired: clerks never start a visit of this type. */
    private String createIntakeVisitType(String name) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", name);
            payload.put("description", "Closed visit a referral from another facility is filed in when it arrives");
            String uuid = objectMapper.readTree(restClient.post()
                    .uri("/visittype")
                    .body(payload.toString())
                    .retrieve()
                    .body(String.class)).path("uuid").asText(null);
            if (uuid == null) return null;

            restClient.delete()
                    .uri("/visittype/{uuid}?reason={reason}", uuid,
                            "Receiver-only visit type; hidden from start-visit picker")
                    .retrieve()
                    .toBodilessEntity();
            log.info("Auto-created retired '{}' visit type: {}", name, uuid);
            return uuid;
        } catch (Exception e) {
            log.warn("Failed to auto-create the '{}' visit type: {}", name, e.getMessage());
            return null;
        }
    }

    private String createReferralEncounter(String patientUuid, String visitUuid,
                                           String referralEncounterTypeUuid,
                                           String referralLocationUuid,
                                           ZonedDateTime when) {
        try {
            ObjectNode encounterPayload = objectMapper.createObjectNode();
            encounterPayload.put("patient", patientUuid);
            encounterPayload.put("encounterDatetime", when.format(OPENMRS_DATE_FORMAT));
            encounterPayload.put("encounterType", referralEncounterTypeUuid);
            if (visitUuid != null) {
                encounterPayload.put("visit", visitUuid);
            }
            encounterPayload.put("location", referralLocationUuid);

            String response = restClient.post()
                    .uri("/encounter")
                    .body(encounterPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created Referral In encounter {} for patient {} on visit {}",
                    uuid, patientUuid, visitUuid != null ? visitUuid : "<none>");
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create Referral In encounter for patient {}: {}",
                    patientUuid, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
