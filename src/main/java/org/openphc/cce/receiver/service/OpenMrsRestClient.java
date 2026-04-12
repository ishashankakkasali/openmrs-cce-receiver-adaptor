package org.openphc.cce.receiver.service;

import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.transformer.FhirToRestTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Sends resources to the OpenMRS REST API v1 endpoint when the FHIR endpoint
 * does not support the required operation.
 *
 * <p>This is the REST fallback path. The incoming FHIR resource is first
 * transformed to the OpenMRS REST payload format using {@link FhirToRestTransformer},
 * then POSTed to the appropriate REST endpoint.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>POST /order — for ServiceRequest (TestOrder) and MedicationRequest (DrugOrder)</li>
 *   <li>POST /encounter — for Encounters with embedded orders</li>
 *   <li>POST /{resource} — generic fallback for other resource types</li>
 * </ul>
 */
@Component
public class OpenMrsRestClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsRestClient.class);

    private final RestClient openmrsRestClient;
    private final FhirToRestTransformer transformer;
    private final FhirReferenceResolver referenceResolver;
    private final RequiredFieldEnricher requiredFieldEnricher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OpenMrsRestClient(
            @Qualifier("openmrsRestClient") RestClient openmrsRestClient,
            FhirToRestTransformer transformer,
            FhirReferenceResolver referenceResolver,
            RequiredFieldEnricher requiredFieldEnricher,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.openmrsRestClient = openmrsRestClient;
        this.transformer = transformer;
        this.referenceResolver = referenceResolver;
        this.requiredFieldEnricher = requiredFieldEnricher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Transforms a FHIR resource to OpenMRS REST format and sends it.
     *
     * @param resourceType FHIR resource type (e.g. "ServiceRequest")
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

        try {
            // Resolve references and concept codes in the FHIR payload before transformation
            String resolvedJson = referenceResolver.resolve(resourceType, resourceJson);

            // Auto-populate missing required fields
            resolvedJson = requiredFieldEnricher.enrich(resourceType, resolvedJson);

            // Transform FHIR → REST payload
            FhirToRestTransformer.RestPayload payload = transformer.transform(resourceType, resolvedJson);

            log.debug("Transformed {} to REST: endpoint={}", resourceType, payload.endpoint());

            // Send to OpenMRS REST endpoint
            String responseBody = switch (method.toUpperCase()) {
                case "POST" -> doPost(payload);
                case "PUT" -> doPut(payload);
                case "DELETE" -> doDelete(payload);
                default -> throw new UnsupportedOperationException("Unsupported method: " + method);
            };

            String resourceId = extractUuid(responseBody);
            String status = "POST".equalsIgnoreCase(method) ? "created" : "updated";

            log.info("REST {} {} succeeded: id={}", method, resourceType, resourceId);
            return RoutingResult.success(resourceType, resourceId, "rest", status,
                    "POST".equalsIgnoreCase(method) ? 201 : 200, responseBody);

        } catch (RestClientResponseException e) {
            log.warn("REST {} {} failed: status={}, body={}",
                    method, resourceType, e.getStatusCode().value(), e.getResponseBodyAsString());

            if (e.getStatusCode().is4xxClientError()) {
                return RoutingResult.failure(resourceType, null, "rest",
                        e.getStatusCode().value(), e.getResponseBodyAsString(), e.getMessage());
            }
            throw e; // 5xx triggers retry
        } finally {
            sample.stop(Timer.builder("cce.receiver.rest.latency")
                    .tag("resourceType", resourceType)
                    .tag("method", method)
                    .register(meterRegistry));
        }
    }

    @Recover
    public RoutingResult recoverSend(Exception ex, String resourceType, String resourceJson, String method) {
        log.error("REST {} {} failed after retries: {}", method, resourceType, ex.getMessage());
        return RoutingResult.failure(resourceType, null, "rest", 502, null,
                "REST request failed after retries: " + ex.getMessage());
    }

    private String doPost(FhirToRestTransformer.RestPayload payload) {
        return openmrsRestClient.post()
                .uri("/{endpoint}", payload.endpoint())
                .body(payload.body())
                .retrieve()
                .body(String.class);
    }

    private String doPut(FhirToRestTransformer.RestPayload payload) {
        String uuid = extractUuidFromPayload(payload.body());
        return openmrsRestClient.put()
                .uri("/{endpoint}/{uuid}", payload.endpoint(), uuid)
                .body(payload.body())
                .retrieve()
                .body(String.class);
    }

    private String doDelete(FhirToRestTransformer.RestPayload payload) {
        String uuid = extractUuidFromPayload(payload.body());
        return openmrsRestClient.delete()
                .uri("/{endpoint}/{uuid}", payload.endpoint(), uuid)
                .retrieve()
                .body(String.class);
    }

    private String extractUuid(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("uuid").asText(null);
        } catch (Exception e) {
            log.debug("Could not extract UUID from REST response");
            return null;
        }
    }

    private String extractUuidFromPayload(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("uuid").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot extract uuid from REST payload", e);
        }
    }
}
