package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FhirElementIdEnricherTest {

    private FhirElementIdEnricher enricher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        enricher = new FhirElementIdEnricher(objectMapper);
    }

    @Test
    void shouldAddIdToNameElementsMissingId() throws Exception {
        String json = """
                {
                  "resourceType": "Patient",
                  "name": [{"family": "Smith", "given": ["John"]}]
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);
        JsonNode nameId = root.path("name").get(0).path("id");

        assertThat(nameId.isMissingNode()).isFalse();
        assertThat(nameId.asText()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldNotOverwriteExistingIds() throws Exception {
        String json = """
                {
                  "resourceType": "Patient",
                  "name": [{"id": "existing-uuid", "family": "Smith"}]
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);
        String nameId = root.path("name").get(0).path("id").asText();

        assertThat(nameId).isEqualTo("existing-uuid");
    }

    @Test
    void shouldAddIdsToMultipleArrayFields() throws Exception {
        String json = """
                {
                  "resourceType": "Patient",
                  "name": [{"family": "Smith"}],
                  "identifier": [{"value": "12345"}],
                  "address": [{"city": "Springfield"}],
                  "telecom": [{"value": "555-1234"}]
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);

        assertThat(root.path("name").get(0).has("id")).isTrue();
        assertThat(root.path("identifier").get(0).has("id")).isTrue();
        assertThat(root.path("address").get(0).has("id")).isTrue();
        assertThat(root.path("telecom").get(0).has("id")).isTrue();
    }

    @Test
    void shouldHandleEncounterWithLocationAndType() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"code": "test-type"}]}],
                  "location": [{"location": {"reference": "Location/abc"}}]
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);

        assertThat(root.path("type").get(0).has("id")).isTrue();
        assertThat(root.path("location").get(0).has("id")).isTrue();
    }

    @Test
    void shouldReturnOriginalForNonObjectRoot() {
        String json = "\"just a string\"";
        String enriched = enricher.enrich(json);
        assertThat(enriched).isEqualTo(json);
    }

    @Test
    void shouldReturnOriginalWhenNoArrayFieldsPresent() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "status": "final",
                  "subject": {"reference": "Patient/123"}
                }
                """;

        String enriched = enricher.enrich(json);
        // No array fields that need IDs → should return original
        assertThat(enriched).isEqualTo(json);
    }

    @Test
    void shouldHandleBlankIdField() throws Exception {
        String json = """
                {
                  "resourceType": "Patient",
                  "name": [{"id": "", "family": "Smith"}]
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);
        String nameId = root.path("name").get(0).path("id").asText();

        assertThat(nameId).isNotBlank();
        assertThat(nameId).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldHandleNestedCodingArraysInObservation() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "category": [{"coding": [{"system": "http://x", "code": "vital-signs"}]}],
                  "code": {"coding": [{"code": "5085AAAA"}]}
                }
                """;

        String enriched = enricher.enrich(json);
        JsonNode root = objectMapper.readTree(enriched);

        // category is a known array field → should get id
        assertThat(root.path("category").get(0).has("id")).isTrue();
        // coding inside code (nested) → also should get id
        assertThat(root.path("code").path("coding").get(0).has("id")).isTrue();
    }
}
