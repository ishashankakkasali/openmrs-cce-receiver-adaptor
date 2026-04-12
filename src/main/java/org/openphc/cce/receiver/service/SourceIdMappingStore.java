package org.openphc.cce.receiver.service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory store that maps source-system resource IDs
 * to OpenMRS-assigned UUIDs.
 *
 * <p>When the CCE Receiver creates a resource in OpenMRS for the first time,
 * the mapping {@code (resourceType, sourceId) → openMrsUuid} is stored here.
 * On subsequent requests carrying the same source ID, the adaptor looks up
 * the OpenMRS UUID and performs an update (PUT) instead of creating a duplicate.
 *
 * <p><b>Lifecycle:</b> This store is in-memory and will be cleared on application restart.
 * For production deployments, consider backing it with a persistent store (e.g., embedded DB).
 */
@Component
public class SourceIdMappingStore {

    private static final Logger log = LoggerFactory.getLogger(SourceIdMappingStore.class);

    private final ConcurrentHashMap<String, String> mappings = new ConcurrentHashMap<>();

    /**
     * Looks up the OpenMRS UUID for a previously created resource.
     *
     * @param resourceType FHIR resource type (e.g. "Patient", "Encounter")
     * @param sourceId     the source system's ID for the resource
     * @return the OpenMRS UUID if a mapping exists, empty otherwise
     */
    public Optional<String> getOpenMrsUuid(String resourceType, String sourceId) {
        String key = key(resourceType, sourceId);
        return Optional.ofNullable(mappings.get(key));
    }

    /**
     * Stores a mapping from a source-system ID to the OpenMRS UUID
     * assigned when the resource was created.
     *
     * @param resourceType FHIR resource type
     * @param sourceId     the source system's ID
     * @param openMrsUuid  the UUID assigned by OpenMRS
     */
    public void store(String resourceType, String sourceId, String openMrsUuid) {
        String key = key(resourceType, sourceId);
        mappings.put(key, openMrsUuid);
        log.info("Stored source-ID mapping: {} → {}", key, openMrsUuid);
    }

    /**
     * Checks whether a mapping exists for the given source ID.
     */
    public boolean contains(String resourceType, String sourceId) {
        return mappings.containsKey(key(resourceType, sourceId));
    }

    /**
     * Returns the number of stored mappings.
     */
    public int size() {
        return mappings.size();
    }

    /**
     * Clears all stored mappings. Mainly useful for testing.
     */
    public void clear() {
        mappings.clear();
    }

    private String key(String resourceType, String sourceId) {
        return resourceType + ":" + sourceId;
    }
}
