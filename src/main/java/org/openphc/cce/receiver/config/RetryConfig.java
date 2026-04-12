package org.openphc.cce.receiver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry for the application.
 *
 * <p>Used by {@code OpenMrsFhirClient} and {@code OpenMrsRestClient} to retry
 * transient failures (5xx, timeouts) with exponential backoff.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
