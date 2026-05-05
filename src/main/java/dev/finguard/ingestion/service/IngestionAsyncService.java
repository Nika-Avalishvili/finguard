package dev.finguard.ingestion.service;

import dev.finguard.ingestion.dto.ImportResult;
import dev.finguard.ingestion.dto.IngestionJob;
import dev.finguard.ingestion.dto.IngestionJob.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a PaySim CSV import in the background via Spring's async executor.
 *
 * <p>This class exists as a separate bean so that calling
 * {@link IngestionService#importPaySimCsv(Path, IngestionJob)} goes through
 * the Spring proxy — ensuring {@code @Transactional} is applied correctly
 * without requiring self-injection in {@code IngestionService}.</p>
 *
 * <p>Sequence:
 * <ol>
 *   <li>Controller creates a job via {@link IngestionJobStore} and returns 202.</li>
 *   <li>This class runs the import on a background thread.</li>
 *   <li>Callers poll {@code GET /api/v1/ingestion/status/{jobId}} for progress.</li>
 * </ol>
 * </p>
 */
@Service
public class IngestionAsyncService {

    private static final Logger log = LoggerFactory.getLogger(IngestionAsyncService.class);

    private final IngestionService ingestionService;
    private final IngestionJobStore jobStore;
    public IngestionAsyncService(IngestionService ingestionService,
                                  IngestionJobStore jobStore) {
        this.ingestionService = ingestionService;
        this.jobStore = jobStore;
    }

    /**
     * Execute the import pipeline asynchronously.
     *
     * @param jobId           the job ID returned to the client in the 202 response
     * @param csvPath         path to the CSV file to import
     * @param deleteTempAfter if true, delete the file after import completes (for uploaded temp files)
     */
    @Async
    public void runImportAsync(String jobId, Path csvPath, boolean deleteTempAfter) {
        IngestionJob job = jobStore.find(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        log.info("[job={}] Async import started: {}", jobId, csvPath);
        job.setPhase(Phase.IMPORTING);
        job.markRunning();

        try {
            ImportResult result = ingestionService.importPaySimCsv(csvPath, job);

            if (job.isCancelRequested()) {
                job.markCancelled();
                log.info("[job={}] Import cancelled — {} rows processed before stop", jobId, String.format("%,d", result.getTotalRows()));
                return;
            }

            job.markDone(result.getSuccessCount(), result.getSkippedCount(),
                         result.getTotalRows(), 0);
            log.info("[job={}] Import done — {} imported, {} skipped in {}s",
                    jobId, result.getSuccessCount(), result.getSkippedCount(), job.getElapsedSeconds());

        } catch (Exception e) {
            log.error("[job={}] Import failed: {}", jobId, e.getMessage(), e);
            job.markFailed(e.getMessage());
        } finally {
            if (deleteTempAfter) {
                try {
                    Files.deleteIfExists(csvPath);
                } catch (IOException ignored) {
                    log.warn("[job={}] Could not delete temp file: {}", jobId, csvPath);
                }
            }
        }
    }
}