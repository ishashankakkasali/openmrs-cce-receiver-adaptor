package org.openphc.cce.receiver.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.fhir.FhirResourceParser;
import org.openphc.cce.receiver.model.ResourceEntry;

import ca.uhn.fhir.context.FhirContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BundleSplitter}.
 */
class BundleSplitterTest {

    private final FhirContext fhirContext = FhirContext.forR4();
    private final FhirResourceParser parser = new FhirResourceParser(fhirContext);
    private final BundleSplitter splitter = new BundleSplitter(parser);

    @Test
    void shouldSplitStandalonePatient() {
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "name": [{"family": "Doe", "given": ["John"]}],
                    "gender": "male"
                }
                """;

        List<ResourceEntry> entries = splitter.split(patientJson);

        assertEquals(1, entries.size());
        assertEquals("Patient", entries.get(0).resourceType());
        assertEquals("POST", entries.get(0).method());
        assertNull(entries.get(0).fullUrl());
    }

    @Test
    void shouldSplitStandalonePatientWithIdAsUpdate() {
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "id": "618ee1e2-0303-42e8-9da2-bd2570865011",
                    "name": [{"family": "Doe", "given": ["John"]}],
                    "gender": "male"
                }
                """;

        List<ResourceEntry> entries = splitter.split(patientJson);

        assertEquals(1, entries.size());
        assertEquals("PUT", entries.get(0).method());
    }

    @Test
    void shouldSplitStandalonePatientWithNonUuidIdAsPost() {
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "id": "251119-0001-4106",
                    "name": [{"family": "Doe", "given": ["John"]}],
                    "gender": "male"
                }
                """;

        List<ResourceEntry> entries = splitter.split(patientJson);

        assertEquals(1, entries.size());
        assertEquals("POST", entries.get(0).method(),
                "Non-UUID IDs (like RHIE source IDs) should be treated as POST, not PUT");
    }

    @Test
    void shouldSplitTransactionBundle() {
        String bundleJson = """
                {
                    "resourceType": "Bundle",
                    "type": "transaction",
                    "entry": [
                        {
                            "fullUrl": "urn:uuid:patient-1",
                            "resource": {
                                "resourceType": "Patient",
                                "name": [{"family": "Doe"}]
                            },
                            "request": {"method": "POST", "url": "Patient"}
                        },
                        {
                            "fullUrl": "urn:uuid:encounter-1",
                            "resource": {
                                "resourceType": "Encounter",
                                "status": "finished",
                                "class": {"code": "AMB"},
                                "subject": {"reference": "urn:uuid:patient-1"}
                            },
                            "request": {"method": "POST", "url": "Encounter"}
                        },
                        {
                            "fullUrl": "urn:uuid:obs-1",
                            "resource": {
                                "resourceType": "Observation",
                                "status": "final",
                                "code": {"coding": [{"code": "8480-6"}]},
                                "valueQuantity": {"value": 120, "unit": "mmHg"}
                            },
                            "request": {"method": "POST", "url": "Observation"}
                        }
                    ]
                }
                """;

        List<ResourceEntry> entries = splitter.split(bundleJson);

        assertEquals(3, entries.size());
        assertEquals("Patient", entries.get(0).resourceType());
        assertEquals("Encounter", entries.get(1).resourceType());
        assertEquals("Observation", entries.get(2).resourceType());
        assertEquals("POST", entries.get(0).method());
        assertEquals("urn:uuid:patient-1", entries.get(0).fullUrl());
    }

    @Test
    void shouldMapInteractionCodes() {
        ResourceEntry postEntry = new ResourceEntry("Patient", "{}", "POST", null);
        ResourceEntry putEntry = new ResourceEntry("Patient", "{}", "PUT", null);
        ResourceEntry deleteEntry = new ResourceEntry("Patient", "{}", "DELETE", null);

        assertEquals("create", postEntry.toInteractionCode());
        assertEquals("update", putEntry.toInteractionCode());
        assertEquals("delete", deleteEntry.toInteractionCode());
    }

    @Test
    void shouldHandleEmptyBundle() {
        String bundleJson = """
                {
                    "resourceType": "Bundle",
                    "type": "transaction",
                    "entry": []
                }
                """;

        List<ResourceEntry> entries = splitter.split(bundleJson);
        assertTrue(entries.isEmpty());
    }

    @Test
    void shouldFlattenNestedBundles() {
        String bundleJson = """
                {
                    "resourceType": "Bundle",
                    "type": "collection",
                    "entry": [
                        {
                            "fullUrl": "urn:uuid:patient-1",
                            "resource": {
                                "resourceType": "Patient",
                                "name": [{"family": "Doe"}]
                            },
                            "request": {"method": "POST", "url": "Patient"}
                        },
                        {
                            "resource": {
                                "resourceType": "Bundle",
                                "type": "transaction",
                                "entry": [
                                    {
                                        "fullUrl": "urn:uuid:obs-1",
                                        "resource": {
                                            "resourceType": "Observation",
                                            "status": "final",
                                            "code": {"coding": [{"code": "8480-6"}]},
                                            "valueQuantity": {"value": 120, "unit": "mmHg"}
                                        },
                                        "request": {"method": "POST", "url": "Observation"}
                                    },
                                    {
                                        "fullUrl": "urn:uuid:enc-1",
                                        "resource": {
                                            "resourceType": "Encounter",
                                            "status": "finished",
                                            "class": {"code": "AMB"}
                                        },
                                        "request": {"method": "POST", "url": "Encounter"}
                                    }
                                ]
                            }
                        }
                    ]
                }
                """;

        List<ResourceEntry> entries = splitter.split(bundleJson);

        assertEquals(3, entries.size(), "Nested Bundle entries should be flattened");
        assertEquals("Patient", entries.get(0).resourceType());
        assertEquals("Observation", entries.get(1).resourceType());
        assertEquals("Encounter", entries.get(2).resourceType());
        // Nested Bundle itself should NOT appear as an entry
        assertTrue(entries.stream().noneMatch(e -> "Bundle".equals(e.resourceType())));
    }
}
