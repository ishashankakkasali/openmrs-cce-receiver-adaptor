package org.openphc.cce.receiver.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FhirToRestTransformer}.
 */
class FhirToRestTransformerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FhirToRestTransformer transformer = new FhirToRestTransformer(objectMapper);

    @Test
    void shouldTransformServiceRequestToTestOrder() throws Exception {
        String fhirJson = """
                {
                    "resourceType": "ServiceRequest",
                    "status": "active",
                    "intent": "order",
                    "priority": "routine",
                    "code": {
                        "coding": [{"system": "http://loinc.org", "code": "concept-uuid-123", "display": "Blood Glucose"}]
                    },
                    "subject": {"reference": "Patient/patient-uuid-456"},
                    "encounter": {"reference": "Encounter/encounter-uuid-789"},
                    "requester": {"reference": "Practitioner/provider-uuid-001"}
                }
                """;

        FhirToRestTransformer.RestPayload payload = transformer.transform("ServiceRequest", fhirJson);

        assertEquals("order", payload.endpoint());

        JsonNode body = objectMapper.readTree(payload.body());
        assertEquals("testorder", body.path("type").asText());
        assertEquals("concept-uuid-123", body.path("concept").asText());
        assertEquals("patient-uuid-456", body.path("patient").asText());
        assertEquals("encounter-uuid-789", body.path("encounter").asText());
        assertEquals("provider-uuid-001", body.path("orderer").asText());
        assertEquals("OUTPATIENT", body.path("careSetting").asText());
        assertEquals("NEW", body.path("action").asText());
        assertEquals("ROUTINE", body.path("urgency").asText());
    }

    @Test
    void shouldTransformMedicationRequestToDrugOrder() throws Exception {
        String fhirJson = """
                {
                    "resourceType": "MedicationRequest",
                    "status": "active",
                    "intent": "order",
                    "medicationCodeableConcept": {
                        "coding": [{"code": "drug-concept-uuid"}]
                    },
                    "subject": {"reference": "Patient/patient-uuid"},
                    "encounter": {"reference": "Encounter/enc-uuid"},
                    "requester": {"reference": "Practitioner/prov-uuid"},
                    "dosageInstruction": [{
                        "doseAndRate": [{
                            "doseQuantity": {"value": 500, "unit": "mg"}
                        }],
                        "route": {
                            "coding": [{"code": "oral-route-uuid"}]
                        },
                        "timing": {
                            "code": {
                                "coding": [{"code": "once-daily-uuid"}]
                            }
                        }
                    }],
                    "dispenseRequest": {
                        "quantity": {"value": 30, "unit": "tablets"},
                        "expectedSupplyDuration": {"value": 30, "unit": "days"},
                        "numberOfRepeatsAllowed": 2
                    }
                }
                """;

        FhirToRestTransformer.RestPayload payload = transformer.transform("MedicationRequest", fhirJson);

        assertEquals("order", payload.endpoint());

        JsonNode body = objectMapper.readTree(payload.body());
        assertEquals("drugorder", body.path("type").asText());
        assertEquals("drug-concept-uuid", body.path("concept").asText());
        assertEquals("patient-uuid", body.path("patient").asText());
        assertEquals("enc-uuid", body.path("encounter").asText());
        assertEquals("prov-uuid", body.path("orderer").asText());
        assertEquals(500.0, body.path("dose").asDouble());
        assertEquals("mg", body.path("doseUnits").asText());
        assertEquals("oral-route-uuid", body.path("route").asText());
        assertEquals("once-daily-uuid", body.path("frequency").asText());
        assertEquals(30.0, body.path("quantity").asDouble());
        assertEquals(30, body.path("duration").asInt());
        assertEquals(2, body.path("numRefills").asInt());
    }

    @Test
    void shouldTransformStatPriorityToStatUrgency() throws Exception {
        String fhirJson = """
                {
                    "resourceType": "ServiceRequest",
                    "status": "active",
                    "intent": "order",
                    "priority": "stat",
                    "code": {"coding": [{"code": "test-uuid"}]},
                    "subject": {"reference": "Patient/p-1"},
                    "encounter": {"reference": "Encounter/e-1"}
                }
                """;

        FhirToRestTransformer.RestPayload payload = transformer.transform("ServiceRequest", fhirJson);
        JsonNode body = objectMapper.readTree(payload.body());
        assertEquals("STAT", body.path("urgency").asText());
    }

    @Test
    void shouldUseGenericFallbackForUnknownType() {
        FhirToRestTransformer.RestPayload payload = transformer.transform("Observation", "{}");

        assertEquals("obs", payload.endpoint());
    }
}
