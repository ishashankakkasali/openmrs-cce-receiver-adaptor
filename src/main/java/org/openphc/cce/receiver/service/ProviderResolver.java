package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps source-system practitioners to OpenMRS providers through the provider {@code identifier}.
 *
 * <p>A practitioner referenced by a source system (e.g. {@code Practitioner/1198503150001234}) is the
 * OpenMRS provider whose identifier is that id, prefixed with {@code external-identifier-prefix} when
 * one is configured (e.g. {@code SPICE:1198503150001234}); with a prefix, providers set up earlier
 * with the bare source id are still found. When none exists,
 * one is created (provider + person, no user account), so every later order from the same
 * practitioner gets the same orderer.
 *
 * <p>OpenMRS provider search ({@code /provider?q=}) matches identifiers and names partially, so every
 * lookup here keeps only an exact, non-retired identifier match — never the first search hit.
 */
@Component
public class ProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderResolver.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String externalIdentifierPrefix;
    private final boolean autoCreateExternal;
    private final String fallbackProviderName;
    private final String fallbackProviderIdentifier;

    /** Provider identifier → provider UUID. Identifiers of providers never change, so no expiry. */
    private final Map<String, String> uuidByIdentifier = new ConcurrentHashMap<>();

    public ProviderResolver(@Qualifier("openmrsRestClient") RestClient restClient,
                            @Value("${openmrs.provider.external-identifier-prefix:}") String externalIdentifierPrefix,
                            @Value("${openmrs.provider.auto-create-external:true}") boolean autoCreateExternal,
                            @Value("${openmrs.provider.fallback-name:Unknown Provider}") String fallbackProviderName,
                            @Value("${openmrs.provider.fallback-identifier:UNKNOWN}") String fallbackProviderIdentifier) {
        this.restClient = restClient;
        this.externalIdentifierPrefix = externalIdentifierPrefix == null ? "" : externalIdentifierPrefix;
        this.autoCreateExternal = autoCreateExternal;
        this.fallbackProviderName = fallbackProviderName;
        this.fallbackProviderIdentifier = fallbackProviderIdentifier;
    }

    /** The provider mapped to a source practitioner id, or {@code null} if there is none. Never creates. */
    public String findExternal(String externalId) {
        if (isBlank(externalId)) return null;
        String uuid = findByIdentifier(externalIdentifierPrefix + externalId);
        if (uuid == null && !externalIdentifierPrefix.isEmpty()) {
            uuid = findByIdentifier(externalId);
        }
        return uuid;
    }

    /**
     * The provider mapped to a source practitioner id, creating it when missing (unless disabled).
     *
     * @param displayName the practitioner's name if the source sent one (e.g. {@code Reference.display})
     */
    public synchronized String findOrCreateExternal(String externalId, String displayName) {
        String uuid = findExternal(externalId);
        if (uuid != null || !autoCreateExternal || isBlank(externalId)) return uuid;

        String identifier = externalIdentifierPrefix + externalId;
        String[] name = personName(displayName, externalId);
        uuid = createProvider(identifier, name[0], name[1]);
        if (uuid != null) {
            log.info("Created provider '{}' for source practitioner {}: {}", String.join(" ", name), identifier, uuid);
        }
        return uuid;
    }

    /** The configured system provider used when an order names no practitioner at all. */
    public synchronized String findOrCreateFallback() {
        String uuid = findByIdentifier(fallbackProviderIdentifier);
        if (uuid != null) return uuid;
        uuid = createProvider(fallbackProviderIdentifier, fallbackProviderName, fallbackProviderIdentifier);
        if (uuid != null) {
            log.info("Created fallback provider '{}' in OpenMRS: {}", fallbackProviderName, uuid);
        }
        return uuid;
    }

    /** Exact, non-retired identifier match. */
    String findByIdentifier(String identifier) {
        if (isBlank(identifier)) return null;
        String cached = uuidByIdentifier.get(identifier);
        if (cached != null) return cached;
        try {
            String response = restClient.get()
                    .uri("/provider?q={identifier}&v=custom:(uuid,identifier,retired)", identifier)
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    if (identifier.equalsIgnoreCase(result.path("identifier").asText(""))
                            && !result.path("retired").asBoolean(false)) {
                        String uuid = result.path("uuid").asText(null);
                        if (uuid != null) uuidByIdentifier.put(identifier, uuid);
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Provider search for identifier '{}' failed: {}", identifier, e.getMessage());
        }
        return null;
    }

    private String createProvider(String identifier, String givenName, String familyName) {
        try {
            ObjectNode personPayload = objectMapper.createObjectNode();
            ObjectNode personName = personPayload.putArray("names").addObject();
            personName.put("givenName", givenName);
            personName.put("familyName", familyName);
            personPayload.put("gender", "U");

            String personUuid = objectMapper.readTree(restClient.post()
                    .uri("/person")
                    .body(personPayload.toString())
                    .retrieve()
                    .body(String.class)).path("uuid").asText(null);
            if (personUuid == null) {
                log.error("Failed to create person for provider '{}'", identifier);
                return null;
            }

            ObjectNode providerPayload = objectMapper.createObjectNode();
            providerPayload.put("identifier", identifier);
            providerPayload.put("person", personUuid);

            String uuid = objectMapper.readTree(restClient.post()
                    .uri("/provider")
                    .body(providerPayload.toString())
                    .retrieve()
                    .body(String.class)).path("uuid").asText(null);
            if (uuid != null) uuidByIdentifier.put(identifier, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create provider '{}': {}", identifier, e.getMessage());
            return null;
        }
    }

    /**
     * Person name for a new provider: the source's display name when sent ("Hana Bekele" → given
     * "Hana", family "Bekele"), otherwise "External practitioner" (or "&lt;prefix&gt; practitioner") + the
     * source id.
     */
    private String[] personName(String displayName, String externalId) {
        if (!isBlank(displayName)) {
            String trimmed = displayName.trim();
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                return new String[] {trimmed.substring(0, space), trimmed.substring(space + 1).trim()};
            }
            return new String[] {trimmed, sourceLabel()};
        }
        return new String[] {sourceLabel() + " practitioner", externalId};
    }

    private String sourceLabel() {
        String label = externalIdentifierPrefix.replaceAll("[:\\-_/]+$", "");
        return label.isBlank() ? "External" : label;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
