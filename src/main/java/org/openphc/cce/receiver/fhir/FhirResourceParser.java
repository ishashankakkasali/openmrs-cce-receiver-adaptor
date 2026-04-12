package org.openphc.cce.receiver.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.openphc.cce.receiver.exception.FhirParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses raw JSON strings into HAPI FHIR {@link IBaseResource} instances
 * and provides serialization back to JSON.
 *
 * <p>Uses the shared {@link FhirContext} singleton for R4 parsing.
 * Throws {@link FhirParsingException} on any parse failure.
 */
@Component
public class FhirResourceParser {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceParser.class);

    private final FhirContext fhirContext;

    public FhirResourceParser(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    /**
     * Parses a FHIR R4 JSON string into an {@link IBaseResource}.
     *
     * @param json the raw FHIR JSON string
     * @return the parsed FHIR resource
     * @throws FhirParsingException if the JSON is null, blank, or cannot be parsed
     */
    public IBaseResource parse(String json) {
        if (json == null || json.isBlank()) {
            throw new FhirParsingException("FHIR resource JSON is null or blank");
        }

        try {
            IParser parser = fhirContext.newJsonParser();
            IBaseResource resource = parser.parseResource(json);
            log.debug("Parsed FHIR resource: resourceType={}, id={}",
                    resource.fhirType(), resource.getIdElement().getIdPart());
            return resource;
        } catch (DataFormatException e) {
            throw new FhirParsingException("Failed to parse FHIR resource JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes a FHIR resource back to JSON.
     *
     * @param resource the FHIR resource
     * @return JSON string representation
     */
    public String encode(IBaseResource resource) {
        return fhirContext.newJsonParser()
                .setPrettyPrint(false)
                .encodeResourceToString(resource);
    }

    /**
     * Checks whether the parsed resource is a FHIR Bundle.
     *
     * @param resource the parsed resource
     * @return true if the resource is a Bundle
     */
    public boolean isBundle(IBaseResource resource) {
        return resource instanceof Bundle;
    }
}
