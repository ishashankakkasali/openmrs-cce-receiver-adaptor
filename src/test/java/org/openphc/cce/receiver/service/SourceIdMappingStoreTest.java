package org.openphc.cce.receiver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceIdMappingStore}.
 */
class SourceIdMappingStoreTest {

    private SourceIdMappingStore store;

    @BeforeEach
    void setUp() {
        store = new SourceIdMappingStore();
    }

    @Test
    void shouldStoreAndRetrieveMapping() {
        store.store("Encounter", "source-enc-001", "abc-openmrs-uuid");

        var result = store.getOpenMrsUuid("Encounter", "source-enc-001");
        assertTrue(result.isPresent());
        assertEquals("abc-openmrs-uuid", result.get());
    }

    @Test
    void shouldReturnEmptyForUnknownMapping() {
        var result = store.getOpenMrsUuid("Encounter", "unknown-id");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldOverwriteExistingMapping() {
        store.store("Patient", "pat-001", "old-uuid");
        store.store("Patient", "pat-001", "new-uuid");

        assertEquals("new-uuid", store.getOpenMrsUuid("Patient", "pat-001").orElse(null));
        assertEquals(1, store.size(), "Overwriting should not increase count");
    }

    @Test
    void shouldTrackMappingCount() {
        assertEquals(0, store.size());

        store.store("Patient", "p1", "uuid1");
        store.store("Encounter", "e1", "uuid2");
        store.store("Observation", "o1", "uuid3");

        assertEquals(3, store.size());
    }

    @Test
    void shouldClearAllMappings() {
        store.store("Patient", "p1", "uuid1");
        store.store("Encounter", "e1", "uuid2");

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.getOpenMrsUuid("Patient", "p1").isEmpty());
    }

    @Test
    void shouldKeepDifferentResourceTypesSeparate() {
        // Same source ID, different resource types — two separate mappings
        store.store("Patient", "shared-id", "patient-uuid");
        store.store("Encounter", "shared-id", "encounter-uuid");

        assertEquals("patient-uuid", store.getOpenMrsUuid("Patient", "shared-id").orElse(null));
        assertEquals("encounter-uuid", store.getOpenMrsUuid("Encounter", "shared-id").orElse(null));
        assertEquals(2, store.size());
    }

    @Test
    void shouldReportContainsCorrectly() {
        store.store("Patient", "p1", "uuid1");

        assertTrue(store.contains("Patient", "p1"));
        assertFalse(store.contains("Patient", "p2"));
        assertFalse(store.contains("Encounter", "p1"));
    }
}
