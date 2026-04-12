package org.openphc.cce.receiver.service;

import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Sends FHIR resources to the OpenMRS FHIR R4 endpoint.
 *
 * <p>Used when the CapabilityStatement confirms the resource type
 * supports the required operation. Retries on 5xx / timeouts.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>POST /{ResourceType} — create</li>
 *   <li>PUT /{ResourceType}/{id} — update</li>
 *   <li>DELETE /{ResourceType}/{id} — delete</li>
 * </ul>
 */
@Component
public class OpenMrsFhirClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsFhirClient.class);

    private final RestClient fhirRestClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final FhirReferenceResolver referenceResolver;
    private final RequiredFieldEnricher requiredFieldEnricher;
    private final PatientIdentifierEnricher identifierEnricher;
    private final FhirElementIdEnricher elementIdEnricher;
    private final VisitManager visitManager;

    public OpenMrsFhirClient(
            @Qualifier("fhirRestClient") RestClient fhirRestClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            FhirReferenceResolver referenceResolver,
            RequiredFieldEnricher requiredFieldEnricher,
            PatientIdentifierEnricher identifierEnricher,
            FhirElementIdEnricher elementIdEnricher,
            VisitManager visitManager) {
        this.fhirRestClient = fhirRestClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.referenceResolver = referenceResolver;
        this.requiredFieldEnricher = requiredFieldEnricher;
        this.identifierEnricher = identifierEnricher;
        this.elementIdEnricher = elementIdEnricher;
        this.visitManager = visitManager;
    }

    /**
     * Sends a FHIR resource to OpenMRS via the FHIR R4 endpoint.
     *
     * @param resourceType FHIR resource type (e.g. "Patient")
     * @param resourceJson the FHIR resource JSON
     * @param method       HTTP method ("POST", "PUT", "DELETE")
     * @return routing result with status
     */
    @Retryable(
            retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public RoutingResult send(String resourceType, String resourceJson, String method) {
        Timer.Sample sample = Timer.start(meterRegistry);

        // Resolve external identifiers in references to OpenMRS UUIDs
        String enrichedJson = referenceResolver.resolve(resourceType, resourceJson);

        // Auto-populate missing required fields (e.g. effectiveDateTime for Observation)
        enrichedJson = requiredFieldEnricher.enrich(resourceType, enrichedJson);

        // Enrich Patient payloads with OpenMRS-compatible identifier if missing
        enrichedJson = identifierEnricher.enrich(resourceType, enrichedJson);

        // Add UUID 'id' fields to sub-elements that OpenMRS FHIR module requires
        enrichedJson = elementIdEnricher.enrich(enrichedJson);

        // Ensure Encounter is linked to a Visit (required for O3 Visits UI)
        if ("Encounter".equals(resourceType)) {
            enrichedJson = visitManager.ensureVisitLinked(enrichedJson);
        }

        try {
            String responseBody = switch (method.toUpperCase()) {
                case "POST" -> doPost(resourceType, enrichedJson);
                case "PUT" -> doPut(resourceType, enrichedJson);
                case "DELETE" -> doDelete(resourceType, enrichedJson);
                default -> throw new UnsupportedOperationException("Unsupported method: " + method);
            };

            String resourceId = extractId(responseBody);
            String status = "POST".equalsIgnoreCase(method) ? "created" : "updated";

            log.info("FHIR {} {} succeeded: id={}", method, resourceType, resourceId);
            return RoutingResult.success(resourceType, resourceId, "fhir", status, httpStatus(method), responseBody);

        } catch (RestClientResponseException e) {
            log.warn("FHIR {} {} failed: status={}, body={}",
                    method, resourceType, e.getStatusCode().value(), e.getResponseBodyAsString());

            // 4xx errors are not retryable — rethrow only 5xx
            if (e.getStatusCode().is4xxClientError()) {
                return RoutingResult.failure(resourceType, null, "fhir",
                        e.getStatusCode().value(), e.getResponseBodyAsString(), e.getMessage());
            }
            throw e; // 5xx will trigger retry
        } finally {
            sample.stop(Timer.builder("cce.receiver.fhir.latency")
                    .tag("resourceType", resourceType)
                    .tag("method", method)
                    .register(meterRegistry));
        }
    }

    @Recover
    public RoutingResult recoverSend(Exception ex, String resourceType, String resourceJson, String method) {
        log.error("FHIR {} {} failed after retries: {}", method, resourceType, ex.getMessage());
        return RoutingResult.failure(resourceType, null, "fhir", 502, null,
                "FHIR request failed after retries: " + ex.getMessage());
    }

    private String doPost(String resourceType, String resourceJson) {
        return fhirRestClient.post()
                .uri("/{resourceType}", resourceType)
                .body(resourceJson)
                .retrieve()
                .body(String.class);
    }

    private String doPut(String resourceType, String resourceJson) {
        String resourceId = extractIdFromJson(resourceJson);
        return fhirRestClient.put()
                .uri("/{resourceType}/{id}", resourceType, resourceId)
                .body(resourceJson)
                .retrieve()
                .body(String.class);
    }

    private String doDelete(String resourceType, String resourceJson) {
        String resourceId = extractIdFromJson(resourceJson);
        return fhirRestClient.delete()
                .uri("/{resourceType}/{id}", resourceType, resourceId)
                .retrieve()
                .body(String.class);
    }

    private String extractId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("id").asText(null);
        } catch (Exception e) {
            log.debug("Could not extract ID from FHIR response");
            return null;
        }
    }

    private String extractIdFromJson(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            return root.path("id").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot extract id from resource JSON", e);
        }
    }

    private int httpStatus(String method) {
        return "POST".equalsIgnoreCase(method) ? 201 : 200;
    }
}
