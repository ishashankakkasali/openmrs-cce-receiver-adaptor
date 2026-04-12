package org.openphc.cce.receiver.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Auto-populates missing required fields with sensible defaults before
 * forwarding FHIR resources to OpenMRS.
 *
 * <h3>Problem</h3>
 * Source systems often omit fields that FHIR considers optional but OpenMRS
 * treats as mandatory. For example, OpenMRS requires {@code obsDatetime}
 * (mapped from {@code effectiveDateTime}) for every Observation, but
 * source systems may not include it.
 *
 * <h3>Solution</h3>
 * For each resource type, this enricher checks a pre-configured list of
 * required fields and inserts a default value when the field is missing
 * or null. Defaults are never applied when the source system already
 * provided a value.
 *
 * <h3>Supported defaults</h3>
 * <table>
 *   <tr><th>Resource</th><th>Field</th><th>Default</th></tr>
 *   <tr><td>Observation</td><td>effectiveDateTime</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Observation</td><td>status</td><td>"final"</td></tr>
 *   <tr><td>Encounter</td><td>period.start</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Encounter</td><td>status</td><td>"unknown"</td></tr>
 *   <tr><td>Condition</td><td>onsetDateTime</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Condition</td><td>recordedDate</td><td>current UTC timestamp</td></tr>
 *   <tr><td>AllergyIntolerance</td><td>recordedDate</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Immunization</td><td>occurrenceDateTime</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Immunization</td><td>status</td><td>"completed"</td></tr>
 *   <tr><td>DiagnosticReport</td><td>effectiveDateTime</td><td>current UTC timestamp</td></tr>
 *   <tr><td>DiagnosticReport</td><td>issued</td><td>current UTC timestamp</td></tr>
 *   <tr><td>DiagnosticReport</td><td>status</td><td>"final"</td></tr>
 *   <tr><td>ServiceRequest</td><td>authoredOn</td><td>current UTC timestamp</td></tr>
 *   <tr><td>ServiceRequest</td><td>status</td><td>"active"</td></tr>
 *   <tr><td>ServiceRequest</td><td>intent</td><td>"order"</td></tr>
 *   <tr><td>MedicationRequest</td><td>authoredOn</td><td>current UTC timestamp</td></tr>
 *   <tr><td>MedicationRequest</td><td>status</td><td>"active"</td></tr>
 *   <tr><td>MedicationRequest</td><td>intent</td><td>"order"</td></tr>
 *   <tr><td>Task</td><td>authoredOn</td><td>current UTC timestamp</td></tr>
 *   <tr><td>Task</td><td>status</td><td>"requested"</td></tr>
 *   <tr><td>Task</td><td>intent</td><td>"order"</td></tr>
 * </table>
 *
 * @see OpenMrsFhirClient
 * @see OpenMrsRestClient
 */
@Component
public class RequiredFieldEnricher {

    private static final Logger log = LoggerFactory.getLogger(RequiredFieldEnricher.class);

    /**
     * ISO-8601 formatter for UTC timestamps (e.g. {@code 2026-04-02T10:30:00.000Z}).
     */
    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    /**
     * Resource type → ordered list of field defaults.
     * Only fields that are missing or null will be populated.
     */
    private static final Map<String, List<FieldDefault>> RESOURCE_DEFAULTS = Map.ofEntries(

            Map.entry("Observation", List.of(
                    FieldDefault.dateTime("effectiveDateTime"),
                    FieldDefault.value("status", "final")
            )),

            Map.entry("Encounter", List.of(
                    FieldDefault.periodStart("period"),
                    FieldDefault.value("status", "unknown")
            )),

            Map.entry("Condition", List.of(
                    FieldDefault.dateTime("onsetDateTime"),
                    FieldDefault.dateTime("recordedDate")
            )),

            Map.entry("AllergyIntolerance", List.of(
                    FieldDefault.dateTime("recordedDate")
            )),

            Map.entry("Immunization", List.of(
                    FieldDefault.dateTime("occurrenceDateTime"),
                    FieldDefault.value("status", "completed")
            )),

            Map.entry("DiagnosticReport", List.of(
                    FieldDefault.dateTime("effectiveDateTime"),
                    FieldDefault.dateTime("issued"),
                    FieldDefault.value("status", "final")
            )),

            Map.entry("ServiceRequest", List.of(
                    FieldDefault.dateTime("authoredOn"),
                    FieldDefault.value("status", "active"),
                    FieldDefault.value("intent", "order")
            )),

            Map.entry("MedicationRequest", List.of(
                    FieldDefault.dateTime("authoredOn"),
                    FieldDefault.value("status", "active"),
                    FieldDefault.value("intent", "order")
            )),

            Map.entry("Task", List.of(
                    FieldDefault.dateTime("authoredOn"),
                    FieldDefault.value("status", "requested"),
                    FieldDefault.value("intent", "order")
            )),

            Map.entry("Procedure", List.of(
                    FieldDefault.dateTime("performedDateTime"),
                    FieldDefault.value("status", "completed")
            )),

            Map.entry("MedicationDispense", List.of(
                    FieldDefault.dateTime("whenHandedOver"),
                    FieldDefault.value("status", "completed")
            )),

            Map.entry("MedicationAdministration", List.of(
                    FieldDefault.dateTime("effectiveDateTime"),
                    FieldDefault.value("status", "completed")
            )),

            Map.entry("Consent", List.of(
                    FieldDefault.dateTime("dateTime"),
                    FieldDefault.value("status", "active")
            ))
    );

    private final ObjectMapper objectMapper;

    public RequiredFieldEnricher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches a FHIR resource by inserting sensible defaults for missing
     * required fields that OpenMRS expects.
     *
     * @param resourceType FHIR resource type (e.g. "Observation")
     * @param resourceJson raw FHIR JSON from the source system
     * @return enriched JSON with defaults applied, or the original if no
     *         defaults are needed or an error occurs
     */
    public String enrich(String resourceType, String resourceJson) {
        List<FieldDefault> defaults = RESOURCE_DEFAULTS.get(resourceType);
        if (defaults == null || defaults.isEmpty()) {
            return resourceJson;
        }

        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!(root instanceof ObjectNode mutableRoot)) {
                return resourceJson;
            }

            boolean modified = false;
            String now = formatNow();

            for (FieldDefault fd : defaults) {
                boolean applied = switch (fd.type()) {
                    case DATETIME -> applyDateTimeDefault(mutableRoot, fd.fieldName(), now, resourceType);
                    case PERIOD_START -> applyPeriodStartDefault(mutableRoot, fd.fieldName(), now, resourceType);
                    case VALUE -> applyValueDefault(mutableRoot, fd.fieldName(), fd.defaultValue(), resourceType);
                };
                modified |= applied;
            }

            return modified ? objectMapper.writeValueAsString(mutableRoot) : resourceJson;

        } catch (Exception e) {
            log.warn("Failed to enrich required fields for {}: {}. Sending original payload.",
                    resourceType, e.getMessage());
            return resourceJson;
        }
    }

    // ───────────────── default application methods ──────────────────

    /**
     * Sets a datetime field to the current timestamp if missing.
     */
    private boolean applyDateTimeDefault(ObjectNode root, String fieldName, String now, String resourceType) {
        if (isMissing(root, fieldName)) {
            root.put(fieldName, now);
            log.info("Added default {}='{}' to {} resource", fieldName, now, resourceType);
            return true;
        }
        return false;
    }

    /**
     * Sets a period.start field to the current timestamp if the period or its
     * start field is missing.
     */
    private boolean applyPeriodStartDefault(ObjectNode root, String fieldName, String now, String resourceType) {
        JsonNode period = root.get(fieldName);
        if (period == null || period.isNull()) {
            // Entire period missing — create it with start
            ObjectNode periodObj = objectMapper.createObjectNode();
            periodObj.put("start", now);
            root.set(fieldName, periodObj);
            log.info("Added default {}.start='{}' to {} resource", fieldName, now, resourceType);
            return true;
        } else if (period.isObject()) {
            // Period exists but start missing
            if (isMissing((ObjectNode) period, "start")) {
                ((ObjectNode) period).put("start", now);
                log.info("Added default {}.start='{}' to {} resource", fieldName, now, resourceType);
                return true;
            }
        }
        return false;
    }

    /**
     * Sets a string field to a default value if missing.
     */
    private boolean applyValueDefault(ObjectNode root, String fieldName, String value, String resourceType) {
        if (isMissing(root, fieldName)) {
            root.put(fieldName, value);
            log.debug("Added default {}='{}' to {} resource", fieldName, value, resourceType);
            return true;
        }
        return false;
    }

    /**
     * Checks if a field is absent, null, or blank.
     */
    private boolean isMissing(ObjectNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field == null || field.isNull() || (field.isTextual() && field.asText("").isBlank());
    }

    /**
     * Formats the current instant as an ISO-8601 UTC timestamp.
     * Visible for testing — can be overridden.
     */
    String formatNow() {
        return Instant.now().atOffset(ZoneOffset.UTC).format(ISO_UTC);
    }

    // ───────────────────── field default model ──────────────────────

    /**
     * Describes a single field default configuration.
     *
     * @param fieldName    the JSON field name (e.g. "effectiveDateTime")
     * @param type         the type of default to apply
     * @param defaultValue the literal value for {@link Type#VALUE} defaults
     */
    record FieldDefault(String fieldName, Type type, String defaultValue) {

        enum Type {
            /** Insert current UTC timestamp as a simple string field. */
            DATETIME,
            /** Insert current UTC timestamp into a nested {@code period.start}. */
            PERIOD_START,
            /** Insert a literal string value. */
            VALUE
        }

        /** Creates a datetime default (uses current timestamp). */
        static FieldDefault dateTime(String field) {
            return new FieldDefault(field, Type.DATETIME, null);
        }

        /** Creates a period.start default (uses current timestamp). */
        static FieldDefault periodStart(String field) {
            return new FieldDefault(field, Type.PERIOD_START, null);
        }

        /** Creates a literal value default. */
        static FieldDefault value(String field, String val) {
            return new FieldDefault(field, Type.VALUE, val);
        }
    }
}
