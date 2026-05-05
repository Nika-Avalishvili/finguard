package dev.finguard.presentation.dto;

import dev.finguard.ingestion.dto.IngestionJob;

/**
 * REST response for GET /api/v1/ingestion/status/{jobId}.
 * Snapshots the live {@link IngestionJob} state at the moment of the request.
 */
public record IngestionStatusResponse(
        String jobId,
        String status,
        String phase,
        long totalRows,
        long processedRows,
        long importedRows,
        long skippedRows,
        long featuresComputed,
        double percentComplete,
        long elapsedSeconds,
        String errorMessage
) {
    public static IngestionStatusResponse from(IngestionJob job) {
        return new IngestionStatusResponse(
                job.getJobId(),
                job.getStatus().name(),
                job.getPhase() != null ? job.getPhase().name() : null,
                job.getTotalRows(),
                job.getProcessedRows(),
                job.getImportedRows(),
                job.getSkippedRows(),
                job.getFeaturesComputed(),
                Math.round(job.getPercentComplete() * 10.0) / 10.0,
                job.getElapsedSeconds(),
                job.getErrorMessage()
        );
    }
}
