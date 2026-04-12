package org.openphc.cce.receiver.service;

import java.util.List;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Orchestrates the full inbound resource processing pipeline:
 *
 * <ol>
 *   <li>Parse & split the incoming FHIR payload (Bundle or standalone)</li>
 *   <li>Route each resource entry to the appropriate OpenMRS endpoint</li>
 *   <li>Aggregate results into a {@link ProcessingResponse}</li>
 * </ol>
 *
 * <p>This service keeps the controller thin — the controller handles only HTTP
 * concerns and delegates all business logic here.
 */
@Service
public class InboundProcessingService {

    private static final Logger log = LoggerFactory.getLogger(InboundProcessingService.class);

    private final BundleSplitter bundleSplitter;
    private final ResourceRouter resourceRouter;
    private final VisitManager visitManager;
    private final MeterRegistry meterRegistry;

    public InboundProcessingService(
            BundleSplitter bundleSplitter,
            ResourceRouter resourceRouter,
            VisitManager visitManager,
            MeterRegistry meterRegistry) {
        this.bundleSplitter = bundleSplitter;
        this.resourceRouter = resourceRouter;
        this.visitManager = visitManager;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Processes an inbound FHIR payload through the complete pipeline.
     *
     * @param fhirJson      the raw FHIR JSON (Bundle or standalone resource)
     * @param correlationId optional correlation ID for tracing
     * @param sourceSystem  optional source system identifier
     * @return processing response with per-resource results
     */
    public ProcessingResponse process(String fhirJson, String correlationId, String sourceSystem) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Set MDC context for structured logging
            if (correlationId != null) MDC.put("correlationId", correlationId);
            if (sourceSystem != null) MDC.put("source", sourceSystem);

            Counter.builder("cce.receiver.requests.received")
                    .tag("source", sourceSystem != null ? sourceSystem : "unknown")
                    .register(meterRegistry).increment();

            log.info("Processing inbound FHIR payload: correlationId={}, source={}",
                    correlationId, sourceSystem);

            // Step 1: Split payload into individual resource entries
            List<ResourceEntry> entries = bundleSplitter.split(fhirJson);

            if (entries.isEmpty()) {
                log.warn("No resource entries extracted from payload");
                return ProcessingResponse.from(List.of());
            }

            // Step 2: Route each entry to OpenMRS (FHIR or REST)
            List<RoutingResult> results = resourceRouter.route(entries);

            // Step 3: Build response
            ProcessingResponse response = ProcessingResponse.from(results);

            log.info("Processing complete: total={}, succeeded={}, failed={}",
                    response.totalEntries(), response.succeeded(), response.failed());

            return response;

        } finally {
            // Clear per-request caches
            visitManager.clearCache();

            sample.stop(Timer.builder("cce.receiver.processing.total")
                    .register(meterRegistry));
            MDC.remove("correlationId");
            MDC.remove("source");
        }
    }
}
