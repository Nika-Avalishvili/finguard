package dev.finguard.evaluation.runner;

import dev.finguard.config.exception.BadRequestException;
import dev.finguard.config.exception.ResourceNotFoundException;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.evaluation.metrics.CAKRScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Application-layer facade for evaluation reads and mutations.
 *
 * <p>Keeps the REST controller ({@code EvaluationController}) thin: controllers
 * do HTTP binding + validation and delegate all persistence to this service.
 * This restores the presentation → application → domain dependency direction
 * flagged by the architecture audit (controllers were calling
 * {@code repository.deleteById} / {@code saveAll} directly).</p>
 */
@Service
public class EvaluationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationApplicationService.class);

    /** Soft cap on explanations re-scored in a single {@code /rescore} call. */
    private static final int RESCORE_MAX = 500;
    /** Soft cap on IDs deleted in a single bulk-delete call. */
    private static final int BULK_DELETE_MAX_IDS = 1000;

    private final ExperimentResultRepository resultRepository;
    private final ExplanationRepository explanationRepository;
    private final CAKRScorer cakrScorer;

    public EvaluationApplicationService(ExperimentResultRepository resultRepository,
                                         ExplanationRepository explanationRepository,
                                         CAKRScorer cakrScorer) {
        this.resultRepository = resultRepository;
        this.explanationRepository = explanationRepository;
        this.cakrScorer = cakrScorer;
    }

    // ── Read-side ──────────────────────────────────────────────────────────

    public List<String> listExperimentNames() {
        return resultRepository.findDistinctExperimentNames();
    }

    public List<ExperimentResult> getByExperimentName(String experimentName) {
        requireNonBlankName(experimentName);
        return resultRepository.findByExperimentNameOrdered(experimentName);
    }

    public ExperimentResult getById(Long id) {
        return resultRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExperimentResult", "id", id));
    }

    // ── Mutation-side (cache-evicting) ─────────────────────────────────────

    /**
     * Delete a single result. Returns {@code true} if a row was removed,
     * {@code false} if the id didn't exist (lets the controller return 404).
     */
    @CacheEvict(value = "dashboard-stats", allEntries = true)
    public boolean deleteById(Long id) {
        if (!resultRepository.existsById(id)) {
            return false;
        }
        resultRepository.deleteById(id);
        log.info("Deleted experiment result id={}", id);
        return true;
    }

    @CacheEvict(value = "dashboard-stats", allEntries = true)
    public int deleteAll() {
        int n = resultRepository.bulkDeleteAll();
        log.warn("Bulk deleted {} experiment results", n);
        return n;
    }

    @CacheEvict(value = "dashboard-stats", allEntries = true)
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException("At least one id must be provided");
        }
        if (ids.size() > BULK_DELETE_MAX_IDS) {
            throw new BadRequestException("Too many ids in one request (max "
                    + BULK_DELETE_MAX_IDS + ", got " + ids.size() + ")");
        }
        int n = resultRepository.bulkDeleteByIdIn(ids);
        log.warn("Bulk deleted {} experiment results by id list (requested {})", n, ids.size());
        return n;
    }

    @CacheEvict(value = "dashboard-stats", allEntries = true)
    public int deleteByExperimentName(String experimentName) {
        requireNonBlankName(experimentName);
        int n = resultRepository.bulkDeleteByExperimentName(experimentName);
        log.warn("Bulk deleted {} rows for experiment '{}'", n, experimentName);
        return n;
    }

    // ── CAKR re-score workflow (thesis §3.4.2 inter-judge comparison) ──────

    /**
     * Clear CAKR columns on a deterministic sample of explanations, then ask the
     * current judge LLM to re-score them. Returns a report containing the
     * sampled count, the actually-scored count, and wall-clock duration.
     */
    public Map<String, Object> rescoreExplanations(ExplanationType explanationType, int limit) {
        int clamped = Math.min(Math.max(limit, 1), RESCORE_MAX);

        List<Explanation> sample = explanationRepository
                .findAllByExplanationType(explanationType, PageRequest.of(0, clamped))
                .getContent();

        if (sample.isEmpty()) {
            return Map.of(
                    "message", "No explanations found for type " + explanationType,
                    "explanationType", explanationType.name(),
                    "sampled", 0,
                    "rescored", 0);
        }

        for (Explanation e : sample) {
            e.setCakrCompleteness(null);
            e.setCakrActionability(null);
            e.setCakrCorrectness(null);
            e.setCakrRegulatory(null);
        }
        explanationRepository.saveAll(sample);

        long started = System.currentTimeMillis();
        log.info("[rescore] Re-scoring {} {} explanations with current judge", sample.size(), explanationType);
        int scored = cakrScorer.scoreAll(sample);
        long elapsed = System.currentTimeMillis() - started;

        return Map.of(
                "explanationType", explanationType.name(),
                "sampled", sample.size(),
                "rescored", scored,
                "elapsedMs", elapsed);
    }

    /**
     * Read-only slice for Cohen's κ export workflow (thesis §3.4.2 inter-rater
     * agreement). Returns a deterministic sample of explanations plus their
     * current LLM CAKR scores — the controller renders the CSV; this service
     * owns the query.
     */
    public List<Explanation> sampleExplanationsForRating(ExplanationType explanationType, int n) {
        int clamped = Math.min(Math.max(n, 1), 200);
        return explanationRepository
                .findAllByExplanationType(explanationType, PageRequest.of(0, clamped))
                .getContent();
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private static void requireNonBlankName(String experimentName) {
        if (experimentName == null || experimentName.isBlank()) {
            throw new BadRequestException("Experiment name is required");
        }
    }
}
