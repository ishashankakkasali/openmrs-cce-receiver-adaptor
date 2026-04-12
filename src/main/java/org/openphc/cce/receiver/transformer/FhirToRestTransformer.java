package org.openphc.cce.receiver.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openphc.cce.receiver.exception.ResourceTransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Transforms FHIR R4 resources into OpenMRS REST API v1 payloads.
 *
 * <p>This is used for resources where the OpenMRS FHIR endpoint does NOT
 * support the required operation (e.g., ServiceRequest create, MedicationRequest create).
 *
 * <p>Currently handles:
 * <ul>
 *   <li>{@code ServiceRequest} → OpenMRS {@code testorder}</li>
 *   <li>{@code MedicationRequest} → OpenMRS {@code order} (Drug Order)</li>
 * </ul>
 *
 * <p>Additional transformers can be added for other resource types as needed.
 */
@Component
public class FhirToRestTransformer {

    private static final Logger log = LoggerFactory.getLogger(FhirToRestTransformer.class);

    private final ObjectMapper objectMapper;

    public FhirToRestTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Transforms a FHIR resource into an OpenMRS REST payload.
     *
     * @param resourceType FHIR resource type
     * @param fhirJson     the FHIR resource JSON
     * @return a RestPayload containing the REST endpoint path and transformed body
     */
    public RestPayload transform(String resourceType, String fhirJson) {
        return switch (resourceType) {
            case "ServiceRequest" -> transformServiceRequest(fhirJson);
            case "MedicationRequest" -> transformMedicationRequest(fhirJson);
            default -> transformGeneric(resourceType, fhirJson);
        };
    }

    /**
     * Transforms a FHIR ServiceRequest into an OpenMRS TestOrder REST payload.
     *
     * <p>FHIR ServiceRequest → OpenMRS TestOrder mapping:
     * <ul>
     *   <li>code.coding[0].code → concept (UUID)</li>
     *   <li>encounter.reference → encounter (UUID)</li>
     *   <li>subject.reference → patient (UUID)</li>
     *   <li>requester.reference → orderer (UUID)</li>
     *   <li>intent → action: "NEW" for "order", "DISCONTINUE" for "revoke"</li>
     *   <li>priority → urgency: "STAT" for "stat", "ROUTINE" for "routine"</li>
     * </ul>
     */
    private RestPayload transformServiceRequest(String fhirJson) {
        try {
            JsonNode fhir = objectMapper.readTree(fhirJson);
            ObjectNode rest = objectMapper.createObjectNode();

            // Type: testorder
            rest.put("type", "testorder");

            // Concept from code.coding[0].code
            JsonNode coding = fhir.path("code").path("coding");
            if (coding.isArray() && !coding.isEmpty()) {
                rest.put("concept", coding.get(0).path("code").asText());
            }

            // Encounter reference
            String encounterRef = extractReference(fhir, "encounter");
            if (encounterRef != null) {
                rest.put("encounter", encounterRef);
            }

            // Patient reference
            String patientRef = extractReference(fhir, "subject");
            if (patientRef != null) {
                rest.put("patient", patientRef);
            }

            // Orderer (requester) reference
            String ordererRef = extractReference(fhir, "requester");
            if (ordererRef != null) {
                rest.put("orderer", ordererRef);
            }

            // Care setting — default to OUTPATIENT
            rest.put("careSetting", "OUTPATIENT");

            // Action from intent
            String intent = fhir.path("intent").asText("order");
            rest.put("action", mapIntentToAction(intent));

            // Urgency from priority
            String priority = fhir.path("priority").asText("routine");
            rest.put("urgency", mapPriorityToUrgency(priority));

            log.debug("Transformed ServiceRequest to TestOrder: {}", rest);
            return new RestPayload("order", rest.toString());

        } catch (Exception e) {
            throw new ResourceTransformException("ServiceRequest",
                    "Failed to transform ServiceRequest to TestOrder: " + e.getMessage(), e);
        }
    }

    /**
     * Transforms a FHIR MedicationRequest into an OpenMRS DrugOrder REST payload.
     *
     * <p>FHIR MedicationRequest → OpenMRS DrugOrder mapping:
     * <ul>
     *   <li>medicationCodeableConcept.coding[0].code → concept (UUID)</li>
     *   <li>medicationReference.reference → drug (UUID)</li>
     *   <li>encounter.reference → encounter (UUID)</li>
     *   <li>subject.reference → patient (UUID)</li>
     *   <li>requester.reference → orderer (UUID)</li>
     *   <li>dosageInstruction[0].doseAndRate[0].doseQuantity.value → dose</li>
     *   <li>dosageInstruction[0].doseAndRate[0].doseQuantity.unit → doseUnits (UUID)</li>
     *   <li>dosageInstruction[0].route.coding[0].code → route (UUID)</li>
     *   <li>dosageInstruction[0].timing.code.coding[0].code → frequency (UUID)</li>
     *   <li>dispenseRequest.quantity.value → quantity</li>
     *   <li>dispenseRequest.expectedSupplyDuration.value → duration</li>
     * </ul>
     */
    private RestPayload transformMedicationRequest(String fhirJson) {
        try {
            JsonNode fhir = objectMapper.readTree(fhirJson);
            ObjectNode rest = objectMapper.createObjectNode();

            // Type: drugorder
            rest.put("type", "drugorder");

            // Concept from medicationCodeableConcept or medicationReference
            JsonNode medConcept = fhir.path("medicationCodeableConcept").path("coding");
            if (medConcept.isArray() && !medConcept.isEmpty()) {
                rest.put("concept", medConcept.get(0).path("code").asText());
            }

            // Drug from medicationReference
            String drugRef = extractReference(fhir, "medicationReference");
            if (drugRef != null) {
                rest.put("drug", drugRef);
            }

            // Encounter reference
            String encounterRef = extractReference(fhir, "encounter");
            if (encounterRef != null) {
                rest.put("encounter", encounterRef);
            }

            // Patient reference
            String patientRef = extractReference(fhir, "subject");
            if (patientRef != null) {
                rest.put("patient", patientRef);
            }

            // Orderer reference
            String ordererRef = extractReference(fhir, "requester");
            if (ordererRef != null) {
                rest.put("orderer", ordererRef);
            }

            // Care setting — default to OUTPATIENT
            rest.put("careSetting", "OUTPATIENT");

            // Action from intent
            String intent = fhir.path("intent").asText("order");
            rest.put("action", mapIntentToAction(intent));

            // Dosage instructions
            JsonNode dosage = fhir.path("dosageInstruction");
            if (dosage.isArray() && !dosage.isEmpty()) {
                JsonNode firstDosage = dosage.get(0);

                // Dose
                JsonNode doseAndRate = firstDosage.path("doseAndRate");
                if (doseAndRate.isArray() && !doseAndRate.isEmpty()) {
                    JsonNode doseQuantity = doseAndRate.get(0).path("doseQuantity");
                    if (!doseQuantity.isMissingNode()) {
                        rest.put("dose", doseQuantity.path("value").asDouble());
                        rest.put("doseUnits", doseQuantity.path("unit").asText());
                    }
                }

                // Route
                JsonNode route = firstDosage.path("route").path("coding");
                if (route.isArray() && !route.isEmpty()) {
                    rest.put("route", route.get(0).path("code").asText());
                }

                // Frequency from timing
                JsonNode timingCode = firstDosage.path("timing").path("code").path("coding");
                if (timingCode.isArray() && !timingCode.isEmpty()) {
                    rest.put("frequency", timingCode.get(0).path("code").asText());
                }
            }

            // Dispense details
            JsonNode dispense = fhir.path("dispenseRequest");
            if (!dispense.isMissingNode()) {
                JsonNode quantity = dispense.path("quantity");
                if (!quantity.isMissingNode()) {
                    rest.put("quantity", quantity.path("value").asDouble());
                    rest.put("quantityUnits", quantity.path("unit").asText());
                }
                JsonNode duration = dispense.path("expectedSupplyDuration");
                if (!duration.isMissingNode()) {
                    rest.put("duration", duration.path("value").asInt());
                    rest.put("durationUnits", duration.path("unit").asText());
                }
            }

            // Number of refills
            if (fhir.has("dispenseRequest") && fhir.path("dispenseRequest").has("numberOfRepeatsAllowed")) {
                rest.put("numRefills", fhir.path("dispenseRequest").path("numberOfRepeatsAllowed").asInt());
            }

            log.debug("Transformed MedicationRequest to DrugOrder: {}", rest);
            return new RestPayload("order", rest.toString());

        } catch (Exception e) {
            throw new ResourceTransformException("MedicationRequest",
                    "Failed to transform MedicationRequest to DrugOrder: " + e.getMessage(), e);
        }
    }

    /**
     * Generic fallback transformation — passes FHIR JSON through with minimal mapping.
     * Used for resource types that don't have a specialized transformer.
     */
    private RestPayload transformGeneric(String resourceType, String fhirJson) {
        log.warn("No specialized transformer for {}. Using generic pass-through to REST.", resourceType);

        // Map FHIR resourceType to OpenMRS REST endpoint
        String endpoint = switch (resourceType) {
            case "Patient" -> "patient";
            case "Encounter" -> "encounter";
            case "Observation" -> "obs";
            case "Condition" -> "condition";
            case "AllergyIntolerance" -> "allergy";
            case "Location" -> "location";
            case "Practitioner" -> "provider";
            case "Medication" -> "drug";
            default -> resourceType.toLowerCase();
        };

        return new RestPayload(endpoint, fhirJson);
    }

    // ==================== Utility methods ====================

    /**
     * Extracts a UUID from a FHIR reference field (e.g., "Patient/abc-123" → "abc-123").
     */
    private String extractReference(JsonNode fhir, String fieldName) {
        String reference = fhir.path(fieldName).path("reference").asText(null);
        if (reference != null && reference.contains("/")) {
            return reference.substring(reference.lastIndexOf('/') + 1);
        }
        return reference;
    }

    /**
     * Maps FHIR ServiceRequest.intent to OpenMRS Order action.
     */
    private String mapIntentToAction(String intent) {
        return switch (intent.toLowerCase()) {
            case "order", "original-order" -> "NEW";
            case "reflex-order" -> "NEW";
            case "filler-order" -> "NEW";
            case "instance-order" -> "NEW";
            case "option" -> "NEW";
            default -> "NEW";
        };
    }

    /**
     * Maps FHIR ServiceRequest.priority to OpenMRS Order urgency.
     */
    private String mapPriorityToUrgency(String priority) {
        return switch (priority.toLowerCase()) {
            case "stat" -> "STAT";
            case "asap" -> "STAT";
            case "urgent" -> "STAT";
            default -> "ROUTINE";
        };
    }

    /**
     * Encapsulates the REST endpoint and transformed payload body.
     *
     * @param endpoint REST API path segment (e.g. "order", "patient")
     * @param body     transformed JSON body
     */
    public record RestPayload(String endpoint, String body) {}
}
