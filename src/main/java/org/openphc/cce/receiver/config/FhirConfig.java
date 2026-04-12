package org.openphc.cce.receiver.config;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a singleton {@link FhirContext} bean for FHIR R4 resource parsing.
 *
 * <p>{@code FhirContext} is expensive to create — this configuration ensures a single
 * shared instance is used across the entire application.
 */
@Configuration
public class FhirConfig {

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }
}
