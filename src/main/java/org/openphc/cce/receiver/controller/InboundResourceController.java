package org.openphc.cce.receiver.controller;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main inbound endpoint for the CCE Receiver Adaptor.
 *
 * <p>Receives FHIR R4 payloads (standalone resources or Bundles) from
 * CCE Core and routes them to OpenMRS via FHIR or REST endpoints
 * based on CapabilityStatement discovery.
 *
 * <p>This controller handles only HTTP concerns — all business logic
 * is delegated to {@link InboundProcessingService}.
 */
@RestController
@RequestMapping("/api/v1")
public class InboundResourceController {

    private static final Logger log = LoggerFactory.getLogger(InboundResourceController.class);

    private final InboundProcessingService processingService;

    public InboundResourceController(InboundProcessingService processingService) {
        this.processingService = processingService;
    }

    /**
     * Receives a FHIR R4 payload and routes it to OpenMRS.
     *
     * <p>Accepts both standalone FHIR resources and transaction Bundles.
     * Each resource within the payload is individually routed to either
     * the OpenMRS FHIR endpoint or REST endpoint based on CapabilityStatement
     * capability discovery.
     *
     * @param body          raw FHIR JSON payload (Bundle or standalone resource)
     * @param correlationId optional correlation ID for distributed tracing
     * @param sourceSystem  optional source system identifier (e.g., "spice", "ebuzima")
     * @return processing response with per-resource results
     */
    @PostMapping(
            value = "/fhir",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/fhir+json"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ProcessingResponse> receiveResource(
            @RequestBody String body,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystem) {

        log.info("Received inbound FHIR payload: correlationId={}, source={}, size={}",
                correlationId, sourceSystem, body != null ? body.length() : 0);

        ProcessingResponse response = processingService.process(body, correlationId, sourceSystem);

        HttpStatus status = response.failed() == 0 ? HttpStatus.ACCEPTED : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(response);
    }
}
