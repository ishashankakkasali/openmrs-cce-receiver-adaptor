package org.openphc.cce.receiver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Discovers OpenMRS-specific configuration at startup by querying the OpenMRS
 * REST API, eliminating the need to hardcode instance-specific UUIDs.
 *
 * <p><b>Problem:</b> The receiver adaptor needs several OpenMRS-instance-specific
 * UUIDs (identifier type, idgen source, default location) that differ between
 * local, staging, and production environments. Hardcoding them in
 * {@code application.yml} makes deployment error-prone.
 *
 * <p><b>Solution:</b> At startup, this component queries OpenMRS to discover
 * these values automatically. It then publishes them via {@link DiscoveredConfig}
 * which other components (e.g., {@code PatientIdentifierEnricher}) can use.
 *
 * <p>The discovery is <b>best-effort</b>: if explicit values are provided in
 * {@code application.yml}, those take precedence. Discovery only fills in
 * missing/empty values.
 *
 * <h3>What gets discovered:</h3>
 * <table>
 *   <tr><th>Config</th><th>API Endpoint</th><th>How</th></tr>
 *   <tr><td>Location UUID</td><td>GET /location?tag=Login+Location</td>
 *       <td>First location tagged as "Login Location" (default visit location)</td></tr>
 *   <tr><td>Identifier Type UUID</td><td>GET /patientidentifiertype</td>
 *       <td>Matched by name (e.g., "OpenMRS ID")</td></tr>
 *   <tr><td>Idgen Source UUID</td><td>GET /idgen/identifiersource</td>
 *       <td>First source linked to the discovered identifier type</td></tr>
 *   <tr><td>Source Identifier Types</td><td>GET /patientidentifiertype</td>
 *       <td>All registered types except the primary "OpenMRS ID" — these are
 *       the types the admin has configured in OpenMRS for source system
 *       identifiers (NID, UPI, etc.)</td></tr>
 * </table>
 */
@Component
public class OpenMrsConfigDiscovery {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsConfigDiscovery.class);

    private final RestClient restClient;
    private final OpenMrsProperties properties;
    private final ObjectMapper objectMapper;
    private final DiscoveredConfig discoveredConfig;

    public OpenMrsConfigDiscovery(
            @Qualifier("openmrsRestClient") RestClient restClient,
            OpenMrsProperties properties,
            ObjectMapper objectMapper,
            DiscoveredConfig discoveredConfig) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.discoveredConfig = discoveredConfig;
    }

    /**
     * Runs after the application is fully started. Discovers missing config
     * values from the target OpenMRS instance.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverOnStartup() {
        log.info("Starting OpenMRS configuration discovery...");

        try {
            // 1. Location UUID — needed for patient identifier extension
            String locationUuid = resolveLocationUuid();
            discoveredConfig.setLocationUuid(locationUuid);

            // 2. Identifier Type Name — validate it exists in OpenMRS
            String identifierTypeName = properties.identifier() != null
                    ? properties.identifier().typeName() : "OpenMRS ID";
            discoveredConfig.setIdentifierTypeName(identifierTypeName);

            // 3. Idgen Source UUID — needed if we switch to server-side ID generation
            String idgenSourceUuid = resolveIdgenSourceUuid(identifierTypeName);
            discoveredConfig.setIdgenSourceUuid(idgenSourceUuid);

            // 4. Source identifier types — discover from OpenMRS (admin registers them)
            List<String> sourceTypes = discoverSourceIdentifierTypes(identifierTypeName);
            discoveredConfig.setSourceIdentifierTypes(sourceTypes);

            // 5. Visit type — needed for auto-creating Visits for Encounters
            String visitTypeUuid = discoverVisitTypeUuid();
            discoveredConfig.setVisitTypeUuid(visitTypeUuid);

            log.info("OpenMRS config discovery complete: locationUuid={}, identifierType={} (uuid={}), idgenSource={}, visitType={}, sourceTypes={}",
                    discoveredConfig.getLocationUuid(),
                    discoveredConfig.getIdentifierTypeName(),
                    discoveredConfig.getIdentifierTypeUuid(),
                    discoveredConfig.getIdgenSourceUuid(),
                    discoveredConfig.getVisitTypeUuid(),
                    discoveredConfig.getSourceIdentifierTypes());

        } catch (Exception e) {
            log.warn("OpenMRS config discovery failed: {}. Falling back to application.yml values.",
                    e.getMessage());
            // Fallback: copy static config values
            if (properties.identifier() != null) {
                discoveredConfig.setLocationUuid(
                        firstNonBlank(discoveredConfig.getLocationUuid(),
                                properties.identifier().locationUuid()));
                discoveredConfig.setIdgenSourceUuid(
                        firstNonBlank(discoveredConfig.getIdgenSourceUuid(),
                                properties.identifier().idgenSourceUuid()));
                discoveredConfig.setIdentifierTypeName(
                        firstNonBlank(discoveredConfig.getIdentifierTypeName(),
                                properties.identifier().typeName()));
            }
        }
    }

    /**
     * Names to search for (case-insensitive) when auto-discovering the location
     * to stamp on programmatically-created patient identifiers. Ordered by
     * preference — the first match wins.
     *
     * <p>Rationale: the identifier-location in OpenMRS means
     * "where was this ID issued", so an administrative/registration location
     * is more appropriate than a clinical ward.
     */
    private static final String[] PREFERRED_LOCATION_NAMES = {
            "Registration Desk",
            "Registration",
            "Outpatient Clinic",
            "Outpatient"
    };

    /**
     * Resolves the location UUID to use for patient identifiers.
     *
     * <p><b>Why a default and not from the payload?</b>  
     * Spice's FHIR Bundle does include a {@code Location} resource, but it
     * represents a <em>GPS point</em> (where the screening happened), not an
     * OpenMRS administrative location. The OpenMRS identifier-location extension
     * ({@code http://fhir.openmrs.org/ext/patient/identifier#location}) maps to
     * {@code patient_identifier.location_id} — an OpenMRS concept meaning
     * "where was this patient ID issued". Since the adaptor creates IDs
     * programmatically, a default administrative location is appropriate.
     *
     * <p>Priority:
     * <ol>
     *   <li>Explicit value from {@code application.yml} (if non-blank)</li>
     *   <li>A "Login Location"-tagged location matching preferred names
     *       (Registration Desk, Outpatient Clinic, etc.)</li>
     *   <li>Any "Login Location"-tagged location</li>
     *   <li>First location returned by OpenMRS</li>
     * </ol>
     */
    private String resolveLocationUuid() {
        // If explicitly configured, use that
        if (properties.identifier() != null && isNotBlank(properties.identifier().locationUuid())) {
            log.debug("Using explicitly configured location UUID: {}", properties.identifier().locationUuid());
            return properties.identifier().locationUuid();
        }

        // Query all "Login Location"-tagged locations
        try {
            String response = restClient.get()
                    .uri("/location?tag=Login+Location&v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                // First pass: look for a preferred name (Registration Desk, Outpatient, etc.)
                for (String preferred : PREFERRED_LOCATION_NAMES) {
                    for (JsonNode loc : results) {
                        String name = loc.path("display").asText("");
                        if (name.toLowerCase().contains(preferred.toLowerCase())) {
                            String uuid = loc.path("uuid").asText(null);
                            log.info("Discovered preferred identifier location: {} ({})", name, uuid);
                            return uuid;
                        }
                    }
                }

                // Second pass: just take the first Login Location
                String uuid = results.get(0).path("uuid").asText(null);
                String name = results.get(0).path("display").asText("unknown");
                log.info("Discovered Login Location (no preferred match): {} ({})", name, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.debug("Could not query Login Locations: {}", e.getMessage());
        }

        // Fallback: first location
        try {
            String response = restClient.get()
                    .uri("/location?v=default&limit=1")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                String name = results.get(0).path("display").asText("unknown");
                log.info("Discovered fallback location: {} ({})", name, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Could not discover any location: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Discovers source identifier types from OpenMRS.
     *
     * <p>Queries all registered {@code patientidentifiertype} entries and returns
     * the names of every non-retired type whose name does NOT match the primary
     * identifier type (typically "OpenMRS ID"). These are the types the admin
     * has registered in OpenMRS for source system identifiers (NID, UPI, etc.).
     *
     * <p>The adaptor uses these names to match incoming FHIR identifiers by
     * system URI and set {@code type.text} so OpenMRS persists them.
     *
     * @param primaryTypeName the primary identifier type name to exclude (e.g., "OpenMRS ID")
     * @return list of source identifier type names discovered from OpenMRS
     */
    private List<String> discoverSourceIdentifierTypes(String primaryTypeName) {
        List<String> sourceTypes = new ArrayList<>();
        Map<String, String> typeUuids = new HashMap<>();

        try {
            String response = restClient.get()
                    .uri("/patientidentifiertype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode type : results) {
                    String name = type.path("name").asText("");
                    String uuid = type.path("uuid").asText("");
                    boolean retired = type.path("retired").asBoolean(false);

                    if (retired || name.isBlank()) continue;

                    // Store primary identifier type UUID
                    if (name.equalsIgnoreCase(primaryTypeName)) {
                        discoveredConfig.setIdentifierTypeUuid(uuid);
                        log.info("Discovered primary identifier type UUID: '{}' (uuid={})",
                                name, uuid);
                        continue;
                    }

                    // Non-primary type — source identifier
                    sourceTypes.add(name);
                    typeUuids.put(name, uuid);
                    log.info("Discovered source identifier type: '{}' (uuid={})",
                            name, uuid);
                }
            }
        } catch (Exception e) {
            log.warn("Could not discover source identifier types: {}", e.getMessage());
        }

        discoveredConfig.setIdentifierTypeUuids(typeUuids);

        if (sourceTypes.isEmpty()) {
            log.info("No source identifier types found in OpenMRS. "
                    + "Source identifiers (NID, UPI, etc.) will not be enriched. "
                    + "Admin can register them via OpenMRS Admin UI or REST API.");
        }

        return sourceTypes;
    }

    /**
     * Ensures that a patient identifier type with the given name exists in
     * OpenMRS. If it does not exist, it is created automatically via the REST
     * API and registered in {@link DiscoveredConfig}.
     *
     * <p>This enables a <b>zero-restart workflow</b>: when the adaptor
     * receives a Patient with an identifier whose {@code system} maps to a
     * type name that is not yet registered in OpenMRS, the type is created
     * on-the-fly and immediately available for enrichment.
     *
     * <p><b>Thread safety:</b> This method is {@code synchronized} to prevent
     * concurrent creation of the same type.
     *
     * @param typeName the identifier type name (e.g., "NID", "UPI")
     * @return the UUID of the (existing or newly created) identifier type,
     *         or {@code null} if creation failed
     */
    public synchronized String ensureIdentifierTypeExists(String typeName) {
        // 1. Already in discovered config?
        java.util.Map<String, String> typeUuids = discoveredConfig.getIdentifierTypeUuids();
        if (typeUuids.containsKey(typeName)) {
            return typeUuids.get(typeName);
        }

        // Primary type?
        if (typeName.equalsIgnoreCase(discoveredConfig.getIdentifierTypeName())) {
            return discoveredConfig.getIdentifierTypeUuid();
        }

        // 2. Re-query OpenMRS — maybe it was created externally since startup
        String uuid = findIdentifierTypeByName(typeName);
        if (uuid != null) {
            discoveredConfig.addSourceIdentifierType(typeName, uuid);
            log.info("Found existing identifier type '{}' (uuid={}) on re-query", typeName, uuid);
            return uuid;
        }

        // 3. Create it in OpenMRS
        uuid = createIdentifierType(typeName);
        if (uuid != null) {
            discoveredConfig.addSourceIdentifierType(typeName, uuid);
        }
        return uuid;
    }

    /**
     * Queries OpenMRS for an identifier type by exact name.
     */
    private String findIdentifierTypeByName(String typeName) {
        try {
            String response = restClient.get()
                    .uri("/patientidentifiertype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode type : results) {
                    if (typeName.equalsIgnoreCase(type.path("name").asText(""))) {
                        return type.path("uuid").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not query identifier types for '{}': {}", typeName, e.getMessage());
        }
        return null;
    }

    /**
     * Creates a new patient identifier type in OpenMRS via the REST API.
     */
    private String createIdentifierType(String typeName) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", typeName,
                    "description", "Auto-created by CCE Receiver Adaptor for " + typeName + " identifiers",
                    "required", false,
                    "uniquenessBehavior", "UNIQUE"
            ));

            String response = restClient.post()
                    .uri("/patientidentifiertype")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode result = objectMapper.readTree(response);
            String uuid = result.path("uuid").asText(null);

            log.info("Auto-created identifier type '{}' in OpenMRS (uuid={})", typeName, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to auto-create identifier type '{}': {}", typeName, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the idgen identifier source UUID for the given identifier type.
     *
     * <p>Priority:
     * <ol>
     *   <li>Explicit value from {@code application.yml} (if non-blank)</li>
     *   <li>First idgen source whose identifierType matches the configured type name</li>
     * </ol>
     */
    /**
     * Discovers the default visit type UUID from OpenMRS.
     *
     * <p>Priority:
     * <ol>
     *   <li>"Facility Visit" (most common in O3)</li>
     *   <li>First available visit type</li>
     * </ol>
     */
    private String discoverVisitTypeUuid() {
        try {
            String response = restClient.get()
                    .uri("/visittype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                // Prefer "Facility Visit"
                for (JsonNode vt : results) {
                    String name = vt.path("display").asText("");
                    if ("Facility Visit".equalsIgnoreCase(name)) {
                        String uuid = vt.path("uuid").asText(null);
                        log.info("Discovered visit type: {} ({})", name, uuid);
                        return uuid;
                    }
                }
                // Fallback: first available
                String uuid = results.get(0).path("uuid").asText(null);
                String name = results.get(0).path("display").asText("unknown");
                log.info("Discovered fallback visit type: {} ({})", name, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Could not discover visit type: {}", e.getMessage());
        }
        return null;
    }

    private String resolveIdgenSourceUuid(String identifierTypeName) {
        // If explicitly configured, use that
        if (properties.identifier() != null && isNotBlank(properties.identifier().idgenSourceUuid())) {
            log.debug("Using explicitly configured idgen source UUID: {}",
                    properties.identifier().idgenSourceUuid());
            return properties.identifier().idgenSourceUuid();
        }

        try {
            String response = restClient.get()
                    .uri("/idgen/identifiersource?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode source : results) {
                    String sourceType = source.path("identifierType").path("display").asText("");
                    if (identifierTypeName.equalsIgnoreCase(sourceType)) {
                        String uuid = source.path("uuid").asText(null);
                        String name = source.path("name").asText("unknown");
                        log.info("Discovered idgen source: {} ({}) for type '{}'",
                                name, uuid, identifierTypeName);
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not discover idgen source: {}", e.getMessage());
        }

        return null;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (isNotBlank(v)) return v;
        }
        return null;
    }
}
