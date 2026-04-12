package org.openphc.cce.receiver.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Enriches inbound FHIR Patient resources with OpenMRS-compatible identifiers
 * before they are sent to the OpenMRS FHIR endpoint.
 *
 * <p><b>Problem 1 – Missing preferred identifier:</b> CCE Core sends standard
 * FHIR Patient payloads with external identifiers (e.g., national IDs using
 * {@code system} URIs). However, OpenMRS requires every Patient to have a
 * <em>preferred</em> identifier of a specific type (typically "OpenMRS ID") that
 * passes the Luhn Mod 30 validator. Without it, OpenMRS rejects the payload with:
 * <pre>
 *   'Patient#null' failed to validate with reason: Select a preferred identifier
 * </pre>
 *
 * <p><b>Problem 2 – Source identifiers silently dropped:</b> OpenMRS maps FHIR
 * identifiers to {@code patient_identifier} rows using
 * {@code identifier.type.text} → {@code patient_identifier_type.name}. If an
 * inbound identifier only has a {@code system} (e.g. "NID") but no
 * {@code type.text}, OpenMRS silently drops it. The identifier is never persisted,
 * so subsequent resources that reference the patient by source ID (e.g.
 * {@code Patient/251119-0001-4106}) cannot be resolved.
 *
 * <p><b>Solution:</b> This enricher performs two enrichments:
 * <ol>
 *   <li><b>OpenMRS ID injection:</b> Generates a Luhn Mod 30 identifier and
 *       adds it as {@code use: "official"} if no OpenMRS ID exists yet.</li>
 *   <li><b>Source identifier promotion:</b> For every existing identifier that
 *       has a {@code system} but no {@code type.text}, derives the type text
 *       from the system URI (last path segment or full value) and adds the
 *       required OpenMRS location extension. This ensures OpenMRS persists
 *       all source identifiers (NID, UPI, etc.).</li>
 * </ol>
 *
 * <p>Non-Patient resources pass through unchanged.
 */
@Component
public class PatientIdentifierEnricher {

    private static final Logger log = LoggerFactory.getLogger(PatientIdentifierEnricher.class);

    /**
     * Character set used by the OpenMRS Luhn Mod 30 identifier validator.
     * Excludes characters that look alike: B, I, O, Q, S, Z.
     */
    private static final String VALID_CHARS = "0123456789ACDEFGHJKLMNPRTUVWXY";

    /** Atomic counter for generating unique sequential base identifiers. */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() % 100000000L);

    private final ObjectMapper objectMapper;
    private final DiscoveredConfig discoveredConfig;
    private final OpenMrsConfigDiscovery configDiscovery;

    @Autowired
    public PatientIdentifierEnricher(
            ObjectMapper objectMapper,
            DiscoveredConfig discoveredConfig,
            @Nullable OpenMrsConfigDiscovery configDiscovery) {
        this.objectMapper = objectMapper;
        this.discoveredConfig = discoveredConfig;
        this.configDiscovery = configDiscovery;
    }

    /**
     * Convenience constructor for unit tests that do not need on-demand
     * identifier type creation.
     */
    PatientIdentifierEnricher(ObjectMapper objectMapper, DiscoveredConfig discoveredConfig) {
        this(objectMapper, discoveredConfig, null);
    }

    /**
     * Enriches a FHIR resource JSON if it is a Patient lacking an OpenMRS identifier.
     * Non-Patient resources are returned unchanged.
     *
     * @param resourceType the FHIR resource type
     * @param resourceJson the raw FHIR JSON
     * @return enriched JSON (or original if no enrichment needed)
     */
    public String enrich(String resourceType, String resourceJson) {
        if (!"Patient".equals(resourceType)) {
            return resourceJson;
        }

        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!(root instanceof ObjectNode patientNode)) {
                return resourceJson;
            }

            JsonNode identifiers = patientNode.path("identifier");

            // Check if patient already has an OpenMRS-compatible preferred identifier
            if (hasOpenMrsIdentifier(identifiers)) {
                log.debug("Patient already has an OpenMRS-compatible identifier, skipping enrichment");
                return resourceJson;
            }

            // Generate a new OpenMRS ID via local Luhn Mod 30
            String generatedId = generateOpenMrsId();
            if (generatedId == null) {
                log.warn("Failed to generate OpenMRS ID. "
                        + "Patient will be sent without enrichment — OpenMRS may reject it.");
                return resourceJson;
            }

            // Note: UUID 'id' fields on sub-elements (name, address, telecom, identifier)
            // are handled globally by FhirElementIdEnricher in OpenMrsFhirClient.

            // Build the OpenMRS identifier FHIR node
            ObjectNode openmrsIdentifier = objectMapper.createObjectNode();
            openmrsIdentifier.put("id", UUID.randomUUID().toString());
            openmrsIdentifier.put("use", "official");

            ObjectNode typeNode = objectMapper.createObjectNode();
            typeNode.put("text", discoveredConfig.getIdentifierTypeName());

            // Add type.coding with identifier type UUID — O3 uses this for type mapping
            String primaryTypeUuid = discoveredConfig.getIdentifierTypeUuid();
            if (primaryTypeUuid != null && !primaryTypeUuid.isBlank()) {
                ArrayNode codingArray = objectMapper.createArrayNode();
                ObjectNode coding = objectMapper.createObjectNode();
                coding.put("code", primaryTypeUuid);
                codingArray.add(coding);
                typeNode.set("coding", codingArray);
            }

            openmrsIdentifier.set("type", typeNode);

            openmrsIdentifier.put("value", generatedId);

            // Add location extension if configured (uses auto-discovered or explicit value)
            String locationUuid = discoveredConfig.getLocationUuid();
            if (locationUuid != null) {
                ArrayNode extensions = objectMapper.createArrayNode();
                ObjectNode locationExt = objectMapper.createObjectNode();
                locationExt.put("url", "http://fhir.openmrs.org/ext/patient/identifier#location");

                ObjectNode valueRef = objectMapper.createObjectNode();
                valueRef.put("reference", "Location/" + locationUuid);
                valueRef.put("type", "Location");
                locationExt.set("valueReference", valueRef);

                extensions.add(locationExt);
                openmrsIdentifier.set("extension", extensions);
            }

            // Ensure identifiers is an array and add the new one
            ArrayNode identifierArray;
            if (identifiers.isArray()) {
                identifierArray = (ArrayNode) identifiers;
            } else {
                identifierArray = objectMapper.createArrayNode();
            }

            // Demote any existing "official" identifiers to "secondary"
            // so the new OpenMRS ID becomes the preferred one
            for (int i = 0; i < identifierArray.size(); i++) {
                JsonNode existing = identifierArray.get(i);
                if (existing instanceof ObjectNode existingNode
                        && "official".equals(existing.path("use").asText(null))) {
                    existingNode.put("use", "secondary");
                    log.debug("Demoted existing identifier to 'secondary': {}",
                            existing.path("value").asText());
                }
            }

            identifierArray.add(openmrsIdentifier);
            patientNode.set("identifier", identifierArray);

            // Enrich source identifiers (NID, UPI, etc.) with type.text and location extension
            enrichSourceIdentifiers(identifierArray);

            String enrichedJson = objectMapper.writeValueAsString(patientNode);
            log.info("Enriched Patient with OpenMRS ID: {}", generatedId);
            return enrichedJson;

        } catch (Exception e) {
            log.error("Failed to enrich Patient identifier: {}. Sending original payload.",
                    e.getMessage(), e);
            return resourceJson;
        }
    }

    /**
     * Enriches source identifiers (NID, UPI, etc.) so OpenMRS persists them.
     *
     * <p>For each identifier that has a {@code system} matching a configured
     * source identifier type but is missing {@code type.text}, this method:
     * <ol>
     *   <li>Sets {@code type.text} to the matching type name (e.g., "NID")</li>
     *   <li>Adds the OpenMRS location extension if missing</li>
     *   <li>Ensures {@code use} is set (defaults to "secondary")</li>
     * </ol>
     *
     * <p>The match is case-insensitive: a system URI like
     * {@code http://example.org/nid} matches a configured type "NID" because
     * the URI contains "nid".
     *
     * @param identifierArray the mutable identifier array to enrich in-place
     */
    private void enrichSourceIdentifiers(ArrayNode identifierArray) {
        List<String> sourceTypes = discoveredConfig.getSourceIdentifierTypes();
        String locationUuid = discoveredConfig.getLocationUuid();

        for (int i = 0; i < identifierArray.size(); i++) {
            JsonNode identifier = identifierArray.get(i);
            if (!(identifier instanceof ObjectNode idNode)) continue;

            // Skip identifiers that already have type.text set
            String existingTypeText = idNode.path("type").path("text").asText(null);
            if (existingTypeText != null && !existingTypeText.isBlank()) {
                continue;
            }

            // Try to match system URI against configured source types
            String system = idNode.path("system").asText(null);
            if (system == null || system.isBlank()) continue;

            String matchedType = matchSourceType(system, sourceTypes);

            // If no match found, auto-create the identifier type in OpenMRS
            if (matchedType == null && configDiscovery != null) {
                String derivedName = deriveTypeName(system);
                if (derivedName != null) {
                    String uuid = configDiscovery.ensureIdentifierTypeExists(derivedName);
                    if (uuid != null) {
                        // Re-fetch the updated source types (config was mutated)
                        sourceTypes = discoveredConfig.getSourceIdentifierTypes();
                        matchedType = derivedName;
                        log.info("On-demand identifier type '{}' created/found (uuid={}). "
                                + "No adaptor restart needed.", derivedName, uuid);
                    }
                }
            }

            if (matchedType == null) continue;

            // Set type.text so OpenMRS maps it to the patient_identifier_type
            ObjectNode typeNode = idNode.has("type")
                    ? (ObjectNode) idNode.get("type")
                    : objectMapper.createObjectNode();
            typeNode.put("text", matchedType);

            // Set type.coding with identifier type UUID — O3 requires this for type mapping
            java.util.Map<String, String> typeUuids = discoveredConfig.getIdentifierTypeUuids();
            String typeUuid = typeUuids != null ? typeUuids.get(matchedType) : null;
            if (typeUuid != null && !typeUuid.isBlank()) {
                ArrayNode codingArray = objectMapper.createArrayNode();
                ObjectNode coding = objectMapper.createObjectNode();
                coding.put("code", typeUuid);
                codingArray.add(coding);
                typeNode.set("coding", codingArray);
            }

            idNode.set("type", typeNode);

            // Remove system field — OpenMRS maps identifiers by type.coding, not system.
            // Non-URI system values like "NID" cause O3 to silently drop the identifier.
            idNode.remove("system");

            // Ensure use is set
            if (!idNode.has("use") || idNode.path("use").asText("").isBlank()) {
                idNode.put("use", "secondary");
            }

            // Add location extension if missing
            if (!hasLocationExtension(idNode) && locationUuid != null) {
                ArrayNode extensions = idNode.has("extension") && idNode.get("extension").isArray()
                        ? (ArrayNode) idNode.get("extension")
                        : objectMapper.createArrayNode();

                ObjectNode locationExt = objectMapper.createObjectNode();
                locationExt.put("url", "http://fhir.openmrs.org/ext/patient/identifier#location");

                ObjectNode valueRef = objectMapper.createObjectNode();
                valueRef.put("reference", "Location/" + locationUuid);
                valueRef.put("type", "Location");
                locationExt.set("valueReference", valueRef);

                extensions.add(locationExt);
                idNode.set("extension", extensions);
            }

            log.info("Enriched source identifier: system={}, type.text={}, type.coding={}",
                    system, matchedType, typeUuid);
        }
    }

    /**
     * Matches a system URI against configured source identifier type names.
     *
     * <p>Match strategy (case-insensitive):
     * <ol>
     *   <li>Last path segment of the URI equals type name (e.g., {@code http://example.org/NID} → "NID")</li>
     *   <li>URI contains the type name anywhere (e.g., {@code http://nid.gov.rw/ids} → "NID")</li>
     * </ol>
     *
     * @return matched type name, or null if no match
     */
    private String matchSourceType(String system, List<String> sourceTypes) {
        if (sourceTypes == null || sourceTypes.isEmpty()) {
            return null;
        }

        // Extract last path segment
        String lastSegment = system;
        int lastSlash = system.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < system.length() - 1) {
            lastSegment = system.substring(lastSlash + 1);
        }

        // First pass: exact match on last segment (case-insensitive)
        for (String type : sourceTypes) {
            if (type.equalsIgnoreCase(lastSegment)) {
                return type;
            }
        }

        // Second pass: contains match anywhere in URI
        String systemLower = system.toLowerCase();
        for (String type : sourceTypes) {
            if (systemLower.contains(type.toLowerCase())) {
                return type;
            }
        }

        return null;
    }

    /**
     * Derives an identifier type name from a FHIR {@code system} value.
     *
     * <p>RHIE sends bare system values like {@code "NID"} or {@code "UPI"}.
     * For URI-style systems like {@code http://example.org/nid}, extracts the
     * last path segment. Rejects overly long values to avoid creating
     * nonsensical types from arbitrary URIs.
     *
     * @param system the FHIR identifier system value
     * @return derived type name, or null if not derivable
     */
    private String deriveTypeName(String system) {
        String name = system;
        int lastSlash = system.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < system.length() - 1) {
            name = system.substring(lastSlash + 1);
        }
        // Reject very long or blank names
        if (name.isBlank() || name.length() > 50) {
            return null;
        }
        return name;
    }

    /**
     * Checks if an identifier node already has the OpenMRS location extension.
     */
    private boolean hasLocationExtension(JsonNode identifierNode) {
        JsonNode extensions = identifierNode.path("extension");
        if (!extensions.isArray()) return false;
        for (JsonNode ext : extensions) {
            if ("http://fhir.openmrs.org/ext/patient/identifier#location"
                    .equals(ext.path("url").asText(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the identifier array already contains an OpenMRS-compatible
     * preferred identifier (matched by type.text).
     */
    private boolean hasOpenMrsIdentifier(JsonNode identifiers) {
        if (!identifiers.isArray()) {
            return false;
        }

        String expectedType = discoveredConfig.getIdentifierTypeName();

        for (JsonNode identifier : identifiers) {
            String use = identifier.path("use").asText(null);
            String typeText = identifier.path("type").path("text").asText(null);

            if ("official".equals(use) && expectedType.equals(typeText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates a valid OpenMRS ID with Luhn Mod 30 check digit.
     *
     * <p>Uses a combination of timestamp + atomic sequence to ensure uniqueness,
     * encoded in the Luhn Mod 30 character set, then appends the check digit.
     *
     * @return generated identifier string that passes Luhn Mod 30 validation
     */
    private String generateOpenMrsId() {
        try {
            // Generate a unique base using sequence counter
            long seq = SEQUENCE.incrementAndGet();
            String base = encodeBase30(seq);

            // Compute Luhn Mod 30 check digit
            char checkDigit = computeLuhnMod30CheckDigit(base);
            String generatedId = base + checkDigit;

            log.info("Generated OpenMRS ID locally: {} (base={}, check={})", generatedId, base, checkDigit);
            return generatedId;

        } catch (Exception e) {
            log.error("Failed to generate OpenMRS ID: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Encodes a long value in the Luhn Mod 30 character set.
     */
    private String encodeBase30(long value) {
        int base = VALID_CHARS.length(); // 30
        StringBuilder sb = new StringBuilder();
        long v = Math.abs(value);

        do {
            sb.insert(0, VALID_CHARS.charAt((int) (v % base)));
            v /= base;
        } while (v > 0);

        // Ensure minimum length of 5 characters
        while (sb.length() < 5) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    /**
     * Computes the Luhn Mod 30 check digit for a given identifier string.
     *
     * <p>This implements the same algorithm used by OpenMRS
     * {@code LuhnMod30IdentifierValidator}. The character set is
     * {@code 0123456789ACDEFGHJKLMNPRTUVWXY} (30 chars).
     *
     * @param identifier the base identifier (without check digit)
     * @return the check character
     */
    private char computeLuhnMod30CheckDigit(String identifier) {
        int factor = 2;
        int sum = 0;
        int base = VALID_CHARS.length(); // 30

        // Process from right to left
        for (int i = identifier.length() - 1; i >= 0; i--) {
            int codePoint = VALID_CHARS.indexOf(identifier.charAt(i));
            if (codePoint == -1) {
                throw new IllegalArgumentException(
                        "Invalid character in identifier: " + identifier.charAt(i));
            }

            int addend = factor * codePoint;
            factor = (factor == 2) ? 1 : 2;

            addend = (addend / base) + (addend % base);
            sum += addend;
        }

        int remainder = sum % base;
        int checkCodePoint = (base - remainder) % base;
        return VALID_CHARS.charAt(checkCodePoint);
    }
}
