package org.openphc.cce.receiver.config;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Obtains and caches OAuth2 Bearer tokens for outbound calls to OpenMRS.
 *
 * <p>Uses the <b>Client Credentials</b> grant type (RFC 6749 §4.4) to
 * authenticate with the configured Identity Provider (Keycloak, Azure AD, etc.)
 * and obtain an access token that OpenMRS accepts via {@code Authorization: Bearer}.
 *
 * <h3>Token lifecycle</h3>
 * <ul>
 *   <li>Tokens are cached in memory until 30 seconds before expiry</li>
 *   <li>Refresh happens lazily on the next {@link #getAccessToken()} call</li>
 *   <li>Thread-safe via synchronized access</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * Reads from {@code openmrs.auth.oauth2.*} in application.yml:
 * <ul>
 *   <li>{@code token-url} — IdP token endpoint (e.g. {@code http://keycloak:8180/realms/openmrs/protocol/openid-connect/token})</li>
 *   <li>{@code client-id} — OAuth2 client registered for the adaptor</li>
 *   <li>{@code client-secret} — client secret</li>
 *   <li>{@code scope} — optional scope to request (e.g. "openid")</li>
 * </ul>
 */
@Component
public class OAuth2TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenProvider.class);

    /**
     * Refresh the token 30 seconds before actual expiry to prevent
     * rejected calls during the refresh window.
     */
    private static final int EXPIRY_BUFFER_SECONDS = 30;

    private final OpenMrsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cached token state (guarded by synchronized)
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public OAuth2TokenProvider(OpenMrsProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns {@code true} if OAuth2 is configured for outbound OpenMRS calls.
     */
    public boolean isOAuth2Configured() {
        var oauth2 = properties.auth().oauth2();
        return oauth2 != null
                && oauth2.tokenUrl() != null && !oauth2.tokenUrl().isBlank()
                && oauth2.clientId() != null && !oauth2.clientId().isBlank();
    }

    /**
     * Returns a valid Bearer access token, fetching or refreshing as needed.
     *
     * @return the access token string (without "Bearer " prefix)
     * @throws IllegalStateException if OAuth2 is not configured
     * @throws RuntimeException if token fetch fails
     */
    public synchronized String getAccessToken() {
        if (!isOAuth2Configured()) {
            throw new IllegalStateException(
                    "OAuth2 not configured for outbound calls — set openmrs.auth.oauth2.token-url and client credentials");
        }

        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        log.info("Fetching OAuth2 access token from: {}", properties.auth().oauth2().tokenUrl());
        fetchNewToken();
        return cachedToken;
    }

    private void fetchNewToken() {
        var oauth2 = properties.auth().oauth2();

        var formData = new LinkedMultiValueMap<String, String>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", oauth2.clientId());
        formData.add("client_secret", oauth2.clientSecret());
        if (oauth2.scope() != null && !oauth2.scope().isBlank()) {
            formData.add("scope", oauth2.scope());
        }

        try {
            String responseBody = RestClient.create()
                    .post()
                    .uri(oauth2.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(String.class);

            JsonNode json = objectMapper.readTree(responseBody);

            cachedToken = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 300;
            tokenExpiry = Instant.now().plusSeconds(expiresIn - EXPIRY_BUFFER_SECONDS);

            log.info("OAuth2 token obtained — expires in {}s (effective: {}s)",
                    expiresIn, expiresIn - EXPIRY_BUFFER_SECONDS);

        } catch (Exception e) {
            cachedToken = null;
            tokenExpiry = Instant.EPOCH;
            throw new RuntimeException("Failed to obtain OAuth2 access token from " + oauth2.tokenUrl(), e);
        }
    }

    /**
     * Returns the full {@code Authorization} header value: {@code "Bearer <token>"}.
     */
    public String getAuthorizationHeader() {
        return "Bearer " + getAccessToken();
    }
}
