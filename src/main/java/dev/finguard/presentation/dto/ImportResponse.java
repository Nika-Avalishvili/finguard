package dev.finguard.presentation.dto;

import dev.finguard.ingestion.dto.ImportResult;

import java.util.List;

/**
 * REST API response for dataset import operations.
 * Never exposes internal paths or entity details — only summary data.
 */
public record ImportResponse(
        long successCount,
        long skippedCount,
        long totalRows,
        long durationSeconds,
        long featuresComputed,
        List<ErrorEntry> errors
) {

    public record ErrorEntry(long rowNumber, String reason) {}

    public static ImportResponse from(ImportResult result, long featuresComputed) {
        List<ErrorEntry> errors = result.getErrors().stream()
                .map(e -> new ErrorEntry(e.rowNumber(), e.reason()))
                .toList();

        return new ImportResponse(
                result.getSuccessCount(),
                result.getSkippedCount(),
                result.getTotalRows(),
                result.getDuration() != null ? result.getDuration().toSeconds() : 0,
                featuresComputed,
                errors
        );
    }
}
