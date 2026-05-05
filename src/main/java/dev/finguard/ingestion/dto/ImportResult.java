package dev.finguard.ingestion.dto;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Summary of a dataset import operation.
 * Tracks successes, skipped rows, and individual error details.
 */
public class ImportResult {

    private long successCount;
    private long skippedCount;
    private long totalRows;
    private Duration duration;
    private final List<ImportError> errors = new ArrayList<>();

    public void incrementSuccess() {
        successCount++;
    }

    public void addError(long rowNumber, String reason) {
        skippedCount++;
        // Keep at most 100 individual errors to prevent memory issues on bad files
        if (errors.size() < 100) {
            errors.add(new ImportError(rowNumber, reason));
        }
    }

    // Getters and setters

    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }

    public long getSkippedCount() { return skippedCount; }
    public void setSkippedCount(long skippedCount) { this.skippedCount = skippedCount; }

    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }

    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }

    public List<ImportError> getErrors() { return errors; }

    /**
     * Individual import error with row number and reason.
     */
    public record ImportError(long rowNumber, String reason) {}
}
