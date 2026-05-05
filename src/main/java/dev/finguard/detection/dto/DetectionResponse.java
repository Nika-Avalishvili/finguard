package dev.finguard.detection.dto;

import dev.finguard.domain.enums.DetectionConfig;

/**
 * REST API response for batch detection operations.
 */
public record DetectionResponse(
        DetectionConfig config,
        long alertsCreated,
        long transactionsProcessed,
        long durationSeconds
) {
}
