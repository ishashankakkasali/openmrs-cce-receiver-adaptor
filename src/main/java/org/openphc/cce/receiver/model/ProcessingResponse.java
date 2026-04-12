package org.openphc.cce.receiver.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Response payload returned by the receiver adaptor after processing an inbound
 * FHIR payload. Contains per-resource routing results and summary statistics.
 *
 * @param timestamp    when the processing completed
 * @param totalEntries total number of resource entries processed
 * @param succeeded    count of successfully routed entries
 * @param failed       count of failed entries
 * @param results      per-resource routing results
 */
public record ProcessingResponse(
        String timestamp,
        int totalEntries,
        int succeeded,
        int failed,
        List<RoutingResult> results
) {

    public static ProcessingResponse from(List<RoutingResult> results) {
        int succeeded = (int) results.stream().filter(RoutingResult::isSuccess).count();
        int failed = results.size() - succeeded;

        return new ProcessingResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                results.size(),
                succeeded,
                failed,
                results
        );
    }
}
