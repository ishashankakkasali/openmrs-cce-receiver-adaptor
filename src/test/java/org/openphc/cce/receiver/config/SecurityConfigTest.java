package org.openphc.cce.receiver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests verifying the security filter chain in both enabled and disabled modes.
 */
class SecurityConfigTest {

    private static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    /**
     * Tests when security is DISABLED (cce.security.enabled=false).
     * All endpoints should be accessible without authentication.
     */
    @Nested
    @DisplayName("Security disabled (default)")
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class SecurityDisabledTest {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("POST /api/v1/fhir — accessible without auth when security disabled")
        void fhirEndpoint_noAuth_accepted() throws Exception {
            mockMvc.perform(post("/api/v1/fhir")
                            .contentType("application/fhir+json")
                            .content("{\"resourceType\":\"Patient\"}"))
                    .andExpect(status().is2xxSuccessful());
        }

        @Test
        @DisplayName("GET /actuator/health — accessible without auth")
        void healthEndpoint_noAuth_returns200() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /api/v1/capabilities — accessible without auth when security disabled")
        void capabilitiesEndpoint_noAuth_accessible() throws Exception {
            mockMvc.perform(get("/api/v1/capabilities"))
                    .andExpect(status().isOk());
        }
    }

    /**
     * Tests when security is ENABLED (cce.security.enabled=true).
     * Protected endpoints require Basic Auth credentials.
     */
    @Nested
    @DisplayName("Security enabled — Basic Auth required")
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = "cce.security.enabled=true")
    class SecurityEnabledTest {

        @Autowired
        private MockMvc mockMvc;

        private static final String TEST_USER = "test-user";
        private static final String TEST_PASS = "test-pass";

        // === Public endpoints (always accessible) ===

        @Test
        @DisplayName("GET /actuator/health — accessible without auth")
        void healthEndpoint_noAuth_returns200() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /actuator/health/liveness — accessible without auth")
        void livenessProbe_noAuth_returns200() throws Exception {
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /actuator/health/readiness — accessible without auth")
        void readinessProbe_noAuth_returns200() throws Exception {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk());
        }

        // === Protected endpoints ===

        @Test
        @DisplayName("POST /api/v1/fhir without auth — returns 401")
        void fhirEndpoint_noAuth_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/fhir")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resourceType\":\"Patient\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/v1/fhir with valid Basic Auth — accepted")
        void fhirEndpoint_validAuth_accepted() throws Exception {
            mockMvc.perform(post("/api/v1/fhir")
                            .header("Authorization", basicAuth(TEST_USER, TEST_PASS))
                            .contentType("application/fhir+json")
                            .content("{\"resourceType\":\"Patient\"}"))
                    .andExpect(status().is2xxSuccessful());
        }

        @Test
        @DisplayName("POST /api/v1/fhir with wrong password — returns 401")
        void fhirEndpoint_wrongPassword_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/fhir")
                            .header("Authorization", basicAuth(TEST_USER, "wrong"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resourceType\":\"Patient\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /api/v1/fhir with unknown user — returns 401")
        void fhirEndpoint_unknownUser_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/fhir")
                            .header("Authorization", basicAuth("hacker", "password"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resourceType\":\"Patient\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/v1/capabilities without auth — returns 401")
        void capabilitiesEndpoint_noAuth_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/capabilities"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /actuator/metrics without auth — returns 401")
        void metricsEndpoint_noAuth_returns401() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /actuator/metrics with valid auth — accessible")
        void metricsEndpoint_validAuth_returns200() throws Exception {
            mockMvc.perform(get("/actuator/metrics")
                            .header("Authorization", basicAuth(TEST_USER, TEST_PASS)))
                    .andExpect(status().isOk());
        }
    }
}
