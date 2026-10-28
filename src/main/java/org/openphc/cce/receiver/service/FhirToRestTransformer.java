package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.openphc.cce.receiver.exception.ResourceTransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class FhirToRestTransformer {

    private static final Logger log = LoggerFactory.getLogger(FhirToRestTransformer.class);
    private static final DateTimeFormatter OPENMRS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final ConceptResolver conceptResolver;
    private final DiscoveredConfig discoveredConfig;
    private final OpenMrsConfigDiscovery configDiscovery;
    private final RestClient restClient;
    private final PatientProvisioner patientProvisioner;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProviderResolver providerResolver;

    public FhirToRestTransformer(ConceptResolver conceptResolver,
                                 DiscoveredConfig discoveredConfig,
                                 OpenMrsConfigDiscovery configDiscovery,
                                 @Qualifier("openmrsRestClient") RestClient restClient,
                                 PatientProvisioner patientProvisioner,
                                 ProviderResolver providerResolver) {
        this.conceptResolver = conceptResolver;
        this.discoveredConfig = discoveredConfig;
        this.configDiscovery = configDiscovery;
        this.restClient = restClient;
        this.patientProvisioner = patientProvisioner;
        this.providerResolver = providerResolver;
    }

    public TransformResult transform(ObjectNode fhirResource, String resourceType) {
        return switch (resourceType) {
            case "Patient" -> transformPatient(fhirResource);
            case "Encounter" -> transformEncounter(fhirResource);
            case "Observation" -> transformObservation(fhirResource);
            case "Condition" -> transformCondition(fhirResource);
            case "AllergyIntolerance" -> transformAllergyIntolerance(fhirResource);
            case "ServiceRequest" -> transformServiceRequest(fhirResource);
            case "MedicationRequest" -> transformMedicationRequest(fhirResource);
            case "Location" -> transformLocation(fhirResource);
            case "Practitioner" -> transformPractitioner(fhirResource);
            case "Medication" -> transformMedication(fhirResource);
            case "Immunization" -> transformImmunization(fhirResource);
            default -> throw new ResourceTransformException(
                    "No REST transformer for resource type: " + resourceType);
        };
    }

    private TransformResult transformPatient(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Name
        JsonNode nameArray = fhir.path("name");
        if (nameArray.isArray() && !nameArray.isEmpty()) {
            JsonNode name = nameArray.get(0);
            ObjectNode personName = objectMapper.createObjectNode();
            personName.put("givenName", name.path("given").isArray() && !name.path("given").isEmpty()
                    ? name.path("given").get(0).asText("") : "");
            // Fall back to text field or givenName when family is absent
            String familyName = name.path("family").asText("");
            if (familyName.isBlank()) {
                familyName = name.path("text").asText("");
            }
            if (familyName.isBlank() && name.path("given").isArray() && name.path("given").size() > 1) {
                familyName = name.path("given").get(1).asText("");
            }
            if (familyName.isBlank()) {
                familyName = "Unknown";
            }
            personName.put("familyName", familyName);
            if (name.path("given").isArray() && name.path("given").size() > 1) {
                personName.put("middleName", name.path("given").get(1).asText(""));
            }
            ObjectNode person = rest.putObject("person");
            person.putArray("names").add(personName);

            // Gender
            String gender = fhir.path("gender").asText("");
            if (!gender.isEmpty()) {
                person.put("gender", gender.substring(0, 1).toUpperCase());
            }

            // Birth date
            String birthDate = fhir.path("birthDate").asText("");
            if (!birthDate.isEmpty()) {
                person.put("birthdate", birthDate);
            }

            // Address
            JsonNode addressArray = fhir.path("address");
            if (addressArray.isArray() && !addressArray.isEmpty()) {
                ArrayNode addresses = person.putArray("addresses");
                for (JsonNode addr : addressArray) {
                    ObjectNode restAddr = objectMapper.createObjectNode();
                    String address1 = addr.path("line").isArray() && !addr.path("line").isEmpty()
                            ? addr.path("line").get(0).asText("") : "";
                    if (address1.isBlank()) {
                        address1 = addr.path("text").asText("");
                    }
                    restAddr.put("address1", address1);
                    restAddr.put("cityVillage", addr.path("city").asText(""));
                    restAddr.put("stateProvince", addr.path("state").asText(""));
                    restAddr.put("country", addr.path("country").asText(""));
                    restAddr.put("postalCode", addr.path("postalCode").asText(""));
                    addresses.add(restAddr);
                }
            }

            // Phone and email from telecom
            JsonNode telecom = fhir.path("telecom");
            if (telecom.isArray()) {
                ArrayNode attributes = person.putArray("attributes");
                for (JsonNode contact : telecom) {
                    String system = contact.path("system").asText("");
                    String value = contact.path("value").asText("");
                    if ("phone".equals(system) && discoveredConfig.getPhoneNumberAttributeTypeUuid() != null) {
                        ObjectNode attr = objectMapper.createObjectNode();
                        attr.put("attributeType", discoveredConfig.getPhoneNumberAttributeTypeUuid());
                        attr.put("value", value);
                        attributes.add(attr);
                    } else if ("email".equals(system) && discoveredConfig.getEmailAttributeTypeUuid() != null) {
                        ObjectNode attr = objectMapper.createObjectNode();
                        attr.put("attributeType", discoveredConfig.getEmailAttributeTypeUuid());
                        attr.put("value", value);
                        attributes.add(attr);
                    }
                }
            }
        }

        // Identifiers
        JsonNode identifiers = fhir.path("identifier");
        ArrayNode restIdentifiers = rest.putArray("identifiers");
        boolean hasOpenMrsId = false;
        String primaryIdTypeName = discoveredConfig.getIdentifierTypeName();
        String primaryIdTypeUuid = discoveredConfig.getIdentifierTypeUuid();

        if (identifiers.isArray() && !identifiers.isEmpty()) {
            for (JsonNode id : identifiers) {
                String value = id.path("value").asText(id.path("identifier").asText(""));
                if (value.isBlank()) continue;

                ObjectNode restId = objectMapper.createObjectNode();
                restId.put("identifier", value);

                // Resolve identifier type: explicit UUID > type.coding[0].code > type.text > system URI
                String typeUuid = null;

                // 1. Explicit identifierType.uuid (pre-resolved)
                JsonNode idType = id.path("identifierType");
                if (!idType.isMissingNode() && idType.has("uuid")) {
                    typeUuid = idType.path("uuid").asText(null);
                }

                // 2. FHIR type.coding[0].code (may contain UUID or type name)
                if (typeUuid == null) {
                    JsonNode typeCoding = id.path("type").path("coding");
                    if (typeCoding.isArray() && !typeCoding.isEmpty()) {
                        String code = typeCoding.get(0).path("code").asText("");
                        if (!code.isBlank()) {
                            // Check if it's a UUID already in the cache
                            if (isUuid(code)) {
                                typeUuid = code;
                            } else {
                                typeUuid = discoveredConfig.getIdentifierTypeUuids().get(code);
                            }
                        }
                    }
                }

                // 3. FHIR type.text (identifier type name)
                if (typeUuid == null) {
                    String typeText = id.path("type").path("text").asText("");
                    if (!typeText.isBlank()) {
                        typeUuid = configDiscovery.ensureIdentifierTypeExists(typeText);
                    }
                }

                // 4. FHIR system URI → extract type name from last path segment → ensure type exists
                if (typeUuid == null) {
                    String system = id.path("system").asText("");
                    if (!system.isBlank()) {
                        String typeName = extractTypeNameFromSystem(system);
                        if (typeName != null) {
                            typeUuid = configDiscovery.ensureIdentifierTypeExists(typeName);
                        }
                    }
                }

                if (typeUuid != null) {
                    restId.set("identifierType", objectMapper.createObjectNode().put("uuid", typeUuid));
                    // Track if this is the primary OpenMRS ID
                    if (typeUuid.equals(primaryIdTypeUuid)) {
                        hasOpenMrsId = true;
                    }
                } else {
                    log.warn("Could not resolve identifier type for identifier value '{}' — skipping", value);
                    continue;
                }

                // Preferred: "official" use → preferred
                restId.put("preferred", "official".equalsIgnoreCase(id.path("use").asText("")));

                // Location: use identifier-level location or fall back to configured location
                JsonNode location = id.path("location");
                String locUuid = null;
                if (!location.isMissingNode() && location.has("uuid")) {
                    locUuid = location.path("uuid").asText();
                } else if (discoveredConfig.getLocationUuid() != null) {
                    locUuid = discoveredConfig.getLocationUuid();
                }
                if (locUuid != null) {
                    restId.set("location", objectMapper.createObjectNode().put("uuid", locUuid));
                }

                restIdentifiers.add(restId);
            }
        }

        // Auto-generate OpenMRS ID if not present in the incoming identifiers
        if (!hasOpenMrsId && primaryIdTypeUuid != null) {
            String generatedId = generateOpenMrsId();
            if (generatedId != null) {
                ObjectNode openMrsIdEntry = objectMapper.createObjectNode();
                openMrsIdEntry.put("identifier", generatedId);
                openMrsIdEntry.set("identifierType", objectMapper.createObjectNode().put("uuid", primaryIdTypeUuid));
                openMrsIdEntry.put("preferred", true);
                if (discoveredConfig.getLocationUuid() != null) {
                    openMrsIdEntry.set("location", objectMapper.createObjectNode().put("uuid", discoveredConfig.getLocationUuid()));
                }
                restIdentifiers.add(openMrsIdEntry);
                log.info("Auto-generated OpenMRS ID '{}' for new patient", generatedId);
            }
        }

        return new TransformResult("/patient", rest.toString());
    }

    private TransformResult transformEncounter(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Patient
        String patientUuid = extractUuidFromReference(fhir.path("subject").path("reference").asText(""));
        if (patientUuid != null) rest.put("patient", patientUuid);

        // Encounter datetime
        String datetime = fhir.path("period").path("start").asText("");
        if (!datetime.isBlank()) rest.put("encounterDatetime", datetime);

        // Encounter type
        JsonNode typeArray = fhir.path("type");
        if (typeArray.isArray() && !typeArray.isEmpty()) {
            JsonNode firstType = typeArray.get(0);
            String conceptUuid = conceptResolver.resolveConceptUuid(firstType);
            if (conceptUuid != null) {
                // Look up encounter type by concept
                String encounterTypeUuid = resolveEncounterType(firstType);
                if (encounterTypeUuid != null) rest.put("encounterType", encounterTypeUuid);
            }
        }

        // Visit
        String visitUuid = fhir.path("visit").asText("");
        if (!visitUuid.isBlank()) rest.put("visit", visitUuid);

        // Location
        JsonNode locationArray = fhir.path("location");
        if (locationArray.isArray() && !locationArray.isEmpty()) {
            String locUuid = extractUuidFromReference(
                    locationArray.get(0).path("location").path("reference").asText(""));
            if (locUuid != null) rest.put("location", locUuid);
        }

        // Providers
        JsonNode participants = fhir.path("participant");
        if (participants.isArray()) {
            ArrayNode encounterProviders = rest.putArray("encounterProviders");
            for (JsonNode participant : participants) {
                String providerRef = participant.path("individual").path("reference").asText("");
                String providerUuid = extractUuidFromReference(providerRef);
                if (providerUuid != null) {
                    ObjectNode ep = objectMapper.createObjectNode();
                    ep.put("provider", providerUuid);
                    encounterProviders.add(ep);
                }
            }
        }

        return new TransformResult("/encounter", rest.toString());
    }

    private TransformResult transformObservation(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Person
        String personUuid = extractUuidFromReference(fhir.path("subject").path("reference").asText(""));
        if (personUuid != null) rest.put("person", personUuid);

        // Encounter
        String encounterUuid = extractUuidFromReference(fhir.path("encounter").path("reference").asText(""));
        if (encounterUuid != null) rest.put("encounter", encounterUuid);

        // Concept
        String conceptUuid = conceptResolver.resolveConceptUuid(fhir.path("code"));
        if (conceptUuid != null) rest.put("concept", conceptUuid);

        // Observation datetime
        String obsDatetime = fhir.path("effectiveDateTime").asText("");
        if (!obsDatetime.isBlank()) rest.put("obsDatetime", obsDatetime);

        // Value
        if (fhir.has("valueQuantity")) {
            rest.put("value", fhir.path("valueQuantity").path("value").asDouble());
        } else if (fhir.has("valueString")) {
            rest.put("value", fhir.path("valueString").asText());
        } else if (fhir.has("valueCodeableConcept")) {
            String valueUuid = conceptResolver.resolveConceptUuid(fhir.path("valueCodeableConcept"));
            if (valueUuid != null) rest.put("value", valueUuid);
        } else if (fhir.has("valueBoolean")) {
            rest.put("value", fhir.path("valueBoolean").asBoolean());
        } else if (fhir.has("valueDateTime")) {
            rest.put("value", fhir.path("valueDateTime").asText());
        } else if (fhir.has("valueInteger")) {
            rest.put("value", fhir.path("valueInteger").asInt());
        }

        // Group members (components)
        JsonNode components = fhir.path("component");
        if (components.isArray() && !components.isEmpty()) {
            ArrayNode groupMembers = rest.putArray("groupMembers");
            for (JsonNode comp : components) {
                ObjectNode member = objectMapper.createObjectNode();
                String compConcept = conceptResolver.resolveConceptUuid(comp.path("code"));
                if (compConcept != null) member.put("concept", compConcept);
                if (personUuid != null) member.put("person", personUuid);
                if (encounterUuid != null) member.put("encounter", encounterUuid);
                if (!obsDatetime.isBlank()) member.put("obsDatetime", obsDatetime);

                if (comp.has("valueQuantity")) {
                    member.put("value", comp.path("valueQuantity").path("value").asDouble());
                } else if (comp.has("valueString")) {
                    member.put("value", comp.path("valueString").asText());
                } else if (comp.has("valueCodeableConcept")) {
                    String valUuid = conceptResolver.resolveConceptUuid(comp.path("valueCodeableConcept"));
                    if (valUuid != null) member.put("value", valUuid);
                }
                groupMembers.add(member);
            }
        }

        return new TransformResult("/obs", rest.toString());
    }

    private TransformResult transformCondition(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        String patientUuid = extractUuidFromReference(fhir.path("subject").path("reference").asText(""));
        if (patientUuid != null) rest.put("patient", patientUuid);

        String conceptUuid = conceptResolver.resolveConceptUuid(fhir.path("code"));
        if (conceptUuid != null) {
            ObjectNode condition = rest.putObject("condition");
            condition.put("coded", conceptUuid);
        }

        // Clinical status
        JsonNode clinicalStatus = fhir.path("clinicalStatus");
        if (!clinicalStatus.isMissingNode()) {
            JsonNode coding = clinicalStatus.path("coding");
            if (coding.isArray() && !coding.isEmpty()) {
                rest.put("clinicalStatus", coding.get(0).path("code").asText("ACTIVE").toUpperCase());
            }
        }

        // Onset date
        String onsetDate = fhir.path("onsetDateTime").asText("");
        if (!onsetDate.isBlank()) rest.put("onsetDate", onsetDate);

        return new TransformResult("/condition", rest.toString());
    }

    private TransformResult transformAllergyIntolerance(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        String patientUuid = extractUuidFromReference(fhir.path("patient").path("reference").asText(""));
        if (patientUuid != null) rest.put("patient", patientUuid);

        // Allergy coded allergen
        JsonNode codeNode = fhir.path("code");
        String allergenUuid = conceptResolver.resolveConceptUuid(codeNode);
        if (allergenUuid != null) {
            ObjectNode allergen = rest.putObject("allergen");
            allergen.put("allergenType", fhir.path("category").isArray() && !fhir.path("category").isEmpty()
                    ? mapAllergyCategory(fhir.path("category").get(0).asText("")) : "DRUG");
            ObjectNode codedAllergen = allergen.putObject("codedAllergen");
            codedAllergen.put("uuid", allergenUuid);
        }

        // Severity
        JsonNode reactions = fhir.path("reaction");
        if (reactions.isArray() && !reactions.isEmpty()) {
            JsonNode firstReaction = reactions.get(0);
            String severity = firstReaction.path("severity").asText("");
            if (!severity.isEmpty()) {
                ObjectNode severityObj = rest.putObject("severity");
                severityObj.put("uuid", mapAllergySeverity(severity));
            }

            // Reactions/manifestations
            JsonNode manifestations = firstReaction.path("manifestation");
            if (manifestations.isArray() && !manifestations.isEmpty()) {
                ArrayNode restReactions = rest.putArray("reactions");
                for (JsonNode m : manifestations) {
                    String reactionUuid = conceptResolver.resolveConceptUuid(m);
                    if (reactionUuid != null) {
                        ObjectNode reaction = objectMapper.createObjectNode();
                        ObjectNode reactionObj = reaction.putObject("reaction");
                        reactionObj.put("uuid", reactionUuid);
                        restReactions.add(reaction);
                    }
                }
            }
        }

        return new TransformResult("/allergy", rest.toString());
    }

    private TransformResult transformServiceRequest(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Determine order type: if ticket-type is "medicalReview" (SPICE referral), use Referral order type.
        // The Referral OrderType in OpenMRS is bound to the TestOrder Java class, so we always
        // post type="testorder" and supply the Referral OrderType UUID explicitly for referrals.
        boolean referral = isReferral(fhir);
        rest.put("type", "testorder");
        if (referral) {
            String referralOrderTypeUuid = discoverReferralOrderTypeUuid();
            if (referralOrderTypeUuid != null) {
                rest.put("orderType", referralOrderTypeUuid);
            }
        }

        // Patient — resolve UUID from subject (supports both reference-based and identifier-based subjects)
        String patientUuid = resolvePatientUuid(fhir.path("subject"));
        if (patientUuid != null) rest.put("patient", patientUuid);

        // Encounter — resolve UUID or create new one for the order
        String encounterRef = fhir.path("encounter").path("reference").asText("");
        String authoredOn = normalizeAuthoredOnToNotBeforeNow(fhir.path("authoredOn").asText(""));
        String encounterUuid = resolveEncounterUuid(encounterRef, patientUuid, authoredOn);
        if (encounterUuid != null) rest.put("encounter", encounterUuid);

        // Concept — handle SPICE gap (empty code.coding)
        String conceptUuid = null;
        JsonNode code = fhir.path("code");
        if (!code.isMissingNode()) {
            JsonNode coding = code.path("coding");
            if (coding.isArray() && !coding.isEmpty() && coding.get(0).has("code")) {
                conceptUuid = conceptResolver.resolveConceptUuid(code);
            }
        }
        if (conceptUuid == null) {
            // SPICE Gap 1: resolve from identifier category
            conceptUuid = resolveConceptFromSpiceCategory(fhir);
        }
        if (conceptUuid != null) rest.put("concept", conceptUuid);

        // Orderer — handle SPICE gap (Organization requester)
        String ordererUuid = resolveOrderer(fhir);
        if (ordererUuid != null) rest.put("orderer", ordererUuid);

        // Urgency — map FHIR priority to OpenMRS urgency
        String priority = fhir.path("priority").asText("routine").toLowerCase();
        String urgency = switch (priority) {
            case "urgent", "asap" -> "STAT";
            case "stat" -> "STAT";
            default -> "ROUTINE";
        };
        rest.put("urgency", urgency);

        // Authored on
        if (!authoredOn.isBlank()) rest.put("dateActivated", authoredOn);

        // Care setting (default to outpatient)
        rest.put("careSetting", "OUTPATIENT");

        // Instructions — patientInstruction (preferred) or note[0].text
        String instructions = fhir.path("patientInstruction").asText("");
        if (instructions.isBlank()) {
            JsonNode notes = fhir.path("note");
            if (notes.isArray() && !notes.isEmpty()) {
                instructions = notes.get(0).path("text").asText("");
            }
        }
        if (!instructions.isBlank()) rest.put("instructions", instructions);

        // Accession number — use source ServiceRequest id or first identifier value
        String accessionNumber = extractAccessionNumber(fhir);
        if (accessionNumber != null && !accessionNumber.isBlank()) {
            rest.put("accessionNumber", accessionNumber);
        }

        return new TransformResult("/order", rest.toString());
    }

    public String extractAccessionNumber(ObjectNode fhir) {
        // Prefer the source FHIR ServiceRequest id (cross-system reference)
        String id = fhir.path("id").asText("");
        if (!id.isBlank() && !isUuid(id)) {
            return id;
        }
        // Fall back to first identifier value
        JsonNode identifiers = fhir.path("identifier");
        if (identifiers.isArray()) {
            for (JsonNode ident : identifiers) {
                String value = ident.path("value").asText("");
                if (!value.isBlank()) return value;
            }
        }
        return id.isBlank() ? null : id;
    }

    private TransformResult transformMedicationRequest(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();
        rest.put("type", "drugorder");

        String patientUuid = extractUuidFromReference(fhir.path("subject").path("reference").asText(""));
        if (patientUuid != null) rest.put("patient", patientUuid);

        String encounterUuid = extractUuidFromReference(fhir.path("encounter").path("reference").asText(""));
        if (encounterUuid != null) rest.put("encounter", encounterUuid);

        // Drug concept
        String conceptUuid = conceptResolver.resolveConceptUuid(fhir.path("medicationCodeableConcept"));
        if (conceptUuid != null) rest.put("concept", conceptUuid);

        // Drug reference
        String drugRef = fhir.path("medicationReference").path("reference").asText("");
        String drugUuid = extractUuidFromReference(drugRef);
        if (drugUuid != null) rest.put("drug", drugUuid);

        // Orderer
        String ordererUuid = resolveOrderer(fhir);
        if (ordererUuid != null) rest.put("orderer", ordererUuid);

        // Dosing
        JsonNode dosageInstruction = fhir.path("dosageInstruction");
        if (dosageInstruction.isArray() && !dosageInstruction.isEmpty()) {
            JsonNode dosage = dosageInstruction.get(0);
            JsonNode doseAndRate = dosage.path("doseAndRate");
            if (doseAndRate.isArray() && !doseAndRate.isEmpty()) {
                JsonNode dose = doseAndRate.get(0).path("doseQuantity");
                if (!dose.isMissingNode()) {
                    rest.put("dose", dose.path("value").asDouble());
                    rest.put("doseUnits", dose.path("unit").asText(""));
                }
            }
            // Route
            String route = dosage.path("route").path("text").asText("");
            if (!route.isBlank()) rest.put("route", route);

            // Frequency
            JsonNode timing = dosage.path("timing");
            if (!timing.isMissingNode()) {
                rest.put("frequency", timing.path("code").path("text").asText(""));
            }
        }

        // Duration
        JsonNode dispenseRequest = fhir.path("dispenseRequest");
        if (!dispenseRequest.isMissingNode()) {
            JsonNode duration = dispenseRequest.path("expectedSupplyDuration");
            if (!duration.isMissingNode()) {
                rest.put("duration", duration.path("value").asInt());
                rest.put("durationUnits", duration.path("unit").asText(""));
            }
            JsonNode quantity = dispenseRequest.path("quantity");
            if (!quantity.isMissingNode()) {
                rest.put("quantity", quantity.path("value").asInt());
            }
        }

        // Authored on
        String authoredOn = normalizeAuthoredOnToNotBeforeNow(fhir.path("authoredOn").asText(""));
        if (!authoredOn.isBlank()) rest.put("dateActivated", authoredOn);

        rest.put("careSetting", "OUTPATIENT");
        String medPriority = fhir.path("priority").asText("routine").toLowerCase();
        rest.put("urgency", (medPriority.equals("stat") || medPriority.equals("asap")
                || medPriority.equals("urgent")) ? "STAT" : "ROUTINE");

        return new TransformResult("/order", rest.toString());
    }

    private TransformResult transformLocation(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        rest.put("name", fhir.path("name").asText(""));
        String description = fhir.path("description").asText("");
        if (!description.isBlank()) rest.put("description", description);

        JsonNode address = fhir.path("address");
        if (!address.isMissingNode()) {
            rest.put("address1", address.path("line").isArray() && !address.path("line").isEmpty()
                    ? address.path("line").get(0).asText("") : "");
            rest.put("cityVillage", address.path("city").asText(""));
            rest.put("stateProvince", address.path("state").asText(""));
            rest.put("country", address.path("country").asText(""));
            rest.put("postalCode", address.path("postalCode").asText(""));
        }

        return new TransformResult("/location", rest.toString());
    }

    private TransformResult transformPractitioner(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Person object with name
        JsonNode nameArray = fhir.path("name");
        if (nameArray.isArray() && !nameArray.isEmpty()) {
            JsonNode name = nameArray.get(0);
            ObjectNode person = rest.putObject("person");
            ObjectNode personName = objectMapper.createObjectNode();
            personName.put("givenName", name.path("given").isArray() && !name.path("given").isEmpty()
                    ? name.path("given").get(0).asText("") : "");
            personName.put("familyName", name.path("family").asText(""));
            person.putArray("names").add(personName);
            person.put("gender", "M"); // Default, as FHIR Practitioner may not have gender
        }

        // Identifier
        JsonNode identifiers = fhir.path("identifier");
        if (identifiers.isArray() && !identifiers.isEmpty()) {
            rest.put("identifier", identifiers.get(0).path("value").asText(""));
        }

        return new TransformResult("/provider", rest.toString());
    }

    private TransformResult transformMedication(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Name from code.text or code.coding[0].display
        JsonNode code = fhir.path("code");
        String name = code.path("text").asText("");
        if (name.isBlank() && code.path("coding").isArray() && !code.path("coding").isEmpty()) {
            name = code.path("coding").get(0).path("display").asText("");
        }
        rest.put("name", name);

        // Concept
        String conceptUuid = conceptResolver.resolveConceptUuid(code);
        if (conceptUuid != null) rest.put("concept", conceptUuid);

        return new TransformResult("/drug", rest.toString());
    }

    private TransformResult transformImmunization(ObjectNode fhir) {
        ObjectNode rest = objectMapper.createObjectNode();

        // Person
        String personUuid = extractUuidFromReference(fhir.path("patient").path("reference").asText(""));
        if (personUuid != null) rest.put("person", personUuid);

        // Encounter
        String encounterUuid = extractUuidFromReference(fhir.path("encounter").path("reference").asText(""));
        if (encounterUuid != null) rest.put("encounter", encounterUuid);

        // Vaccine concept (obs group concept)
        String conceptUuid = conceptResolver.resolveConceptUuid(fhir.path("vaccineCode"));
        if (conceptUuid != null) rest.put("concept", conceptUuid);

        // Obs datetime
        String obsDatetime = fhir.path("occurrenceDateTime").asText("");
        if (!obsDatetime.isBlank()) rest.put("obsDatetime", obsDatetime);

        // Dose as group member
        JsonNode doseQuantity = fhir.path("doseQuantity");
        if (!doseQuantity.isMissingNode()) {
            ArrayNode groupMembers = rest.putArray("groupMembers");
            ObjectNode doseMember = objectMapper.createObjectNode();
            if (personUuid != null) doseMember.put("person", personUuid);
            if (encounterUuid != null) doseMember.put("encounter", encounterUuid);
            if (conceptUuid != null) doseMember.put("concept", conceptUuid);
            doseMember.put("value", doseQuantity.path("value").asDouble());
            if (!obsDatetime.isBlank()) doseMember.put("obsDatetime", obsDatetime);
            groupMembers.add(doseMember);
        }

        return new TransformResult("/obs", rest.toString());
    }

    // ── Helper methods ──

    private static final String UUID_REGEX = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private boolean isUuid(String value) {
        return value != null && value.matches(UUID_REGEX);
    }

    /**
     * If the inbound FHIR {@code authoredOn} timestamp is in the past relative to the server clock,
     * coerce it to "now". Real-world callers always emit a timestamp slightly in the past (build +
     * dispatch latency), but OpenMRS validates {@code Order.dateActivated <= now} AND
     * {@code Encounter.encounterDatetime (= now) <= Order.dateActivated}, so anything before "now"
     * fails. Returning current UTC keeps both constraints satisfied. Blank input passes through.
     */
    private String normalizeAuthoredOnToNotBeforeNow(String authoredOn) {
        if (authoredOn == null || authoredOn.isBlank()) return authoredOn;
        try {
            Instant parsed = ZonedDateTime.parse(authoredOn).toInstant();
            Instant now = Instant.now();
            if (parsed.isBefore(now)) {
                String coerced = OPENMRS_DATE_FORMAT.withZone(ZoneOffset.UTC).format(now);
                log.debug("Coerced authoredOn {} -> {} (was before server now)", authoredOn, coerced);
                return coerced;
            }
            return authoredOn;
        } catch (Exception e) {
            log.debug("Could not parse authoredOn '{}', leaving as-is: {}", authoredOn, e.getMessage());
            return authoredOn;
        }
    }

    /**
     * Extracts identifier type name from a FHIR system URI.
     * Takes the last non-blank path segment: e.g. "http://example.org/fhir/NID" → "NID"
     */
    private String extractTypeNameFromSystem(String system) {
        if (system == null) return null;
        String[] parts = system.split("[/:#]");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return null;
    }

    /**
     * Generates an OpenMRS ID via the idgen module.
     * Falls back to null if idgen is not configured.
     */
    private String generateOpenMrsId() {
        String idgenSourceUuid = discoveredConfig.getIdgenSourceUuid();
        if (idgenSourceUuid == null || idgenSourceUuid.isBlank()) {
            // Try to discover the idgen source dynamically
            idgenSourceUuid = discoverIdgenSource();
            if (idgenSourceUuid == null) {
                log.warn("No idgen source configured — cannot auto-generate OpenMRS ID");
                return null;
            }
        }
        try {
            String response = restClient.post()
                    .uri("/idgen/identifiersource/{uuid}/identifier", idgenSourceUuid)
                    .header("Content-Type", "application/json")
                    .body("{}")
                    .retrieve()
                    .body(String.class);
            String generated = objectMapper.readTree(response).path("identifier").asText(null);
            return generated;
        } catch (Exception e) {
            log.error("Failed to generate OpenMRS ID via idgen: {}", e.getMessage());
            return null;
        }
    }

    private String discoverIdgenSource() {
        try {
            String response = restClient.get()
                    .uri("/idgen/identifiersource?v=default")
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                String primaryTypeName = discoveredConfig.getIdentifierTypeName();
                for (JsonNode source : results) {
                    String typeDisplay = source.path("identifierType").path("display").asText("");
                    if (typeDisplay.equalsIgnoreCase(primaryTypeName)) {
                        String uuid = source.path("uuid").asText(null);
                        if (uuid != null) {
                            discoveredConfig.setIdgenSourceUuid(uuid);
                            log.info("Discovered idgen source for '{}': {}", primaryTypeName, uuid);
                        }
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover idgen source: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Resolves a patient UUID from a FHIR subject node. Supports both styles:
     * <ul>
     *   <li>Literal reference: {@code subject.reference = "Patient/<uuid-or-identifier>"}</li>
     *   <li>Identifier-based reference (no literal reference): {@code subject.identifier.value = "<spice-id>"}
     *       — used by cce-intelligence-service when the source system only knows the SPICE patient id.</li>
     * </ul>
     * For non-UUID values, looks the patient up via {@code GET /patient?identifier=...}.
     */
    private String resolvePatientUuid(JsonNode subjectNode) {
        if (subjectNode == null || subjectNode.isMissingNode()) return null;

        // Phase 1: collect candidate identifiers and run lookup-only resolution
        // for both the literal reference and the FHIR identifier.
        String refCandidate = null;
        String reference = subjectNode.path("reference").asText("");
        if (!reference.isBlank()) {
            String tail = extractUuidFromReference(reference);
            if (tail != null) {
                if (isUuid(tail)) return tail;
                refCandidate = tail;
                String resolved = patientProvisioner.lookup(refCandidate);
                if (resolved != null) return resolved;
            }
        }

        String idCandidate = null;
        String idSystem = null;
        JsonNode identifier = subjectNode.path("identifier");
        if (!identifier.isMissingNode()) {
            String idValue = identifier.path("value").asText("");
            if (!idValue.isBlank()) {
                idCandidate = idValue;
                idSystem = identifier.path("system").asText("");
                String resolved = patientProvisioner.lookup(idCandidate);
                if (resolved != null) return resolved;
            }
        }

        // Phase 2: every lookup failed — auto-provision a placeholder patient.
        String createValue = idCandidate != null ? idCandidate : refCandidate;
        if (createValue != null) {
            String created = patientProvisioner.lookupOrCreate(createValue, idSystem);
            if (created != null) return created;
            log.warn("Subject identifier '{}' (system='{}') could not be resolved or auto-provisioned",
                    createValue, idSystem);
        }

        log.warn("Could not resolve patient from subject node: {}", subjectNode);
        return null;
    }

    /**
     * Resolves an encounter UUID from a FHIR reference.
     * If the reference ID is a UUID, returns it directly.
     * Otherwise, creates a new Consultation encounter linked to the patient's active visit
     * (per POC pattern: new encounter per ordering act, not reusing existing encounters).
     */
    private String resolveEncounterUuid(String reference, String patientUuid, String authoredOn) {
        String id = extractUuidFromReference(reference);
        if (id != null && isUuid(id)) return id;

        // Not a UUID — create a new encounter for this order
        if (patientUuid == null) return null;
        try {
            // Find active visit
            String visitUuid = findActiveVisit(patientUuid);
            if (visitUuid == null) {
                log.info("No active visit found for patient {} — auto-creating one", patientUuid);
                visitUuid = createVisit(patientUuid, authoredOn);
                if (visitUuid == null) {
                    log.warn("Failed to auto-create visit for patient: {}", patientUuid);
                    return null;
                }
            }

            // Create a new Consultation encounter using the order's authoredOn datetime
            return createOrderEncounter(patientUuid, visitUuid, authoredOn);
        } catch (Exception e) {
            log.warn("Failed to resolve encounter for patient '{}': {}", patientUuid, e.getMessage());
        }
        return null;
    }

    private String findActiveVisit(String patientUuid) {
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=default", patientUuid)
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("uuid").asText(null);
            }
        } catch (Exception e) {
            log.debug("No active visit found for patient {}: {}", patientUuid, e.getMessage());
        }
        return null;
    }

    private String createVisit(String patientUuid, String authoredOn) {
        try {
            // Use "Facility Visit" as default visit type
            String visitTypeUuid = discoveredConfig.getVisitTypeUuid();
            if (visitTypeUuid == null || visitTypeUuid.isBlank()) {
                visitTypeUuid = discoverVisitType();
                if (visitTypeUuid == null) {
                    log.warn("No visit type available to auto-create visit");
                    return null;
                }
            }

            String startDatetime;
            if (authoredOn != null && !authoredOn.isBlank()) {
                startDatetime = authoredOn;
            } else {
                startDatetime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now());
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("patient", patientUuid);
            payload.put("visitType", visitTypeUuid);
            payload.put("startDatetime", startDatetime);
            String locationUuid = discoveredConfig.getLocationUuid();
            if (locationUuid != null) payload.put("location", locationUuid);

            String response = restClient.post()
                    .uri("/visit")
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            String uuid = objectMapper.readTree(response).path("uuid").asText(null);
            log.info("Auto-created visit {} for patient {}", uuid, patientUuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create visit for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    private String discoverVisitType() {
        try {
            String response = restClient.get()
                    .uri("/visittype?v=default")
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                // Prefer "Facility Visit"
                for (JsonNode vt : results) {
                    if ("Facility Visit".equalsIgnoreCase(vt.path("name").asText(""))) {
                        String uuid = vt.path("uuid").asText(null);
                        discoveredConfig.setVisitTypeUuid(uuid);
                        log.info("Discovered visit type 'Facility Visit': {}", uuid);
                        return uuid;
                    }
                }
                // Fall back to first available
                if (!results.isEmpty()) {
                    String uuid = results.get(0).path("uuid").asText(null);
                    discoveredConfig.setVisitTypeUuid(uuid);
                    log.info("Discovered visit type '{}': {}", results.get(0).path("name").asText(""), uuid);
                    return uuid;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover visit types: {}", e.getMessage());
        }
        return null;
    }

    private String createOrderEncounter(String patientUuid, String visitUuid, String authoredOn) {
        // Prefer "Consultation" encounter type
        String encounterTypeUuid = discoveredConfig.getEncounterTypeCache().get("Consultation");
        if (encounterTypeUuid == null) {
            encounterTypeUuid = discoveredConfig.getEncounterTypeCache().get("Adult Visit");
        }
        if (encounterTypeUuid == null && !discoveredConfig.getEncounterTypeCache().isEmpty()) {
            encounterTypeUuid = discoveredConfig.getEncounterTypeCache().values().iterator().next();
        }
        if (encounterTypeUuid == null) {
            log.warn("No encounter type available to create order encounter");
            return null;
        }

        String locationUuid = discoveredConfig.getLocationUuid();
        // Use the order's authoredOn if available, otherwise current time
        String encounterDatetime;
        if (authoredOn != null && !authoredOn.isBlank()) {
            encounterDatetime = authoredOn;
        } else {
            encounterDatetime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("patient", patientUuid);
            payload.put("encounterType", encounterTypeUuid);
            payload.put("encounterDatetime", encounterDatetime);
            payload.put("visit", visitUuid);
            if (locationUuid != null) payload.put("location", locationUuid);

            String body = objectMapper.writeValueAsString(payload);
            String response = restClient.post()
                    .uri("/encounter")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String encUuid = objectMapper.readTree(response).path("uuid").asText(null);
            log.info("Auto-created encounter {} for order patient={} visit={}", encUuid, patientUuid, visitUuid);
            return encUuid;
        } catch (Exception e) {
            log.error("Failed to create order encounter for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    private String extractUuidFromReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String[] parts = reference.split("/");
        return parts[parts.length - 1];
    }

    private String resolveEncounterType(JsonNode typeCodeableConcept) {
        JsonNode coding = typeCodeableConcept.path("coding");
        if (coding.isArray() && !coding.isEmpty()) {
            String display = coding.get(0).path("display").asText("");
            String code = coding.get(0).path("code").asText("");
            // Try to find in discovered config
            String uuid = discoveredConfig.getEncounterTypeCache().get(display);
            if (uuid != null) return uuid;
            uuid = discoveredConfig.getEncounterTypeCache().get(code);
            if (uuid != null) return uuid;
        }
        String text = typeCodeableConcept.path("text").asText("");
        if (!text.isBlank()) {
            return discoveredConfig.getEncounterTypeCache().get(text);
        }
        return null;
    }

    private volatile String cachedReferralOrderTypeUuid;

    /**
     * Discovers the "Referral" OrderType UUID, creating it if it doesn't exist.
     * Referral orders use the generic {@code org.openmrs.Order} java class.
     */
    private String discoverReferralOrderTypeUuid() {
        if (cachedReferralOrderTypeUuid != null) return cachedReferralOrderTypeUuid;
        try {
            String response = restClient.get()
                    .uri("/ordertype?v=default")
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode ot : results) {
                    if ("Referral".equalsIgnoreCase(ot.path("name").asText(""))) {
                        cachedReferralOrderTypeUuid = ot.path("uuid").asText(null);
                        log.info("Discovered Referral OrderType UUID: {}", cachedReferralOrderTypeUuid);
                        return cachedReferralOrderTypeUuid;
                    }
                }
            }
            // Not found — auto-create
            cachedReferralOrderTypeUuid = createReferralOrderType();
            return cachedReferralOrderTypeUuid;
        } catch (Exception e) {
            log.warn("Failed to discover Referral OrderType: {}", e.getMessage());
        }
        return null;
    }

    private String createReferralOrderType() {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("name", "Referral");
            payload.put("description", "Order for patient referrals to other services or facilities");
            payload.put("javaClassName", "org.openmrs.Order");
            payload.putArray("conceptClasses");  // empty — accepts any concept class

            String body = objectMapper.writeValueAsString(payload);
            log.info("Auto-creating 'Referral' OrderType in OpenMRS");

            String response = restClient.post()
                    .uri("/ordertype")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String uuid = objectMapper.readTree(response).path("uuid").asText(null);
            log.info("Auto-created 'Referral' OrderType with UUID: {}", uuid);
            return uuid;
        } catch (Exception e) {
            log.warn("Failed to auto-create Referral OrderType: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Analyses a ServiceRequest to determine the OpenMRS order type.
     * <p>
     * A ServiceRequest is treated as a referral if ANY of the following are true:
     * <ul>
     *   <li>{@code requisition.value == "medicalReview"} (SPICE convention)</li>
     *   <li>any {@code identifier} has {@code system} ending in {@code /patient-status}
     *       or {@code /patient-current-status} with value {@code "Referred"}</li>
     *   <li>any {@code category.coding[]} has code/display containing "referral"
     *       (FHIR standard convention, e.g. SNOMED 3457005)</li>
     *   <li>{@code code.coding[]} has code/display containing "referral"</li>
     * </ul>
     * Otherwise defaults to {@code "testorder"}.
     */
    private String determineOrderType(ObjectNode fhir) {
        return isReferral(fhir) ? "order" : "testorder";
    }

    /**
     * Returns {@code true} if the given FHIR ServiceRequest should be treated as
     * a referral (Referral OrderType in OpenMRS) using the same 4-signal logic
     * documented on {@link #determineOrderType(ObjectNode)}. Exposed so the
     * routing layer can apply referral-specific visit/encounter/queue handling.
     */
    public boolean isReferral(ObjectNode fhir) {
        // 1. SPICE: requisition.ticket-type == medicalReview
        JsonNode requisition = fhir.path("requisition");
        if (!requisition.isMissingNode()) {
            String system = requisition.path("system").asText("");
            String value = requisition.path("value").asText("");
            if (system.endsWith("/ticket-type") && "medicalReview".equalsIgnoreCase(value)) {
                log.debug("Detected referral via requisition.ticket-type=medicalReview");
                return true;
            }
        }

        // 2. SPICE: identifier with patient-status / patient-current-status = Referred
        JsonNode identifiers = fhir.path("identifier");
        if (identifiers.isArray()) {
            for (JsonNode id : identifiers) {
                String system = id.path("system").asText("");
                String value = id.path("value").asText("");
                if ((system.endsWith("/patient-status") || system.endsWith("/patient-current-status"))
                        && "Referred".equalsIgnoreCase(value)) {
                    log.debug("Detected referral via identifier patient-status=Referred");
                    return true;
                }
            }
        }

        // 3. FHIR standard: category contains "referral"
        JsonNode categories = fhir.path("category");
        if (categories.isArray()) {
            for (JsonNode cat : categories) {
                if (containsReferralKeyword(cat)) {
                    log.debug("Detected referral via category coding/text");
                    return true;
                }
            }
        }

        // 4. FHIR standard: code contains "referral"
        if (containsReferralKeyword(fhir.path("code"))) {
            log.debug("Detected referral via code coding/text");
            return true;
        }

        return false;
    }

    private boolean containsReferralKeyword(JsonNode codeableConcept) {
        if (codeableConcept == null || codeableConcept.isMissingNode()) return false;
        String text = codeableConcept.path("text").asText("");
        if (text.toLowerCase().contains("referral")) return true;
        JsonNode coding = codeableConcept.path("coding");
        if (coding.isArray()) {
            for (JsonNode c : coding) {
                String code = c.path("code").asText("").toLowerCase();
                String display = c.path("display").asText("").toLowerCase();
                if (code.contains("referral") || display.contains("referral")) {
                    return true;
                }
                // SNOMED: 3457005 = Patient referral
                if ("3457005".equals(c.path("code").asText(""))) return true;
            }
        }
        return false;
    }

    private String resolveConceptFromSpiceCategory(ObjectNode fhir) {
        JsonNode identifiers = fhir.path("identifier");
        if (!identifiers.isArray()) return null;

        String categoryName = null;
        for (JsonNode id : identifiers) {
            String system = id.path("system").asText("");
            if (system.endsWith("/category")) {
                categoryName = id.path("value").asText("");
                if (!categoryName.isBlank()) {
                    String uuid = conceptResolver.searchConceptByName(categoryName);
                    if (uuid != null) return uuid;
                }
            }
        }

        // No exact match found — auto-create the category concept with Test class + Text datatype
        if (categoryName != null && !categoryName.isBlank()) {
            log.info("Auto-creating concept for SPICE category: {}", categoryName);
            return conceptResolver.autoCreateConcept(categoryName);
        }

        return null;
    }

    private String resolveOrderer(ObjectNode fhir) {
        // SPICE Gap 2: the practitioner is in performer[], mapped to an OpenMRS provider by identifier
        JsonNode performers = fhir.path("performer");
        if (performers.isArray()) {
            for (JsonNode performer : performers) {
                String ref = performer.path("reference").asText("");
                if (ref.startsWith("Practitioner/")) {
                    String id = extractUuidFromReference(ref);
                    // ReferenceResolver already turned a mapped practitioner into its provider UUID
                    if (id != null && isUuid(id)) return id;
                    if (id != null) {
                        String providerUuid = providerResolver.findOrCreateExternal(
                                id, performer.path("display").asText(null));
                        if (providerUuid != null) return providerUuid;
                    }
                }
            }
        }

        // Fallback to requester if it's a UUID
        String requesterRef = fhir.path("requester").path("reference").asText("");
        if (!requesterRef.isBlank()) {
            String id = extractUuidFromReference(requesterRef);
            if (id != null && isUuid(id)) return id;
        }

        // Final fallback: configurable system provider
        log.warn("No Practitioner resolved for order — falling back to the system provider");
        return providerResolver.findOrCreateFallback();
    }

    private String mapAllergyCategory(String fhirCategory) {
        return switch (fhirCategory.toLowerCase()) {
            case "medication" -> "DRUG";
            case "food" -> "FOOD";
            case "environment" -> "ENVIRONMENT";
            default -> "DRUG";
        };
    }

    private String mapAllergySeverity(String fhirSeverity) {
        return switch (fhirSeverity.toLowerCase()) {
            case "severe" -> discoveredConfig.getSevereSeverityConceptUuid();
            case "moderate" -> discoveredConfig.getModerateSeverityConceptUuid();
            case "mild" -> discoveredConfig.getMildSeverityConceptUuid();
            default -> discoveredConfig.getModerateSeverityConceptUuid();
        };
    }

    public record TransformResult(String endpoint, String payload) {}
}
