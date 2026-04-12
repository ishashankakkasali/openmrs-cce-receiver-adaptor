package org.openphc.cce.receiver.config;

import org.springframework.stereotype.Component;

/**
 * Holds OpenMRS configuration values that are either discovered at startup
 * from the target OpenMRS instance or provided statically via application.yml.
 *
 * <p>This bean is populated by {@link OpenMrsConfigDiscovery} during startup
 * and consumed by services like {@code PatientIdentifierEnricher} at runtime.
 *
 * <p>The separation from {@link OpenMrsProperties} is intentional:
 * <ul>
 *   <li>{@link OpenMrsProperties} — immutable, bound from YAML at boot time</li>
 *   <li>{@link DiscoveredConfig} — mutable, populated after the app contacts OpenMRS</li>
 * </ul>
 *
 * <p>This allows the adaptor to be deployed <b>without</b> knowing
 * instance-specific UUIDs — only the OpenMRS base URL and credentials are needed.
 */
@Component
public class DiscoveredConfig {

    private volatile String locationUuid;
    private volatile String identifierTypeName;
    private volatile String identifierTypeUuid;
    private volatile String idgenSourceUuid;
    private volatile String visitTypeUuid;
    private volatile java.util.List<String> sourceIdentifierTypes = java.util.List.of();
    private volatile java.util.Map<String, String> identifierTypeUuids = java.util.Map.of();

    /** UUID of the Location to attach to generated patient identifiers. */
    public String getLocationUuid() {
        return locationUuid;
    }

    public void setLocationUuid(String locationUuid) {
        this.locationUuid = locationUuid;
    }

    /** OpenMRS identifier type name (e.g., "OpenMRS ID"). */
    public String getIdentifierTypeName() {
        return identifierTypeName;
    }

    public void setIdentifierTypeName(String identifierTypeName) {
        this.identifierTypeName = identifierTypeName;
    }

    /** UUID of the primary identifier type (e.g., "05a29f94-c0ed-11e2-94be-8c13b969e334" for OpenMRS ID). */
    public String getIdentifierTypeUuid() {
        return identifierTypeUuid;
    }

    public void setIdentifierTypeUuid(String identifierTypeUuid) {
        this.identifierTypeUuid = identifierTypeUuid;
    }

    /** UUID of the idgen identifier source for auto-generation. */
    public String getIdgenSourceUuid() {
        return idgenSourceUuid;
    }

    public void setIdgenSourceUuid(String idgenSourceUuid) {
        this.idgenSourceUuid = idgenSourceUuid;
    }

    /** UUID of the default visit type (e.g., "Facility Visit") used when auto-creating visits. */
    public String getVisitTypeUuid() {
        return visitTypeUuid;
    }

    public void setVisitTypeUuid(String visitTypeUuid) {
        this.visitTypeUuid = visitTypeUuid;
    }

    /** Source identifier type names that should be preserved (e.g., ["NID", "UPI"]). */
    public java.util.List<String> getSourceIdentifierTypes() {
        return sourceIdentifierTypes;
    }

    public void setSourceIdentifierTypes(java.util.List<String> sourceIdentifierTypes) {
        this.sourceIdentifierTypes = sourceIdentifierTypes != null ? sourceIdentifierTypes : java.util.List.of();
    }

    /**
     * Map of identifier type name → UUID for all non-primary types.
     * Used by enricher to set {@code type.coding[0].code} on FHIR identifiers.
     */
    public java.util.Map<String, String> getIdentifierTypeUuids() {
        return identifierTypeUuids;
    }

    public void setIdentifierTypeUuids(java.util.Map<String, String> identifierTypeUuids) {
        this.identifierTypeUuids = identifierTypeUuids != null ? identifierTypeUuids : java.util.Map.of();
    }

    /**
     * Atomically adds a new source identifier type to both the name list and
     * UUID map. Creates new immutable snapshots for thread safety.
     *
     * <p>This method is used by the on-demand auto-creation flow so that
     * newly created identifier types are immediately available to the
     * enricher without requiring an adaptor restart.
     *
     * @param name the identifier type name (e.g., "NID")
     * @param uuid the UUID assigned by OpenMRS
     */
    public synchronized void addSourceIdentifierType(String name, String uuid) {
        java.util.List<String> types = new java.util.ArrayList<>(this.sourceIdentifierTypes);
        if (!types.contains(name)) {
            types.add(name);
            this.sourceIdentifierTypes = java.util.List.copyOf(types);
        }
        java.util.Map<String, String> uuids = new java.util.HashMap<>(this.identifierTypeUuids);
        uuids.put(name, uuid);
        this.identifierTypeUuids = java.util.Map.copyOf(uuids);
    }
}
