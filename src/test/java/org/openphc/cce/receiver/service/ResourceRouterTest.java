package org.openphc.cce.receiver.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.receiver.fhir.CapabilityStatementCache;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceRouter}, focusing on source-ID mapping
 * (create vs update detection) and routing logic.
 */
@ExtendWith(MockitoExtension.class)
class ResourceRouterTest {

    @Mock private CapabilityStatementCache capabilityCache;
    @Mock private OpenMrsFhirClient fhirClient;
    @Mock private OpenMrsRestClient restClient;

    private SourceIdMappingStore mappingStore;
    private ResourceRouter router;

    private static final String OPENMRS_UUID = "aaaa-bbbb-cccc-dddd";

    @BeforeEach
    void setUp() {
        mappingStore = new SourceIdMappingStore();
        router = new ResourceRouter(
                capabilityCache, fhirClient, restClient,
                new SimpleMeterRegistry(), mappingStore, new ObjectMapper());
    }

    // ----- Create vs Update detection -----

    @Test
    void shouldPostNewResourceAndStoreMapping() {
        // Source sends an Encounter with a source ID not yet seen
        String json = """
                {"resourceType":"Encounter","id":"src-enc-001","status":"inprogress"}
                """;
        ResourceEntry entry = new ResourceEntry("Encounter", json, "POST", null);

        when(capabilityCache.canFhir("Encounter", "create")).thenReturn(true);
        when(fhirClient.send(eq("Encounter"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Encounter", OPENMRS_UUID, "fhir", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals("created", results.get(0).status());

        // Verify the mapping was stored
        assertTrue(mappingStore.contains("Encounter", "src-enc-001"));
        assertEquals(OPENMRS_UUID, mappingStore.getOpenMrsUuid("Encounter", "src-enc-001").orElse(null));

        // Verify the source ID was stripped from the JSON sent to FHIR
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(fhirClient).send(eq("Encounter"), jsonCaptor.capture(), eq("POST"));
        assertFalse(jsonCaptor.getValue().contains("src-enc-001"),
                "Source ID should be stripped from the JSON for POST");
    }

    @Test
    void shouldPutExistingResourceUsingMapping() {
        // Pre-populate the mapping (simulate a previous create)
        mappingStore.store("Encounter", "src-enc-001", OPENMRS_UUID);

        // Source sends the same Encounter again with updated status
        String json = """
                {"resourceType":"Encounter","id":"src-enc-001","status":"finished"}
                """;
        ResourceEntry entry = new ResourceEntry("Encounter", json, "POST", null);

        when(capabilityCache.canFhir("Encounter", "update")).thenReturn(true);
        when(fhirClient.send(eq("Encounter"), anyString(), eq("PUT")))
                .thenReturn(RoutingResult.success("Encounter", OPENMRS_UUID, "fhir", "updated", 200, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals("updated", results.get(0).status());

        // Verify the method was changed to PUT and the ID was replaced
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(fhirClient).send(eq("Encounter"), jsonCaptor.capture(), eq("PUT"));
        assertTrue(jsonCaptor.getValue().contains(OPENMRS_UUID),
                "Source ID should be replaced with OpenMRS UUID for PUT");
        assertFalse(jsonCaptor.getValue().contains("src-enc-001"),
                "Source ID should no longer be in the JSON");
    }

    @Test
    void shouldDetectUuidSourceIdAndCreateAsPost() {
        // Source sends an Encounter with a UUID-format source ID (not an OpenMRS UUID)
        String sourceUuid = "9a8e5398-64ee-4111-84a0-9e1e6e0a0121";
        String json = """
                {"resourceType":"Encounter","id":"%s","status":"inprogress"}
                """.formatted(sourceUuid);
        // BundleSplitter would have set method=PUT because the ID is UUID-format,
        // but ResourceRouter should override to POST since no mapping exists
        ResourceEntry entry = new ResourceEntry("Encounter", json, "PUT", null);

        when(capabilityCache.canFhir("Encounter", "create")).thenReturn(true);
        when(fhirClient.send(eq("Encounter"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Encounter", OPENMRS_UUID, "fhir", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertTrue(results.get(0).isSuccess());
        // Verify method was overridden from PUT to POST
        verify(fhirClient).send(eq("Encounter"), anyString(), eq("POST"));
        verify(fhirClient, never()).send(any(), any(), eq("PUT"));

        // Verify mapping was stored
        assertEquals(OPENMRS_UUID, mappingStore.getOpenMrsUuid("Encounter", sourceUuid).orElse(null));
    }

    @Test
    void shouldUpdateUuidSourceIdOnSecondRequest() {
        // First: source sends encounter with UUID source ID (create)
        String sourceUuid = "9a8e5398-64ee-4111-84a0-9e1e6e0a0121";
        mappingStore.store("Encounter", sourceUuid, OPENMRS_UUID);

        // Second: same encounter sent again with updated status
        String json = """
                {"resourceType":"Encounter","id":"%s","status":"finished"}
                """.formatted(sourceUuid);
        ResourceEntry entry = new ResourceEntry("Encounter", json, "PUT", null);

        when(capabilityCache.canFhir("Encounter", "update")).thenReturn(true);
        when(fhirClient.send(eq("Encounter"), anyString(), eq("PUT")))
                .thenReturn(RoutingResult.success("Encounter", OPENMRS_UUID, "fhir", "updated", 200, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertTrue(results.get(0).isSuccess());
        assertEquals("updated", results.get(0).status());

        // Verify the source UUID was replaced with the OpenMRS UUID
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(fhirClient).send(eq("Encounter"), jsonCaptor.capture(), eq("PUT"));
        assertTrue(jsonCaptor.getValue().contains(OPENMRS_UUID));
        assertFalse(jsonCaptor.getValue().contains(sourceUuid));
    }

    @Test
    void shouldDeleteUsingMapping() {
        mappingStore.store("Encounter", "src-enc-001", OPENMRS_UUID);

        String json = """
                {"resourceType":"Encounter","id":"src-enc-001"}
                """;
        ResourceEntry entry = new ResourceEntry("Encounter", json, "DELETE", null);

        when(capabilityCache.canFhir("Encounter", "delete")).thenReturn(true);
        when(fhirClient.send(eq("Encounter"), anyString(), eq("DELETE")))
                .thenReturn(RoutingResult.success("Encounter", OPENMRS_UUID, "fhir", "deleted", 200, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertTrue(results.get(0).isSuccess());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(fhirClient).send(eq("Encounter"), jsonCaptor.capture(), eq("DELETE"));
        assertTrue(jsonCaptor.getValue().contains(OPENMRS_UUID));
    }

    @Test
    void shouldFailDeleteForUnknownSourceId() {
        String json = """
                {"resourceType":"Encounter","id":"unknown-src-id"}
                """;
        ResourceEntry entry = new ResourceEntry("Encounter", json, "DELETE", null);

        List<RoutingResult> results = router.route(List.of(entry));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals(404, results.get(0).httpStatus());
        assertTrue(results.get(0).errorMessage().contains("no mapping found"));
    }

    @Test
    void shouldPassThroughResourceWithoutId() {
        // Resource with no ID — just POST as-is
        String json = """
                {"resourceType":"Observation","status":"final","code":{"coding":[{"code":"8480-6"}]}}
                """;
        ResourceEntry entry = new ResourceEntry("Observation", json, "POST", null);

        when(capabilityCache.canFhir("Observation", "create")).thenReturn(true);
        when(fhirClient.send(eq("Observation"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Observation", "obs-uuid", "fhir", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertTrue(results.get(0).isSuccess());
        assertEquals(0, mappingStore.size(), "No mapping stored when no source ID");
    }

    // ----- Routing -----

    @Test
    void shouldRouteViaRestWhenFhirLacksSupport() {
        String json = """
                {"resourceType":"ServiceRequest","id":"src-sr-001","status":"active"}
                """;
        ResourceEntry entry = new ResourceEntry("ServiceRequest", json, "POST", null);

        when(capabilityCache.canFhir("ServiceRequest", "create")).thenReturn(false);
        when(restClient.send(eq("ServiceRequest"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("ServiceRequest", "sr-uuid", "rest", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(entry));

        assertTrue(results.get(0).isSuccess());
        assertEquals("rest", results.get(0).route());
        verify(fhirClient, never()).send(any(), any(), any());
    }

    @Test
    void shouldRespectDependencyOrder() {
        // Send Observation before Patient — router should reorder so Patient goes first
        ResourceEntry obs = new ResourceEntry("Observation", """
                {"resourceType":"Observation","status":"final"}
                """, "POST", null);
        ResourceEntry patient = new ResourceEntry("Patient", """
                {"resourceType":"Patient","name":[{"family":"Doe"}]}
                """, "POST", null);

        when(capabilityCache.canFhir(anyString(), eq("create"))).thenReturn(true);
        when(fhirClient.send(eq("Patient"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Patient", "pat-uuid", "fhir", "created", 201, "{}"));
        when(fhirClient.send(eq("Observation"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Observation", "obs-uuid", "fhir", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(obs, patient));

        assertEquals(2, results.size());
        // Patient should be processed first
        assertEquals("Patient", results.get(0).resourceType());
        assertEquals("Observation", results.get(1).resourceType());
    }

    @Test
    void shouldResolveCrossReferencesForSourceIds() {
        // Bundle scenario: Patient with source ID, then Encounter referencing Patient/sourceId
        String patientJson = """
                {"resourceType":"Patient","id":"src-pat-001","name":[{"family":"Test"}]}
                """;
        String encounterJson = """
                {"resourceType":"Encounter","id":"src-enc-001","status":"finished",
                 "subject":{"reference":"Patient/src-pat-001"}}
                """;

        ResourceEntry patEntry = new ResourceEntry("Patient", patientJson, "POST", null);
        ResourceEntry encEntry = new ResourceEntry("Encounter", encounterJson, "POST", null);

        when(capabilityCache.canFhir(anyString(), eq("create"))).thenReturn(true);
        when(fhirClient.send(eq("Patient"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Patient", "openmrs-pat-uuid", "fhir", "created", 201, "{}"));
        when(fhirClient.send(eq("Encounter"), anyString(), eq("POST")))
                .thenReturn(RoutingResult.success("Encounter", "openmrs-enc-uuid", "fhir", "created", 201, "{}"));

        List<RoutingResult> results = router.route(List.of(patEntry, encEntry));

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());

        // Verify the Encounter's reference was resolved to the OpenMRS UUID
        ArgumentCaptor<String> encJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(fhirClient).send(eq("Encounter"), encJsonCaptor.capture(), eq("POST"));
        String sentEncJson = encJsonCaptor.getValue();
        assertTrue(sentEncJson.contains("Patient/openmrs-pat-uuid"),
                "Encounter should reference the OpenMRS patient UUID, got: " + sentEncJson);
        assertFalse(sentEncJson.contains("src-pat-001"),
                "Source patient ID should be resolved away");
    }

    @Test
    void shouldWorkForAllResourceTypes() {
        // Verify the mapping logic works for various resource types, not just Encounter
        String[] types = {"Patient", "Encounter", "Observation", "Condition", "AllergyIntolerance"};

        when(capabilityCache.canFhir(anyString(), eq("create"))).thenReturn(true);

        for (String type : types) {
            String sourceId = "src-" + type.toLowerCase() + "-001";
            String json = """
                    {"resourceType":"%s","id":"%s"}
                    """.formatted(type, sourceId);
            ResourceEntry entry = new ResourceEntry(type, json, "POST", null);

            String openmrsUuid = "openmrs-" + type.toLowerCase() + "-uuid";
            when(fhirClient.send(eq(type), anyString(), eq("POST")))
                    .thenReturn(RoutingResult.success(type, openmrsUuid, "fhir", "created", 201, "{}"));

            router.route(List.of(entry));

            assertEquals(openmrsUuid, mappingStore.getOpenMrsUuid(type, sourceId).orElse(null),
                    "Mapping should be stored for " + type);
        }

        assertEquals(types.length, mappingStore.size());
    }
}
