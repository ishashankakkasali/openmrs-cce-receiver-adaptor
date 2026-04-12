package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VisitManagerTest {

    private static final String PATIENT_UUID = "26922e36-e154-4648-b168-f65999a54c69";
    private static final String VISIT_TYPE_UUID = "7b0f5697-27e3-40c4-8bae-f4049abfb4ed";
    private static final String VISIT_UUID = "e0cbb20e-f31e-422c-849f-e55d50837775";
    private static final String LOCATION_UUID = "58c57d25-8d39-41ab-8422-108a0c277d98";

    private ObjectMapper objectMapper;
    private DiscoveredConfig discoveredConfig;
    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    private RestClient.ResponseSpec getResponseSpec;
    private RestClient.ResponseSpec postResponseSpec;
    private VisitManager visitManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        discoveredConfig = new DiscoveredConfig();
        discoveredConfig.setVisitTypeUuid(VISIT_TYPE_UUID);
        discoveredConfig.setLocationUuid(LOCATION_UUID);

        restClient = mock(RestClient.class);
        requestHeadersUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        getResponseSpec = mock(RestClient.ResponseSpec.class, "getResponseSpec");
        postResponseSpec = mock(RestClient.ResponseSpec.class, "postResponseSpec");

        visitManager = new VisitManager(restClient, objectMapper, discoveredConfig);
    }

    private String makeEncounterJson(String patientUuid, boolean withPartOf) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("resourceType", "Encounter");
        root.put("status", "finished");

        // type array (clinical encounter)
        var typeArray = root.putArray("type");
        var typeObj = typeArray.addObject();
        var codingArray = typeObj.putArray("coding");
        var coding = codingArray.addObject();
        coding.put("code", "d7151f82-c1f3-4152-a605-2f9ea7414a79");
        coding.put("display", "Visit Note");

        // subject
        ObjectNode subject = root.putObject("subject");
        subject.put("reference", "Patient/" + patientUuid);

        // period
        ObjectNode period = root.putObject("period");
        period.put("start", "2026-04-05T09:00:00+02:00");

        // partOf
        if (withPartOf) {
            ObjectNode partOf = root.putObject("partOf");
            partOf.put("reference", "Encounter/" + VISIT_UUID);
        }

        return root.toString();
    }

    private String makeVisitEncounterJson() {
        // A Visit-type encounter has no type array
        ObjectNode root = objectMapper.createObjectNode();
        root.put("resourceType", "Encounter");
        root.put("status", "unknown");

        ObjectNode subject = root.putObject("subject");
        subject.put("reference", "Patient/" + PATIENT_UUID);

        ObjectNode period = root.putObject("period");
        period.put("start", "2026-04-05T07:00:00+00:00");

        return root.toString();
    }

    private void mockGetVisits(String responseJson) {
        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(anyString(), any(Object.class));
        doReturn(getResponseSpec).when(requestHeadersUriSpec).retrieve();
        doReturn(responseJson).when(getResponseSpec).body(String.class);
    }

    private void mockCreateVisit(String responseJson) {
        doReturn(requestBodyUriSpec).when(restClient).post();
        doReturn(requestBodyUriSpec).when(requestBodyUriSpec).uri(anyString());
        doReturn(requestBodyUriSpec).when(requestBodyUriSpec).header(anyString(), anyString());
        doReturn(requestBodyUriSpec).when(requestBodyUriSpec).body(anyString());
        doReturn(postResponseSpec).when(requestBodyUriSpec).retrieve();
        doReturn(responseJson).when(postResponseSpec).body(String.class);
    }

    @Nested
    @DisplayName("ensureVisitLinked — skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("returns unchanged JSON when partOf already set")
        void alreadyHasPartOf() {
            String json = makeEncounterJson(PATIENT_UUID, true);
            String result = visitManager.ensureVisitLinked(json);

            // Should not modify
            JsonNode root = parseJson(result);
            assertThat(root.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);
            // No REST calls made
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("returns unchanged JSON for Visit-type encounter (no type array)")
        void visitTypeEncounter() {
            String json = makeVisitEncounterJson();
            String result = visitManager.ensureVisitLinked(json);

            JsonNode root = parseJson(result);
            assertThat(root.has("partOf")).isFalse();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("returns unchanged JSON when no visit type UUID discovered")
        void noVisitTypeDiscovered() {
            discoveredConfig.setVisitTypeUuid(null);
            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            JsonNode root = parseJson(result);
            assertThat(root.has("partOf")).isFalse();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("returns unchanged JSON when no patient UUID in subject")
        void noPatientUuid() throws Exception {
            ObjectNode root = (ObjectNode) objectMapper.readTree(makeEncounterJson(PATIENT_UUID, false));
            root.remove("subject");
            String json = root.toString();

            String result = visitManager.ensureVisitLinked(json);
            JsonNode resultRoot = parseJson(result);
            assertThat(resultRoot.has("partOf")).isFalse();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("returns unchanged JSON when patient reference is not a UUID")
        void nonUuidPatientReference() throws Exception {
            ObjectNode root = (ObjectNode) objectMapper.readTree(makeEncounterJson(PATIENT_UUID, false));
            ((ObjectNode) root.get("subject")).put("reference", "Patient/source-id-123");
            String json = root.toString();

            String result = visitManager.ensureVisitLinked(json);
            JsonNode resultRoot = parseJson(result);
            assertThat(resultRoot.has("partOf")).isFalse();
            verifyNoInteractions(restClient);
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — active visit exists")
    class ActiveVisitExists {

        @Test
        @DisplayName("links encounter to existing active visit")
        void linksToExistingVisit() {
            String visitResponse = """
                    {"results": [
                        {"uuid": "%s", "display": "Facility Visit @ Outpatient Clinic - 04/05/2026",
                         "stopDatetime": null}
                    ]}""".formatted(VISIT_UUID);

            mockGetVisits(visitResponse);

            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            JsonNode root = parseJson(result);
            assertThat(root.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);
            assertThat(root.path("partOf").path("type").asText())
                    .isEqualTo("Encounter");

            // No POST to create visit
            verify(restClient, never()).post();
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — no active visit")
    class NoActiveVisit {

        @Test
        @DisplayName("creates new visit and links encounter")
        void createsNewVisit() {
            // GET returns no results
            String emptyVisitResponse = """
                    {"results": []}""";
            mockGetVisits(emptyVisitResponse);

            // POST creates a visit
            String createResponse = """
                    {"uuid": "%s", "display": "Facility Visit"}""".formatted(VISIT_UUID);
            mockCreateVisit(createResponse);

            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            JsonNode root = parseJson(result);
            assertThat(root.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — caching")
    class Caching {

        @Test
        @DisplayName("uses cached visit for same patient in same batch")
        void usesCachedVisit() {
            String visitResponse = """
                    {"results": [
                        {"uuid": "%s", "display": "Facility Visit",
                         "stopDatetime": null}
                    ]}""".formatted(VISIT_UUID);

            mockGetVisits(visitResponse);

            // First call
            String json1 = makeEncounterJson(PATIENT_UUID, false);
            visitManager.ensureVisitLinked(json1);

            // Second call — should use cache, no additional REST calls
            String json2 = makeEncounterJson(PATIENT_UUID, false);
            String result2 = visitManager.ensureVisitLinked(json2);

            JsonNode root = parseJson(result2);
            assertThat(root.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);

            // GET should only be called once
            verify(restClient, times(1)).get();
        }

        @Test
        @DisplayName("clearCache removes cached visits")
        void clearCacheWorks() {
            String visitResponse = """
                    {"results": [
                        {"uuid": "%s", "display": "Facility Visit",
                         "stopDatetime": null}
                    ]}""".formatted(VISIT_UUID);

            mockGetVisits(visitResponse);

            // First call
            visitManager.ensureVisitLinked(makeEncounterJson(PATIENT_UUID, false));

            // Clear cache
            visitManager.clearCache();

            // Second call — should make a new REST call
            visitManager.ensureVisitLinked(makeEncounterJson(PATIENT_UUID, false));

            // GET should be called twice
            verify(restClient, times(2)).get();
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns original JSON when REST call fails")
        void restCallFails() {
            doReturn(requestHeadersUriSpec).when(restClient).get();
            doReturn(requestHeadersUriSpec).when(requestHeadersUriSpec).uri(anyString(), any(Object.class));
            doReturn(getResponseSpec).when(requestHeadersUriSpec).retrieve();
            doThrow(new RuntimeException("Connection refused")).when(getResponseSpec).body(String.class);

            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            // Should return unchanged
            JsonNode root = parseJson(result);
            assertThat(root.has("partOf")).isFalse();
        }

        @Test
        @DisplayName("returns original JSON when visit creation fails")
        void visitCreationFails() {
            // GET returns empty
            String emptyResponse = """
                    {"results": []}""";
            mockGetVisits(emptyResponse);

            // POST fails
            doReturn(requestBodyUriSpec).when(restClient).post();
            doReturn(requestBodyUriSpec).when(requestBodyUriSpec).uri(anyString());
            doReturn(requestBodyUriSpec).when(requestBodyUriSpec).header(anyString(), anyString());
            doReturn(requestBodyUriSpec).when(requestBodyUriSpec).body(anyString());
            doReturn(postResponseSpec).when(requestBodyUriSpec).retrieve();
            doThrow(new RuntimeException("500 Internal Server Error")).when(postResponseSpec).body(String.class);

            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            JsonNode root = parseJson(result);
            assertThat(root.has("partOf")).isFalse();
        }

        @Test
        @DisplayName("returns original JSON for invalid input")
        void invalidJson() {
            String result = visitManager.ensureVisitLinked("not valid json");
            assertThat(result).isEqualTo("not valid json");
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — datetime handling")
    class DatetimeHandling {

        @Test
        @DisplayName("uses period.start when available")
        void usesPeriodStart() throws Exception {
            String emptyResponse = """
                    {"results": []}""";
            mockGetVisits(emptyResponse);

            String createResponse = """
                    {"uuid": "%s", "display": "Facility Visit"}""".formatted(VISIT_UUID);
            mockCreateVisit(createResponse);

            String json = makeEncounterJson(PATIENT_UUID, false);
            visitManager.ensureVisitLinked(json);

            // Verify POST was called (visit created)
            verify(restClient).post();
        }

        @Test
        @DisplayName("falls back to current time when no period.start")
        void fallsBackToCurrentTime() throws Exception {
            // Create encounter without period
            ObjectNode root = (ObjectNode) objectMapper.readTree(makeEncounterJson(PATIENT_UUID, false));
            root.remove("period");
            String json = root.toString();

            String emptyResponse = """
                    {"results": []}""";
            mockGetVisits(emptyResponse);

            String createResponse = """
                    {"uuid": "%s", "display": "Facility Visit"}""".formatted(VISIT_UUID);
            mockCreateVisit(createResponse);

            String result = visitManager.ensureVisitLinked(json);

            JsonNode resultRoot = parseJson(result);
            assertThat(resultRoot.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);
        }
    }

    @Nested
    @DisplayName("ensureVisitLinked — visit filtering")
    class VisitFiltering {

        @Test
        @DisplayName("skips closed visits (with stopDatetime)")
        void skipsClosedVisits() {
            String visitResponse = """
                    {"results": [
                        {"uuid": "old-visit-uuid", "display": "Old Visit",
                         "stopDatetime": "2026-04-04T23:59:59.000+0000"}
                    ]}""";
            mockGetVisits(visitResponse);

            String createResponse = """
                    {"uuid": "%s", "display": "Facility Visit"}""".formatted(VISIT_UUID);
            mockCreateVisit(createResponse);

            String json = makeEncounterJson(PATIENT_UUID, false);
            String result = visitManager.ensureVisitLinked(json);

            // Should have created new visit (closed visit was skipped)
            JsonNode root = parseJson(result);
            assertThat(root.path("partOf").path("reference").asText())
                    .isEqualTo("Encounter/" + VISIT_UUID);
            verify(restClient).post();
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}
