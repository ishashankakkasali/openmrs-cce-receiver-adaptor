package org.openphc.cce.receiver.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpenMrsConfigDiscovery}, focusing on the on-demand
 * identifier type creation via {@code ensureIdentifierTypeExists}.
 */
@ExtendWith(MockitoExtension.class)
class OpenMrsConfigDiscoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestGetSpec;

    @Mock
    private RestClient.RequestBodyUriSpec requestPostSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private DiscoveredConfig discoveredConfig;
    private OpenMrsConfigDiscovery discovery;

    @BeforeEach
    void setUp() {
        discoveredConfig = new DiscoveredConfig();
        discoveredConfig.setIdentifierTypeName("OpenMRS ID");
        discoveredConfig.setIdentifierTypeUuid("05a29f94-c0ed-11e2-94be-8c13b969e334");
        discoveredConfig.setSourceIdentifierTypes(java.util.List.of());
        discoveredConfig.setIdentifierTypeUuids(java.util.Map.of());

        OpenMrsProperties properties = new OpenMrsProperties(null, null, null, null);
        discovery = new OpenMrsConfigDiscovery(restClient, properties, objectMapper, discoveredConfig);
    }

    @Test
    void shouldReturnExistingUuidWhenTypeAlreadyDiscovered() {
        // Pre-populate config with NID
        discoveredConfig.addSourceIdentifierType("NID", "uuid-nid-existing");

        String result = discovery.ensureIdentifierTypeExists("NID");

        assertEquals("uuid-nid-existing", result);
        // Should NOT call REST API
        verifyNoInteractions(restClient);
    }

    @Test
    void shouldReturnPrimaryTypeUuidWhenRequestedByName() {
        String result = discovery.ensureIdentifierTypeExists("OpenMRS ID");

        assertEquals("05a29f94-c0ed-11e2-94be-8c13b969e334", result);
        verifyNoInteractions(restClient);
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldFindExistingTypeOnReQuery() throws Exception {
        // Simulate: type exists in OpenMRS but wasn't discovered at startup
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {
                  "results": [
                    {"name": "OpenMRS ID", "uuid": "05a29f94-c0ed", "retired": false},
                    {"name": "NID", "uuid": "uuid-nid-found", "retired": false}
                  ]
                }
                """);

        String result = discovery.ensureIdentifierTypeExists("NID");

        assertEquals("uuid-nid-found", result);
        assertTrue(discoveredConfig.getSourceIdentifierTypes().contains("NID"));
        assertEquals("uuid-nid-found", discoveredConfig.getIdentifierTypeUuids().get("NID"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldCreateTypeWhenNotFoundAnywhere() throws Exception {
        // Mock GET — returns empty (type doesn't exist)
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": [{"name": "OpenMRS ID", "uuid": "primary-uuid", "retired": false}]}
                """);

        // Mock POST — create returns new UUID
        RestClient.RequestBodyUriSpec mockPost = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec mockBody = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(mockPost);
        when(mockPost.uri(anyString())).thenReturn(mockBody);
        when(mockBody.header(anyString(), anyString())).thenReturn(mockBody);
        when(mockBody.body(anyString())).thenReturn(mockBody);
        when(mockBody.retrieve()).thenReturn(postResponse);
        when(postResponse.body(String.class)).thenReturn("""
                {"uuid": "uuid-upi-created", "name": "UPI", "display": "UPI"}
                """);

        String result = discovery.ensureIdentifierTypeExists("UPI");

        assertEquals("uuid-upi-created", result);
        assertTrue(discoveredConfig.getSourceIdentifierTypes().contains("UPI"));
        assertEquals("uuid-upi-created", discoveredConfig.getIdentifierTypeUuids().get("UPI"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldReturnNullWhenBothFindAndCreateFail() throws Exception {
        // Mock GET — throws exception
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenThrow(new RuntimeException("Connection refused"));

        // Mock POST — also throws
        RestClient.RequestBodyUriSpec mockPost = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec mockBody = mock(RestClient.RequestBodySpec.class);
        when(restClient.post()).thenReturn(mockPost);
        when(mockPost.uri(anyString())).thenReturn(mockBody);
        when(mockBody.header(anyString(), anyString())).thenReturn(mockBody);
        when(mockBody.body(anyString())).thenReturn(mockBody);
        when(mockBody.retrieve()).thenThrow(new RuntimeException("Connection refused"));

        String result = discovery.ensureIdentifierTypeExists("UNKNOWN_TYPE");

        assertNull(result);
        assertFalse(discoveredConfig.getSourceIdentifierTypes().contains("UNKNOWN_TYPE"));
    }

    @Test
    void shouldNotDuplicateTypesOnConcurrentCalls() {
        // Pre-populate NID
        discoveredConfig.addSourceIdentifierType("NID", "uuid-nid");

        // Both calls should return the same UUID without REST calls
        String r1 = discovery.ensureIdentifierTypeExists("NID");
        String r2 = discovery.ensureIdentifierTypeExists("NID");

        assertEquals("uuid-nid", r1);
        assertEquals("uuid-nid", r2);
        assertEquals(1, discoveredConfig.getSourceIdentifierTypes().size());
        verifyNoInteractions(restClient);
    }
}
