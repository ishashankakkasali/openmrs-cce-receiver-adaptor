package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FhirReferenceResolver}.
 */
@ExtendWith(MockitoExtension.class)
class FhirReferenceResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestClient fhirRestClient;

    @Mock
    private RestClient openmrsRestClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> fhirGetSpec;

    @Mock
    private RestClient.ResponseSpec fhirResponseSpec;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> restGetSpec;

    @Mock
    private RestClient.ResponseSpec restResponseSpec;

    @Mock
    private ConceptResolver conceptResolver;

    private FhirReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FhirReferenceResolver(fhirRestClient, openmrsRestClient, objectMapper, conceptResolver);
    }

    // ─────────── Reference resolution ───────────

    @Test
    void shouldPassThroughUuidReferences() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "subject": {
                    "reference": "Patient/0837d576-ee85-4a1d-bc53-adc4242cba51"
                  }
                }
                """;

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("Patient/0837d576-ee85-4a1d-bc53-adc4242cba51",
                root.path("subject").path("reference").asText());

        // No FHIR searches should have been made
        verifyNoInteractions(fhirRestClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolvePatientReferenceByIdentifier() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "subject": {
                    "reference": "Patient/260402-NEW1-7777",
                    "type": "Patient",
                    "display": "SBA akkas"
                  }
                }
                """;

        // Mock: GET /Patient?identifier=260402-NEW1-7777&_count=1
        mockFhirSearch("""
                {"resourceType":"Bundle","total":1,"entry":[
                  {"resource":{"resourceType":"Patient","id":"0837d576-ee85-4a1d-bc53-adc4242cba51"}}
                ]}
                """);

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("Patient/0837d576-ee85-4a1d-bc53-adc4242cba51",
                root.path("subject").path("reference").asText());
        // display and type should be preserved
        assertEquals("SBA akkas", root.path("subject").path("display").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolvePractitionerReference() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "participant": [{
                    "individual": {
                      "reference": "Practitioner/HLC-PRAC-2025-00005",
                      "display": "MUTIMUKEYE Clarisse"
                    }
                  }]
                }
                """;

        mockFhirSearch("""
                {"resourceType":"Bundle","total":1,"entry":[
                  {"resource":{"resourceType":"Practitioner","id":"f9badd80-ab76-11e2-9e96-0800200c9a66"}}
                ]}
                """);

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("Practitioner/f9badd80-ab76-11e2-9e96-0800200c9a66",
                root.path("participant").get(0).path("individual").path("reference").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveMultipleReferencesInOneResource() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "subject": {"reference": "Patient/UPI-001"},
                  "participant": [{"individual": {"reference": "Practitioner/PRAC-001"}}]
                }
                """;

        // Mock will be called twice — return different results based on invocation
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(fhirRestClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(fhirResponseSpec);
        when(fhirResponseSpec.body(String.class))
                .thenReturn("""
                        {"resourceType":"Bundle","total":1,"entry":[
                          {"resource":{"id":"patient-uuid-111"}}
                        ]}
                        """)
                .thenReturn("""
                        {"resourceType":"Bundle","total":1,"entry":[
                          {"resource":{"id":"practitioner-uuid-222"}}
                        ]}
                        """);

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("Patient/patient-uuid-111",
                root.path("subject").path("reference").asText());
        assertEquals("Practitioner/practitioner-uuid-222",
                root.path("participant").get(0).path("individual").path("reference").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldLeaveReferenceAsIsWhenNotFound() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "subject": {"reference": "Patient/NONEXISTENT-ID"}
                }
                """;

        // Mock: search returns empty bundle
        mockFhirSearch("""
                {"resourceType":"Bundle","total":0}
                """);

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        assertEquals("Patient/NONEXISTENT-ID",
                root.path("subject").path("reference").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCacheResolvedReferences() throws Exception {
        // Resolve once
        resolver.clearCaches();

        mockFhirSearch("""
                {"resourceType":"Bundle","total":1,"entry":[
                  {"resource":{"id":"cached-uuid-aaa"}}
                ]}
                """);

        String ref1 = resolver.resolveReference("Patient/MY-ID-123");
        assertEquals("Patient/cached-uuid-aaa", ref1);

        // Second call should use cache — no additional FHIR search
        String ref2 = resolver.resolveReference("Patient/MY-ID-123");
        assertEquals("Patient/cached-uuid-aaa", ref2);

        // fhirRestClient.get() called only once
        verify(fhirRestClient, times(1)).get();
    }

    @Test
    void shouldPassThroughNonFhirReferenceFormats() {
        // Contained reference
        assertEquals("#contained-1", resolver.resolveReference("#contained-1"));
        // Full URL
        assertEquals("http://example.org/Patient/123",
                resolver.resolveReference("http://example.org/Patient/123"));
    }

    @Test
    void shouldPassThroughNonResourceJson() {
        String invalid = "not-json";
        // Should not throw, just return as-is
        String result = resolver.resolve("Encounter", invalid);
        assertEquals(invalid, result);
    }

    // ─────────── Encounter type resolution ───────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveEncounterTypeByExactName() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"display": "Visit Note"}]}]
                }
                """;

        mockRestSearch(ENCOUNTER_TYPES_JSON);

        String result = resolver.resolve("Encounter", json);

        JsonNode root = objectMapper.readTree(result);
        JsonNode coding = root.path("type").get(0).path("coding").get(0);
        assertEquals("http://fhir.openmrs.org/code-system/encounter-type",
                coding.path("system").asText());
        assertEquals("d7151f82-c1f3-4152-a605-2f9ea7414a79",
                coding.path("code").asText());
        assertEquals("Visit Note", coding.path("display").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveEncounterTypeWithUnderscoreNormalization() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"display": "Visit_Note"}]}]
                }
                """;

        mockRestSearch(ENCOUNTER_TYPES_JSON);

        String result = resolver.resolve("Encounter", json);

        JsonNode coding = objectMapper.readTree(result)
                .path("type").get(0).path("coding").get(0);
        assertEquals("d7151f82-c1f3-4152-a605-2f9ea7414a79",
                coding.path("code").asText());
        assertEquals("Visit Note", coding.path("display").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveEncounterTypeByPrefixAfterStrippingSuffix() throws Exception {
        // "VISIT_ENCOUNTER" → normalize → "Visit Encounter" → strip "Encounter" → "Visit"
        // → prefix-matches "Visit Note"
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"display": "VISIT_ENCOUNTER"}]}]
                }
                """;

        mockRestSearch(ENCOUNTER_TYPES_JSON);

        String result = resolver.resolve("Encounter", json);

        JsonNode coding = objectMapper.readTree(result)
                .path("type").get(0).path("coding").get(0);
        assertEquals("d7151f82-c1f3-4152-a605-2f9ea7414a79",
                coding.path("code").asText());
        assertEquals("Visit Note", coding.path("display").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSkipAlreadyResolvedEncounterType() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{
                    "system": "http://fhir.openmrs.org/code-system/encounter-type",
                    "code": "d7151f82-c1f3-4152-a605-2f9ea7414a79",
                    "display": "Visit Note"
                  }]}]
                }
                """;

        String result = resolver.resolve("Encounter", json);

        // Should be unchanged — no REST calls made
        verifyNoInteractions(openmrsRestClient);
        JsonNode coding = objectMapper.readTree(result)
                .path("type").get(0).path("coding").get(0);
        assertEquals("d7151f82-c1f3-4152-a605-2f9ea7414a79",
                coding.path("code").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNotResolveEncounterTypeForNonEncounterResources() throws Exception {
        String json = """
                {
                  "resourceType": "Patient",
                  "identifier": [{"value": "123"}]
                }
                """;

        String result = resolver.resolve("Patient", json);

        // No REST calls for encounter type
        verifyNoInteractions(openmrsRestClient);
        assertNotNull(result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveEncounterTypeByContainsMatch() throws Exception {
        // "Vitals" is a substring match
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"display": "Vitals"}]}]
                }
                """;

        mockRestSearch(ENCOUNTER_TYPES_JSON);

        String result = resolver.resolve("Encounter", json);

        JsonNode coding = objectMapper.readTree(result)
                .path("type").get(0).path("coding").get(0);
        assertEquals("67a71486-1a54-468f-ac3e-7091a9a79584",
                coding.path("code").asText());
    }

    // ─────────── Full pipeline: references + encounter type ───────────

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveReferencesAndEncounterTypeInFullEncounter() throws Exception {
        String json = """
                {
                  "resourceType": "Encounter",
                  "type": [{"coding": [{"display": "VISIT_ENCOUNTER"}]}],
                  "subject": {"reference": "Patient/UPI-99"},
                  "participant": [{"individual": {"reference": "Practitioner/PRAC-5"}}],
                  "location": [{"location": {"reference": "Location/58c57d25-8d39-41ab-8422-108a0c277d98"}}]
                }
                """;

        // Mock FHIR searches for Patient and Practitioner
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(fhirRestClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(fhirResponseSpec);
        when(fhirResponseSpec.body(String.class))
                .thenReturn("""
                        {"resourceType":"Bundle","total":1,"entry":[{"resource":{"id":"pat-uuid"}}]}
                        """)
                .thenReturn("""
                        {"resourceType":"Bundle","total":1,"entry":[{"resource":{"id":"prac-uuid"}}]}
                        """);

        // Mock REST search for encounter type
        mockRestSearch(ENCOUNTER_TYPES_JSON);

        String result = resolver.resolve("Encounter", json);
        JsonNode root = objectMapper.readTree(result);

        // References resolved
        assertEquals("Patient/pat-uuid", root.path("subject").path("reference").asText());
        assertEquals("Practitioner/prac-uuid",
                root.path("participant").get(0).path("individual").path("reference").asText());
        // Location was already UUID — unchanged
        assertEquals("Location/58c57d25-8d39-41ab-8422-108a0c277d98",
                root.path("location").get(0).path("location").path("reference").asText());
        // Encounter type resolved
        JsonNode coding = root.path("type").get(0).path("coding").get(0);
        assertEquals("d7151f82-c1f3-4152-a605-2f9ea7414a79", coding.path("code").asText());
    }

    // ─────────── Helpers ───────────

    @SuppressWarnings("unchecked")
    private void mockFhirSearch(String responseBody) {
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(fhirRestClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(fhirResponseSpec);
        when(fhirResponseSpec.body(String.class)).thenReturn(responseBody);
    }

    @SuppressWarnings("unchecked")
    private void mockRestSearch(String responseBody) {
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(openmrsRestClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(restResponseSpec);
        when(restResponseSpec.body(String.class)).thenReturn(responseBody);
    }

    // ─────────── Concept code resolution ───────────

    @Test
    void shouldResolveLoincCodeToOpenMrsConcept() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {
                    "coding": [
                      {"system": "http://loinc.org", "code": "8480-6", "display": "Systolic blood pressure"}
                    ]
                  },
                  "subject": {"reference": "Patient/0837d576-ee85-4a1d-bc53-adc4242cba51"}
                }
                """;

        when(conceptResolver.resolve(any(JsonNode.class)))
                .thenReturn("5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        String result = resolver.resolve("Observation", json);

        JsonNode root = objectMapper.readTree(result);
        JsonNode codingArray = root.path("code").path("coding");
        assertEquals(2, codingArray.size(), "Should have OpenMRS coding + original");
        // OpenMRS coding inserted first (no system, just code + display)
        assertFalse(codingArray.get(0).has("system"), "OpenMRS coding should have no system");
        assertEquals("5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", codingArray.get(0).path("code").asText());
        assertEquals("Systolic blood pressure", codingArray.get(0).path("display").asText());
        // Original LOINC coding preserved at position 1
        assertEquals("http://loinc.org", codingArray.get(1).path("system").asText());
        assertEquals("8480-6", codingArray.get(1).path("code").asText());
    }

    @Test
    void shouldSkipConceptResolutionWhenOpenMrsCodingAlreadyPresent() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {
                    "coding": [
                      {"system": "http://loinc.org", "code": "8480-6"},
                      {"system": "http://openmrs.org/concepts", "code": "5085AAAA"}
                    ]
                  }
                }
                """;

        String result = resolver.resolve("Observation", json);

        JsonNode codingArray = objectMapper.readTree(result).path("code").path("coding");
        assertEquals(2, codingArray.size(), "Should NOT add another OpenMRS coding");
        // ConceptResolver should not have been called
        verifyNoInteractions(conceptResolver);
    }

    @Test
    void shouldNotAddOpenMrsCodingWhenResolutionFails() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {
                    "coding": [{"system": "http://loinc.org", "code": "UNKNOWN-CODE"}]
                  }
                }
                """;

        when(conceptResolver.resolve(any(JsonNode.class)))
                .thenThrow(new RuntimeException("Not found"));

        String result = resolver.resolve("Observation", json);

        JsonNode codingArray = objectMapper.readTree(result).path("code").path("coding");
        assertEquals(1, codingArray.size(), "Should still have only original coding");
    }

    // ─────────── Nested concept resolution (component) ───────────

    @Test
    void shouldResolveConceptCodesInObservationComponents() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {
                    "coding": [{"system": "http://loinc.org", "code": "85354-9", "display": "Blood pressure panel"}]
                  },
                  "component": [
                    {
                      "code": {
                        "coding": [{"system": "http://loinc.org", "code": "8480-6", "display": "Systolic"}]
                      },
                      "valueQuantity": {"value": 120, "unit": "mmHg"}
                    },
                    {
                      "code": {
                        "coding": [{"system": "http://loinc.org", "code": "8462-4", "display": "Diastolic"}]
                      },
                      "valueQuantity": {"value": 80, "unit": "mmHg"}
                    }
                  ]
                }
                """;

        // Mock: resolve each LOINC code to a different OpenMRS UUID
        when(conceptResolver.resolve(any(JsonNode.class)))
                .thenReturn("PANEL-UUID-AAAA")  // panel code
                .thenReturn("SYSTOLIC-UUID-AAAA")  // component[0].code
                .thenReturn("DIASTOLIC-UUID-AAAA");  // component[1].code

        String result = resolver.resolve("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        // Root code should have OpenMRS coding inserted
        JsonNode rootCoding = root.path("code").path("coding");
        assertEquals(2, rootCoding.size());
        assertEquals("PANEL-UUID-AAAA", rootCoding.get(0).path("code").asText());

        // component[0].code should have OpenMRS coding inserted
        JsonNode comp0Coding = root.path("component").get(0).path("code").path("coding");
        assertEquals(2, comp0Coding.size());
        assertEquals("SYSTOLIC-UUID-AAAA", comp0Coding.get(0).path("code").asText());

        // component[1].code should have OpenMRS coding inserted
        JsonNode comp1Coding = root.path("component").get(1).path("code").path("coding");
        assertEquals(2, comp1Coding.size());
        assertEquals("DIASTOLIC-UUID-AAAA", comp1Coding.get(0).path("code").asText());
    }

    @Test
    void shouldResolveValueCodeableConceptField() throws Exception {
        String json = """
                {
                  "resourceType": "Observation",
                  "code": {
                    "coding": [{"code": "5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "display": "Systolic"}]
                  },
                  "valueCodeableConcept": {
                    "coding": [{"system": "http://snomed.info/sct", "code": "255604002", "display": "Mild"}]
                  }
                }
                """;

        // code is already OpenMRS UUID format so won't be resolved
        // valueCodeableConcept should be resolved
        when(conceptResolver.resolve(any(JsonNode.class)))
                .thenReturn("MILD-CONCEPT-UUID");

        String result = resolver.resolve("Observation", json);
        JsonNode root = objectMapper.readTree(result);

        JsonNode valueCoding = root.path("valueCodeableConcept").path("coding");
        assertEquals(2, valueCoding.size());
        assertEquals("MILD-CONCEPT-UUID", valueCoding.get(0).path("code").asText());
    }

    /** Simulated OpenMRS encounter types for tests. */
    private static final String ENCOUNTER_TYPES_JSON = """
            {"results": [
              {"uuid": "e22e39fd-7db2-45e7-80f1-60fa0d5a4378", "display": "Admission", "retired": false},
              {"uuid": "ca3aed11-1aa4-42a1-b85c-8332fc8001fc", "display": "Check In", "retired": false},
              {"uuid": "d7151f82-c1f3-4152-a605-2f9ea7414a79", "display": "Visit Note", "retired": false},
              {"uuid": "67a71486-1a54-468f-ac3e-7091a9a79584", "display": "Vitals", "retired": false}
            ]}
            """;
}
