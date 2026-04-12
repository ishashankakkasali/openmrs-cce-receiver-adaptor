package org.openphc.cce.receiver.service;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.openphc.cce.receiver.fhir.FhirResourceParser;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decomposes an incoming FHIR payload into individual {@link ResourceEntry} items.
 *
 * <p>Handles two cases:
 * <ul>
 *   <li><b>Bundle</b> — iterates {@code entry[]} and extracts each resource with its method and fullUrl</li>
 *   <li><b>Standalone resource</b> — wraps into a single-element list with method=POST</li>
 * </ul>
 *
 * <p>The output is a flat list of typed, route-able entries that the
 * {@link ResourceRouter} can classify and send.
 */
@Component
public class BundleSplitter {

    private static final Logger log = LoggerFactory.getLogger(BundleSplitter.class);

    private final FhirResourceParser parser;

    public BundleSplitter(FhirResourceParser parser) {
        this.parser = parser;
    }

    /**
     * Splits an incoming FHIR JSON payload into a list of individual resource entries.
     *
     * @param fhirJson the raw FHIR JSON (Bundle or standalone resource)
     * @return list of resource entries, never null
     */
    public List<ResourceEntry> split(String fhirJson) {
        IBaseResource resource = parser.parse(fhirJson);

        if (parser.isBundle(resource)) {
            return splitBundle((Bundle) resource);
        } else {
            return splitStandalone(resource);
        }
    }

    /** Maximum nesting depth to prevent stack overflow from pathological payloads. */
    private static final int MAX_NESTING_DEPTH = 5;

    private List<ResourceEntry> splitBundle(Bundle bundle) {
        return splitBundle(bundle, 0);
    }

    private List<ResourceEntry> splitBundle(Bundle bundle, int depth) {
        log.info("Splitting Bundle: type={}, entries={}, depth={}",
                bundle.getType(), bundle.getEntry().size(), depth);

        if (depth > MAX_NESTING_DEPTH) {
            log.warn("Maximum Bundle nesting depth ({}) exceeded — skipping further recursion",
                    MAX_NESTING_DEPTH);
            return List.of();
        }

        List<ResourceEntry> entries = new ArrayList<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() == null) {
                log.warn("Skipping Bundle entry with null resource (fullUrl={})", entry.getFullUrl());
                continue;
            }

            // Recursively flatten nested Bundles
            if (entry.getResource() instanceof Bundle nestedBundle) {
                log.info("Nested Bundle detected at depth {} — recursively splitting", depth);
                entries.addAll(splitBundle(nestedBundle, depth + 1));
                continue;
            }

            String resourceType = entry.getResource().fhirType();
            String resourceJson = parser.encode(entry.getResource());
            String method = extractMethod(entry);
            String fullUrl = entry.getFullUrl();

            entries.add(new ResourceEntry(resourceType, resourceJson, method, fullUrl));

            log.debug("Extracted Bundle entry: type={}, method={}, fullUrl={}",
                    resourceType, method, fullUrl);
        }

        log.info("Bundle split into {} resource entries (depth={})", entries.size(), depth);
        return entries;
    }

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private List<ResourceEntry> splitStandalone(IBaseResource resource) {
        String resourceType = resource.fhirType();
        String resourceJson = parser.encode(resource);
        String resourceId = resource.getIdElement().getIdPart();

        // Standalone: only treat as update (PUT) if the ID is a real OpenMRS UUID.
        // RHIE payloads often include non-UUID IDs (e.g. UPI "251119-0001-4106"),
        // which are source identifiers, not OpenMRS resource IDs.
        boolean isUpdate = resourceId != null && !resourceId.isBlank()
                && resourceId.matches(UUID_REGEX);
        String method = isUpdate ? "PUT" : "POST";

        log.info("Standalone resource: type={}, method={}, id={}", resourceType, method, resourceId);

        return List.of(new ResourceEntry(resourceType, resourceJson, method, null));
    }

    private String extractMethod(Bundle.BundleEntryComponent entry) {
        if (entry.getRequest() != null && entry.getRequest().getMethod() != null) {
            return entry.getRequest().getMethod().toCode();
        }
        // Default to POST if no request method specified (e.g., collection/document bundles)
        return "POST";
    }
}
