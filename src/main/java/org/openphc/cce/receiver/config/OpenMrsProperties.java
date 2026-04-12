package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenMRS server connection.
 *
 * <p>Bound to {@code openmrs.*} keys in application.yml.
 *
 * @param fhir FHIR R4 endpoint configuration
 * @param rest REST API endpoint configuration
 * @param auth Authentication credentials (Basic Auth + optional OAuth2 Client Credentials)
 */
@ConfigurationProperties(prefix = "openmrs")
public record OpenMrsProperties(
        FhirProperties fhir,
        RestProperties rest,
        AuthProperties auth,
        IdentifierProperties identifier
) {

    public record FhirProperties(
            String baseUrl,
            int timeout
    ) {}

    public record RestProperties(
            String baseUrl,
            int timeout
    ) {}

    /**
     * Authentication for outbound calls to OpenMRS.
     *
     * <p>Supports two modes controlled by {@code type}:
     * <ul>
     *   <li>{@code basic} (default) — HTTP Basic Auth using {@code username}/{@code password}</li>
     *   <li>{@code oauth2} — OAuth2 Client Credentials grant via {@code oauth2.*} properties.
     *       Obtains a Bearer token from the IdP (Keycloak/Azure AD) and sends it to OpenMRS.</li>
     * </ul>
     *
     * @param type     auth type: "basic" or "oauth2"
     * @param username Basic Auth username (used when type=basic)
     * @param password Basic Auth password (used when type=basic)
     * @param oauth2   OAuth2 Client Credentials config (used when type=oauth2)
     */
    public record AuthProperties(
            String type,
            String username,
            String password,
            OAuth2ClientProperties oauth2
    ) {
        /**
         * Returns the effective auth type, defaulting to "basic" if not set.
         */
        public String effectiveType() {
            return (type != null && !type.isBlank()) ? type.toLowerCase() : "basic";
        }
    }

    /**
     * OAuth2 Client Credentials configuration for outbound OpenMRS calls.
     *
     * @param tokenUrl     IdP token endpoint (e.g. {@code http://keycloak:8180/realms/openmrs/protocol/openid-connect/token})
     * @param clientId     OAuth2 client ID registered for the adaptor
     * @param clientSecret OAuth2 client secret
     * @param scope        optional scope to request (e.g. "openid")
     */
    public record OAuth2ClientProperties(
            String tokenUrl,
            String clientId,
            String clientSecret,
            String scope
    ) {}

    /**
     * Optional static identifier configuration (fallback when auto-discovery fails).
     *
     * @param locationUuid     preferred identifier location UUID
     * @param idgenSourceUuid  identifier generator source UUID
     * @param typeName         preferred identifier type name (e.g. "OpenMRS ID")
     */
    public record IdentifierProperties(
            String locationUuid,
            String idgenSourceUuid,
            String typeName
    ) {}
}
