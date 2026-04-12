package org.openphc.cce.receiver.config;

import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures {@link RestClient} beans for outbound HTTP communication with OpenMRS.
 *
 * <p>Two named beans are created:
 * <ul>
 *   <li>{@code fhirRestClient} — targets the OpenMRS FHIR R4 endpoint</li>
 *   <li>{@code openmrsRestClient} — targets the OpenMRS REST API v1 endpoint</li>
 * </ul>
 *
 * <p>Authentication mode is controlled by {@code openmrs.auth.type}:
 * <ul>
 *   <li>{@code basic} (default) — HTTP Basic Auth using static credentials</li>
 *   <li>{@code oauth2} — OAuth2 Client Credentials grant, Bearer token injected per-request
 *       via {@link OAuth2TokenProvider}</li>
 * </ul>
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /**
     * Creates a {@link RestClient} for the OpenMRS FHIR R4 endpoint.
     *
     * @param properties    OpenMRS configuration properties
     * @param tokenProvider OAuth2 token provider (used when auth type is "oauth2")
     * @return configured RestClient for FHIR communication
     */
    @Bean
    @Qualifier("fhirRestClient")
    public RestClient fhirRestClient(OpenMrsProperties properties, OAuth2TokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.fhir().timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.fhir().timeout()));

        var builder = RestClient.builder()
                .baseUrl(properties.fhir().baseUrl())
                .defaultHeader("Content-Type", "application/fhir+json")
                .defaultHeader("Accept", "application/fhir+json")
                .requestFactory(requestFactory);

        configureAuth(builder, properties, tokenProvider, "FHIR");
        return builder.build();
    }

    /**
     * Creates a {@link RestClient} for the OpenMRS REST API v1 endpoint.
     *
     * @param properties    OpenMRS configuration properties
     * @param tokenProvider OAuth2 token provider (used when auth type is "oauth2")
     * @return configured RestClient for REST API communication
     */
    @Bean
    @Qualifier("openmrsRestClient")
    public RestClient openmrsRestClient(OpenMrsProperties properties, OAuth2TokenProvider tokenProvider) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.rest().timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.rest().timeout()));

        var builder = RestClient.builder()
                .baseUrl(properties.rest().baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory);

        configureAuth(builder, properties, tokenProvider, "REST");
        return builder.build();
    }

    /**
     * Configures authentication on the RestClient builder based on the auth type.
     *
     * <p>For Basic Auth: sets a static Authorization header.
     * <p>For OAuth2: adds a request interceptor that injects a fresh Bearer
     * token on every request (handles token refresh automatically).
     */
    private void configureAuth(RestClient.Builder builder, OpenMrsProperties properties,
                               OAuth2TokenProvider tokenProvider, String clientName) {
        String authType = properties.auth().effectiveType();

        if ("oauth2".equals(authType)) {
            log.info("OpenMRS {} client: using OAuth2 Bearer token authentication", clientName);
            builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().set("Authorization", tokenProvider.getAuthorizationHeader());
                return execution.execute(request, body);
            });
        } else {
            log.info("OpenMRS {} client: using Basic Auth (user: {})", clientName, properties.auth().username());
            builder.defaultHeader("Authorization", basicAuth(properties));
        }
    }

    private String basicAuth(OpenMrsProperties properties) {
        String credentials = properties.auth().username() + ":" + properties.auth().password();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
