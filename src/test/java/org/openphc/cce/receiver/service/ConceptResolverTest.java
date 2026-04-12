package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import org.openphc.cce.receiver.exception.ResourceTransformException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConceptResolver}.
 */
@ExtendWith(MockitoExtension.class)
class ConceptResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ConceptResolver conceptResolver;

    @BeforeEach
    void setUp() {
        conceptResolver = new ConceptResolver(restClient, objectMapper);
    }

    // ======================== resolve(JsonNode codingNode) ========================

    @Test
    void shouldReturnCodeDirectlyWhenOpenMrsSystemPresent() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [
                    {"system": "http://loinc.org", "code": "85354-9", "display": "Blood Pressure"},
                    {"system": "http://openmrs.org/concepts", "code": "5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
                ]
                """);

        String result = conceptResolver.resolve(coding);

        assertEquals("5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", result);
    }

    @Test
    void shouldReturnCodeWhenItLooksLikeUuid() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [
                    {"system": "http://loinc.org", "code": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"}
                ]
                """);

        String result = conceptResolver.resolve(coding);

        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryOpenMrsApiForLoincCode() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [
                    {"system": "http://loinc.org", "code": "85354-9", "display": "Blood Pressure"}
                ]
                """);

        // Mock the REST client chain
        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": [{"uuid": "resolved-uuid-001", "display": "Blood Pressure"}]}
                """);

        String result = conceptResolver.resolve(coding);

        assertEquals("resolved-uuid-001", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCacheResolvedConcepts() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "http://loinc.org", "code": "85354-9"}]
                """);

        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": [{"uuid": "cached-uuid-002"}]}
                """);

        // First call — should query
        String result1 = conceptResolver.resolve(coding);
        assertEquals("cached-uuid-002", result1);
        assertEquals(1, conceptResolver.cacheSize());

        // Second call — should use cache
        String result2 = conceptResolver.resolve(coding);
        assertEquals("cached-uuid-002", result2);

        // restClient.get() should only be called once
        verify(restClient, times(1)).get();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldThrowWhenLookupReturnsEmpty() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "http://loinc.org", "code": "UNKNOWN-CODE"}]
                """);

        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": []}
                """);

        ResourceTransformException ex = assertThrows(ResourceTransformException.class,
                () -> conceptResolver.resolve(coding));

        assertTrue(ex.getMessage().contains("UNKNOWN-CODE"));
        assertTrue(ex.getMessage().contains("Unresolvable concept"));
    }

    @Test
    void shouldPreferOpenMrsCodingOverOtherSystems() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [
                    {"system": "http://snomed.info/sct", "code": "75367002"},
                    {"system": "http://openmrs.org/concepts", "code": "my-openmrs-uuid"}
                ]
                """);

        String result = conceptResolver.resolve(coding);

        // Should pick the OpenMRS coding without querying the API
        assertEquals("my-openmrs-uuid", result);
        verifyNoInteractions(restClient);
    }

    @Test
    void shouldReturnNullForNullOrEmptyCoding() throws Exception {
        assertNull(conceptResolver.resolve((JsonNode) null));
        assertNull(conceptResolver.resolve(objectMapper.readTree("[]")));
    }

    @Test
    void shouldReturnRawCodeWhenSystemIsUnknown() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "http://unknown-system.org", "code": "some-code"}]
                """);

        // Unknown system, not a UUID → should throw since it can't be resolved
        ResourceTransformException ex = assertThrows(ResourceTransformException.class,
                () -> conceptResolver.resolve(coding));

        assertTrue(ex.getMessage().contains("some-code"));
        assertTrue(ex.getMessage().contains("Unresolvable concept"));
    }

    // ======================== resolve(String system, String code) ========================

    @Test
    void shouldReturnCodeAsIsWhenAlreadyUuid() {
        String result = conceptResolver.resolve("http://loinc.org",
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890");

        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", result);
    }

    @Test
    void shouldReturnCodeFromOpenMrsSystemDirectly() {
        String result = conceptResolver.resolve("http://openmrs.org/concepts", "some-concept");

        assertEquals("some-concept", result);
    }

    @Test
    void shouldReturnNullForNullCode() {
        assertNull(conceptResolver.resolve("http://loinc.org", null));
    }

    @Test
    void shouldClearCache() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "http://openmrs.org/concepts", "code": "test-uuid"}]
                """);
        conceptResolver.resolve(coding);

        conceptResolver.clearCache();

        assertEquals(0, conceptResolver.cacheSize());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryForSnomedCode() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "http://snomed.info/sct", "code": "75367002"}]
                """);

        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": [{"uuid": "snomed-resolved-uuid"}]}
                """);

        String result = conceptResolver.resolve(coding);

        assertEquals("snomed-resolved-uuid", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryForCielCode() throws Exception {
        JsonNode coding = objectMapper.readTree("""
                [{"system": "https://openconceptlab.org/orgs/CIEL/sources/CIEL", "code": "5085"}]
                """);

        RestClient.RequestHeadersUriSpec mockGet = mock(RestClient.RequestHeadersUriSpec.class);
        when(restClient.get()).thenReturn(mockGet);
        when(mockGet.uri(anyString(), any(), any())).thenReturn(mockGet);
        when(mockGet.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn("""
                {"results": [{"uuid": "ciel-resolved-uuid"}]}
                """);

        String result = conceptResolver.resolve(coding);

        assertEquals("ciel-resolved-uuid", result);
    }
}
