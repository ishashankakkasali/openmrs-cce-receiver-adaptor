package org.openphc.cce.receiver.controller;

import java.util.Map;

import org.openphc.cce.receiver.fhir.CapabilityStatementCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic endpoint exposing the current CapabilityStatement cache
 * and routing table.
 *
 * <p>Useful for debugging and verifying which resources route through
 * FHIR vs REST.
 */
@RestController
@RequestMapping("/api/v1")
public class DiagnosticsController {

    private final CapabilityStatementCache capabilityCache;

    public DiagnosticsController(CapabilityStatementCache capabilityCache) {
        this.capabilityCache = capabilityCache;
    }

    /**
     * Returns the current CapabilityStatement cache contents.
     */
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> getCapabilities() {
        return ResponseEntity.ok(Map.of(
                "lastRefreshed", capabilityCache.getLastRefreshed() != null
                        ? capabilityCache.getLastRefreshed().toString() : "never",
                "resourceTypes", capabilityCache.getSupportedResourceTypes().size(),
                "capabilities", capabilityCache.getCapabilities()
        ));
    }

    /**
     * Returns a simplified routing table showing which resources go FHIR vs REST.
     */
    @GetMapping("/routing-table")
    public ResponseEntity<Map<String, Object>> getRoutingTable() {
        var capabilities = capabilityCache.getCapabilities();
        var fhirCreateable = capabilities.entrySet().stream()
                .filter(e -> e.getValue().contains("create"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        var restFallback = capabilities.entrySet().stream()
                .filter(e -> !e.getValue().contains("create"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        return ResponseEntity.ok(Map.of(
                "fhirCreate", fhirCreateable,
                "restFallback", restFallback,
                "lastRefreshed", capabilityCache.getLastRefreshed() != null
                        ? capabilityCache.getLastRefreshed().toString() : "never"
        ));
    }
}
