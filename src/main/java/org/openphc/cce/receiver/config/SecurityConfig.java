package org.openphc.cce.receiver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Inbound API security configuration with three modes:
 *
 * <h3>Modes of operation ({@code cce.security.enabled})</h3>
 * <ul>
 *   <li><b>{@code false}</b> (default) — no authentication required on any endpoint.
 *       Suitable for development or when the adaptor sits behind an API gateway
 *       that handles auth.</li>
 *   <li><b>{@code true}</b> — authentication required. Supports <b>both</b>
 *       Basic Auth and OAuth2 JWT simultaneously:
 *       <ul>
 *         <li><b>Basic Auth</b> — always active when enabled. Credentials via
 *             {@code cce.security.username} / {@code cce.security.password}.</li>
 *         <li><b>OAuth2 JWT</b> — additionally active when
 *             {@code cce.security.oauth2.issuer-uri} is set. Validates Bearer
 *             tokens from Keycloak, Azure AD, or any OIDC-compliant IdP.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h3>Always-public endpoints (regardless of enabled flag)</h3>
 * <ul>
 *   <li>{@code /actuator/health/**} — health probes (liveness, readiness)</li>
 *   <li>{@code /actuator/info} — application info</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${cce.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${cce.security.username:cce-client}")
    private String username;

    @Value("${cce.security.password:changeme}")
    private String password;

    @Value("${cce.security.oauth2.issuer-uri:}")
    private String issuerUri;

    @Value("${cce.security.oauth2.required-scope:}")
    private String requiredScope;

    /**
     * Main security filter chain.
     *
     * <p>When {@code cce.security.enabled=false}, all endpoints are open.
     * When {@code true}, supports Basic Auth and optionally OAuth2 JWT.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!securityEnabled) {
            log.info("Inbound API security DISABLED (cce.security.enabled=false) — all endpoints open");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        log.info("Inbound API security ENABLED — Basic Auth active");

        http.authorizeHttpRequests(auth -> auth
                        // Public endpoints — health probes for K8s/Docker
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults());

        // Conditionally enable OAuth2 JWT if issuer-uri is configured
        if (issuerUri != null && !issuerUri.isBlank()) {
            log.info("OAuth2 JWT Resource Server enabled — issuer: {}", issuerUri);
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(Customizer.withDefaults()));
        }

        return http.build();
    }

    /**
     * In-memory user store for Basic Auth.
     * Credentials are configured in application.yml under {@code cce.security.*}.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var user = User.builder()
                .username(username)
                .password(encoder.encode(password))
                .roles("CLIENT")
                .build();

        if (securityEnabled) {
            log.info("Basic Auth configured for user: '{}'", username);
        }
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JWT decoder bean — only connects to the IdP when OAuth2 issuer-uri is configured.
     * Returns a no-op decoder otherwise to prevent startup failures.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (securityEnabled && issuerUri != null && !issuerUri.isBlank()) {
            log.info("Creating JWT decoder for issuer: {}", issuerUri);
            return JwtDecoders.fromIssuerLocation(issuerUri);
        }
        return token -> {
            throw new UnsupportedOperationException(
                    "OAuth2 JWT is not configured — set cce.security.enabled=true and cce.security.oauth2.issuer-uri");
        };
    }
}
