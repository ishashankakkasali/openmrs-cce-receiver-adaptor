package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequiredFieldEnricher}.
 */
class RequiredFieldEnricherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RequiredFieldEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new RequiredFieldEnricher(objectMapper);
    }

    // ───────────── Observation ─────────────

    @Test
    void shouldAddEffectiveDateTimeToObservation() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "status": "final",
                  "code": {"coding": [{"code": "5085AAAA"}]},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("effectiveDateTime"), "effectiveDateTime should be added");
        assertFalse(root.get("effectiveDateTime").asText().isBlank());
        // status should remain unchanged (already present)
        assertEquals("final", root.get("status").asText());
    }

    @Test
    void shouldNotOverwriteExistingEffectiveDateTime() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "status": "final",
                  "effectiveDateTime": "2025-11-15T10:00:00Z",
                  "code": {"coding": [{"code": "5085AAAA"}]}
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("2025-11-15T10:00:00Z", root.get("effectiveDateTime").asText());
    }

    @Test
    void shouldAddDefaultStatusToObservation() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {"coding": [{"code": "5085AAAA"}]}
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("final", root.get("status").asText());
        assertTrue(root.has("effectiveDateTime"));
    }

    // ───────────── Encounter ─────────────

    @Test
    void shouldAddPeriodStartToEncounter() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "status": "finished",
                  "class": {"code": "AMB"},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("Encounter", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("period"), "period should be added");
        assertTrue(root.path("period").has("start"), "period.start should be added");
        assertFalse(root.path("period").path("start").asText().isBlank());
        // status already present, should NOT change
        assertEquals("finished", root.get("status").asText());
    }

    @Test
    void shouldAddStartToExistingPeriodWithoutStart() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "period": {"end": "2025-11-15T12:00:00Z"},
                  "class": {"code": "AMB"}
                }
                """;

        String result = enricher.enrich("Encounter", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.path("period").has("start"));
        // end should remain
        assertEquals("2025-11-15T12:00:00Z", root.path("period").path("end").asText());
    }

    @Test
    void shouldNotOverwriteExistingPeriodStart() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "period": {"start": "2025-11-15T10:00:00Z"},
                  "class": {"code": "AMB"}
                }
                """;

        String result = enricher.enrich("Encounter", json);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("2025-11-15T10:00:00Z", root.path("period").path("start").asText());
    }

    @Test
    void shouldAddDefaultStatusToEncounter() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "class": {"code": "AMB"}
                }
                """;

        String result = enricher.enrich("Encounter", json);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("unknown", root.get("status").asText());
    }

    // ───────────── Condition ─────────────

    @Test
    void shouldAddOnsetAndRecordedDateToCondition() throws Exception {
        String json = """
                {
                  "resourceType": "Condition",
                  "code": {"coding": [{"code": "some-concept"}]},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("Condition", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("onsetDateTime"));
        assertTrue(root.has("recordedDate"));
    }

    // ───────────── AllergyIntolerance ─────────────

    @Test
    void shouldAddRecordedDateToAllergyIntolerance() throws Exception {
        String json = """
                {
                  "resourceType": "AllergyIntolerance",
                  "patient": {"reference": "Patient/abc-123"},
                  "code": {"coding": [{"code": "allergy-concept"}]}
                }
                """;

        String result = enricher.enrich("AllergyIntolerance", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("recordedDate"));
    }

    // ───────────── Immunization ─────────────

    @Test
    void shouldAddOccurrenceDateTimeToImmunization() throws Exception {
        String json = """
                {
                  "resourceType": "Immunization",
                  "vaccineCode": {"coding": [{"code": "vaccine-concept"}]},
                  "patient": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("Immunization", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("occurrenceDateTime"));
        assertEquals("completed", root.get("status").asText());
    }

    // ───────────── DiagnosticReport ─────────────

    @Test
    void shouldAddDateFieldsToDiagnosticReport() throws Exception {
        String json = """
                {
                  "resourceType": "DiagnosticReport",
                  "code": {"coding": [{"code": "report-concept"}]},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("DiagnosticReport", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("effectiveDateTime"));
        assertTrue(root.has("issued"));
        assertEquals("final", root.get("status").asText());
    }

    // ───────────── ServiceRequest ─────────────

    @Test
    void shouldAddAuthoredOnToServiceRequest() throws Exception {
        String json = """
                {
                  "resourceType": "ServiceRequest",
                  "code": {"coding": [{"code": "test-concept"}]},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("ServiceRequest", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("authoredOn"));
        assertEquals("active", root.get("status").asText());
        assertEquals("order", root.get("intent").asText());
    }

    @Test
    void shouldNotOverwriteExistingServiceRequestFields() throws Exception {
        String json = """
                {
                  "resourceType": "ServiceRequest",
                  "status": "completed",
                  "intent": "reflex-order",
                  "authoredOn": "2025-06-01T09:00:00Z",
                  "code": {"coding": [{"code": "test-concept"}]}
                }
                """;

        String result = enricher.enrich("ServiceRequest", json);
        JsonNode root = objectMapper.readTree(result);

        assertEquals("completed", root.get("status").asText());
        assertEquals("reflex-order", root.get("intent").asText());
        assertEquals("2025-06-01T09:00:00Z", root.get("authoredOn").asText());
    }

    // ───────────── MedicationRequest ─────────────

    @Test
    void shouldAddAuthoredOnToMedicationRequest() throws Exception {
        String json = """
                {
                  "resourceType": "MedicationRequest",
                  "medicationCodeableConcept": {"coding": [{"code": "drug-concept"}]},
                  "subject": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("MedicationRequest", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("authoredOn"));
        assertEquals("active", root.get("status").asText());
        assertEquals("order", root.get("intent").asText());
    }

    // ───────────── Task ─────────────

    @Test
    void shouldAddDefaultsToTask() throws Exception {
        String json = """
                {
                  "resourceType": "Task",
                  "for": {"reference": "Patient/abc-123"}
                }
                """;

        String result = enricher.enrich("Task", json);
        JsonNode root = objectMapper.readTree(result);

        assertTrue(root.has("authoredOn"));
        assertEquals("requested", root.get("status").asText());
        assertEquals("order", root.get("intent").asText());
    }

    // ───────────── No-op cases ─────────────

    @ParameterizedTest
    @ValueSource(strings = {"Patient", "Location", "Practitioner", "Medication",
            "Group", "Person", "RelatedPerson"})
    void shouldPassThroughResourcesWithNoDefaults(String resourceType) throws Exception {
        String json = """
                {
                  "resourceType": "%s",
                  "name": [{"family": "Test"}]
                }
                """.formatted(resourceType);

        String result = enricher.enrich(resourceType, json);

        // Should return unchanged
        JsonNode original = objectMapper.readTree(json);
        JsonNode enriched = objectMapper.readTree(result);
        assertEquals(original, enriched);
    }

    @Test
    void shouldHandleInvalidJsonGracefully() {
        String badJson = "not-valid-json";
        String result = enricher.enrich("Observation", badJson);
        assertEquals(badJson, result, "Should return original on parse error");
    }

    @Test
    void shouldHandleNonObjectRootGracefully() {
        String arrayJson = "[1, 2, 3]";
        String result = enricher.enrich("Observation", arrayJson);
        assertEquals(arrayJson, result, "Should return original for non-object root");
    }

    // ───────────── Edge cases ─────────────

    @Test
    void shouldHandleNullFieldValue() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "effectiveDateTime": null,
                  "status": null
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        // Null fields should be treated as missing and populated
        assertNotNull(root.get("effectiveDateTime"));
        assertFalse(root.get("effectiveDateTime").isNull());
        assertEquals("final", root.get("status").asText());
    }

    @Test
    void shouldHandleBlankFieldValue() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "effectiveDateTime": "",
                  "status": "  "
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        // Blank fields should be treated as missing and populated
        assertFalse(root.get("effectiveDateTime").asText().isBlank());
    }

    @Test
    void shouldProduceValidIsoTimestamp() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {"coding": [{"code": "5085AAAA"}]}
                }
                """;

        String result = enricher.enrich("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        String ts = root.get("effectiveDateTime").asText();
        // Should be ISO UTC format ending with Z
        assertTrue(ts.endsWith("Z"), "Timestamp should be UTC (end with Z): " + ts);
        assertTrue(ts.contains("T"), "Should contain T separator: " + ts);
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "Should match ISO-8601 pattern: " + ts);
    }
}
