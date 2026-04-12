package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.DiscoveredConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PatientIdentifierEnricher}.
 */
class PatientIdentifierEnricherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PatientIdentifierEnricher enricher;

    private static DiscoveredConfig createTestConfig() {
        DiscoveredConfig config = new DiscoveredConfig();
        config.setIdentifierTypeName("OpenMRS ID");
        config.setIdentifierTypeUuid("05a29f94-c0ed-11e2-94be-8c13b969e334");
        config.setIdgenSourceUuid("691eed12-c0f1-11e2-94be-8c13b969e334");
        config.setLocationUuid("b1a8b05e-3542-4037-bbd3-998ee9c40574");
        config.setSourceIdentifierTypes(java.util.List.of("NID", "UPI"));
        config.setIdentifierTypeUuids(java.util.Map.of(
                "NID", "8d0e99db-9179-40e3-8edd-71570ba2686f",
                "UPI", "2f7359f1-79f7-43b2-bd4c-2e03dada3830"));
        return config;
    }

    @BeforeEach
    void setUp() {
        enricher = new PatientIdentifierEnricher(objectMapper, createTestConfig());
    }

    @Test
    void shouldPassThroughNonPatientResources() {
        String observationJson = """
                {"resourceType": "Observation", "status": "final"}
                """;

        String result = enricher.enrich("Observation", observationJson);

        assertEquals(observationJson, result);
    }

    @Test
    void shouldSkipEnrichmentWhenOpenMrsIdentifierAlreadyPresent() throws Exception {
        String patientJson = """
                {
                  "resourceType": "Patient",
                  "identifier": [
                    {
                      "use": "official",
                      "type": {"text": "OpenMRS ID"},
                      "value": "100008E"
                    }
                  ],
                  "name": [{"family": "Doe"}]
                }
                """;

        String result = enricher.enrich("Patient", patientJson);

        JsonNode resultNode = objectMapper.readTree(result);
        // Should still have the original identifier
        assertEquals("100008E", resultNode.path("identifier").get(0).path("value").asText());
    }

    @Test
    void shouldEnrichPatientWithGeneratedOpenMrsId() throws Exception {
        String patientJson = """
                {
                  "resourceType": "Patient",
                  "identifier": [
                    {
                      "use": "official",
                      "system": "http://example.org/national-id",
                      "value": "NID-123456"
                    }
                  ],
                  "name": [{"family": "Smith", "given": ["Jane"]}],
                  "gender": "female"
                }
                """;

        String result = enricher.enrich("Patient", patientJson);

        JsonNode enriched = objectMapper.readTree(result);
        JsonNode identifiers = enriched.path("identifier");

        // Should have 2 identifiers now
        assertEquals(2, identifiers.size());

        // Original identifier should be demoted to "secondary"
        assertEquals("secondary", identifiers.get(0).path("use").asText());
        assertEquals("NID-123456", identifiers.get(0).path("value").asText());

        // New OpenMRS identifier
        JsonNode openmrsId = identifiers.get(1);
        assertEquals("official", openmrsId.path("use").asText());
        assertEquals("OpenMRS ID", openmrsId.path("type").path("text").asText());
        assertEquals("05a29f94-c0ed-11e2-94be-8c13b969e334",
                openmrsId.path("type").path("coding").get(0).path("code").asText(),
                "OpenMRS ID should have type.coding with identifier type UUID");
        assertFalse(openmrsId.path("value").asText().isEmpty());

        // Should have location extension
        JsonNode extension = openmrsId.path("extension").get(0);
        assertEquals("http://fhir.openmrs.org/ext/patient/identifier#location",
                extension.path("url").asText());
        assertTrue(extension.path("valueReference").path("reference").asText()
                .contains("b1a8b05e-3542-4037-bbd3-998ee9c40574"));
    }

    @Test
    void shouldAddIdentifierArrayWhenPatientHasNone() throws Exception {
        String patientJson = """
                {
                  "resourceType": "Patient",
                  "name": [{"family": "NoId"}],
                  "gender": "male"
                }
                """;

        String result = enricher.enrich("Patient", patientJson);

        JsonNode enriched = objectMapper.readTree(result);
        JsonNode identifiers = enriched.path("identifier");

        assertEquals(1, identifiers.size());
        assertEquals("official", identifiers.get(0).path("use").asText());
        assertEquals("OpenMRS ID", identifiers.get(0).path("type").path("text").asText());
        assertFalse(identifiers.get(0).path("value").asText().isEmpty());
    }

    @Test
    void shouldGenerateUniqueIds() throws Exception {
        String patientJson = """
                {"resourceType": "Patient", "name": [{"family": "Test"}]}
                """;

        String result1 = enricher.enrich("Patient", patientJson);
        String result2 = enricher.enrich("Patient", patientJson);

        JsonNode id1 = objectMapper.readTree(result1).path("identifier").get(0).path("value");
        JsonNode id2 = objectMapper.readTree(result2).path("identifier").get(0).path("value");

        assertNotEquals(id1.asText(), id2.asText(), "Generated IDs should be unique");
    }

    @Test
    void shouldEnrichSourceIdentifiersWithTypeTextAndLocation() throws Exception {
        // Patient with NID and UPI identifiers that have system but no type.text
        String patientJson = """
                {
                  "resourceType": "Patient",
                  "identifier": [
                    {
                      "system": "http://example.org/nid",
                      "value": "1192880005226099"
                    },
                    {
                      "system": "http://example.org/upi",
                      "value": "251119-TEST-4106"
                    }
                  ],
                  "name": [{"family": "Enriched", "given": ["Source"]}],
                  "gender": "female"
                }
                """;

        String result = enricher.enrich("Patient", patientJson);

        JsonNode enriched = objectMapper.readTree(result);
        JsonNode identifiers = enriched.path("identifier");

        // Should have 3 identifiers: NID, UPI, + generated OpenMRS ID
        assertEquals(3, identifiers.size());

        // NID identifier should now have type.text = "NID", type.coding, and location extension
        // system field should be removed to prevent O3 from dropping identifiers with non-URI system
        JsonNode nid = identifiers.get(0);
        assertEquals("NID", nid.path("type").path("text").asText());
        assertEquals("8d0e99db-9179-40e3-8edd-71570ba2686f",
                nid.path("type").path("coding").get(0).path("code").asText(),
                "NID should have type.coding with identifier type UUID");
        assertTrue(nid.path("system").isMissingNode(),
                "system field should be removed after enrichment");
        assertEquals("secondary", nid.path("use").asText());
        assertEquals("http://fhir.openmrs.org/ext/patient/identifier#location",
                nid.path("extension").get(0).path("url").asText());

        // UPI identifier should now have type.text = "UPI", type.coding, and location extension
        JsonNode upi = identifiers.get(1);
        assertEquals("UPI", upi.path("type").path("text").asText());
        assertEquals("2f7359f1-79f7-43b2-bd4c-2e03dada3830",
                upi.path("type").path("coding").get(0).path("code").asText(),
                "UPI should have type.coding with identifier type UUID");
        assertTrue(upi.path("system").isMissingNode(),
                "system field should be removed after enrichment");
        assertEquals("secondary", upi.path("use").asText());
        assertTrue(upi.path("extension").get(0).path("valueReference")
                .path("reference").asText().contains("b1a8b05e"));
    }

    @Test
    void shouldNotEnrichSourceIdentifiersWhenNoSourceTypesConfigured() throws Exception {
        // Create config with empty source types and no discovery (null)
        DiscoveredConfig noSourceConfig = new DiscoveredConfig();
        noSourceConfig.setIdentifierTypeName("OpenMRS ID");
        noSourceConfig.setLocationUuid("b1a8b05e-3542-4037-bbd3-998ee9c40574");
        noSourceConfig.setSourceIdentifierTypes(java.util.List.of());

        PatientIdentifierEnricher noSourceEnricher =
                new PatientIdentifierEnricher(objectMapper, noSourceConfig);

        String patientJson = """
                {
                  "resourceType": "Patient",
                  "identifier": [
                    {
                      "system": "http://example.org/nid",
                      "value": "1192880005226099"
                    }
                  ],
                  "name": [{"family": "NoSource"}],
                  "gender": "male"
                }
                """;

        String result = noSourceEnricher.enrich("Patient", patientJson);
        JsonNode enriched = objectMapper.readTree(result);
        JsonNode identifiers = enriched.path("identifier");

        // NID should NOT have type.text (no source types configured, no discovery available)
        JsonNode nid = identifiers.get(0);
        assertTrue(nid.path("type").path("text").isMissingNode()
                || nid.path("type").path("text").asText("").isEmpty(),
                "NID should not have type.text when no source types are configured");
    }

    @Test
    void shouldAddSourceIdentifierTypeDynamically() {
        // Verify DiscoveredConfig.addSourceIdentifierType works atomically
        DiscoveredConfig config = new DiscoveredConfig();
        config.setSourceIdentifierTypes(java.util.List.of());
        config.setIdentifierTypeUuids(java.util.Map.of());

        config.addSourceIdentifierType("NID", "uuid-nid-123");

        assertEquals(1, config.getSourceIdentifierTypes().size());
        assertEquals("NID", config.getSourceIdentifierTypes().get(0));
        assertEquals("uuid-nid-123", config.getIdentifierTypeUuids().get("NID"));

        // Add a second type — first should still be there
        config.addSourceIdentifierType("UPI", "uuid-upi-456");

        assertEquals(2, config.getSourceIdentifierTypes().size());
        assertTrue(config.getSourceIdentifierTypes().contains("NID"));
        assertTrue(config.getSourceIdentifierTypes().contains("UPI"));
        assertEquals("uuid-nid-123", config.getIdentifierTypeUuids().get("NID"));
        assertEquals("uuid-upi-456", config.getIdentifierTypeUuids().get("UPI"));

        // Adding an existing type should not duplicate it
        config.addSourceIdentifierType("NID", "uuid-nid-123");
        assertEquals(2, config.getSourceIdentifierTypes().size());
    }
}
