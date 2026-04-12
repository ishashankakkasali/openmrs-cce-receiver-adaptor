package org.openphc.cce.receiver.fhir;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CapabilityStatementCache}.
 *
 * <p>Tests the capability lookup logic using a pre-populated cache.
 * Does not test the actual HTTP refresh (that's an integration test).
 */
class CapabilityStatementCacheTest {

    @Test
    void shouldReportFhirCapable() {
        CapabilityStatementCache cache = createTestCache();

        assertTrue(cache.canFhir("Patient", "create"));
        assertTrue(cache.canFhir("Patient", "read"));
        assertTrue(cache.canFhir("Patient", "update"));
        assertTrue(cache.canFhir("Patient", "delete"));
        assertTrue(cache.canFhir("Encounter", "create"));
        assertTrue(cache.canFhir("Observation", "create"));
    }

    @Test
    void shouldReportRestFallback() {
        CapabilityStatementCache cache = createTestCache();

        // ServiceRequest only supports read + search-type, not create
        assertFalse(cache.canFhir("ServiceRequest", "create"));
        assertFalse(cache.canFhir("ServiceRequest", "update"));
        assertTrue(cache.canFhir("ServiceRequest", "read"));

        // MedicationRequest only supports read + search-type, not create
        assertFalse(cache.canFhir("MedicationRequest", "create"));
        assertFalse(cache.canFhir("MedicationRequest", "update"));
        assertTrue(cache.canFhir("MedicationRequest", "read"));
    }

    @Test
    void shouldReturnEmptyForUnknownResourceType() {
        CapabilityStatementCache cache = createTestCache();

        assertFalse(cache.canFhir("UnknownResource", "create"));
        assertTrue(cache.getSupportedOperations("UnknownResource").isEmpty());
    }

    /**
     * Creates a test cache with capabilities matching the real OpenMRS CapabilityStatement.
     */
    private CapabilityStatementCache createTestCache() {
        // We can't easily mock the scheduled refresh, so we use reflection
        // to set the capabilities map directly.
        CapabilityStatementCache cache = new CapabilityStatementCache(null, null);

        // Use reflection to populate the capabilities for testing
        try {
            var field = CapabilityStatementCache.class.getDeclaredField("capabilities");
            field.setAccessible(true);
            field.set(cache, Map.of(
                    "Patient", Set.of("create", "read", "update", "delete", "patch", "search-type"),
                    "Encounter", Set.of("create", "read", "update", "delete", "search-type"),
                    "Observation", Set.of("create", "read", "delete", "history-instance", "search-type"),
                    "Condition", Set.of("create", "read", "update", "delete", "search-type"),
                    "ServiceRequest", Set.of("read", "search-type"),
                    "MedicationRequest", Set.of("read", "search-type", "patch"),
                    "AllergyIntolerance", Set.of("create", "read", "update", "delete", "search-type"),
                    "Location", Set.of("create", "read", "update", "delete", "search-type"),
                    "Medication", Set.of("create", "read", "update", "delete", "search-type"),
                    "Task", Set.of("create", "read", "update", "delete", "search-type")
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to set test capabilities", e);
        }

        return cache;
    }
}
