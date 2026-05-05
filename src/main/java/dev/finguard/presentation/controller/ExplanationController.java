package dev.finguard.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.config.exception.ResourceNotFoundException;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.llm.ExplanationResponse;
import dev.finguard.explanation.llm.LLMExplanationService;
import dev.finguard.explanation.validation.HallucinationValidator;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.explanation.llm.ExplanationBatchAsyncService;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for LLM-based explanation generation and retrieval.
 *
 * <p>Provides endpoints to generate explanations for individual alerts,
 * generate explanations in batch, and retrieve existing explanations.</p>
 */
@RestController
@RequestMapping("/api/v1/explanations")
@Tag(name = "Explanations", description = "LLM-based fraud explanation generation and retrieval")
public class ExplanationController {

    private static final Logger log = LoggerFactory.getLogger(ExplanationController.class);

    private final LLMExplanationService llmService;
    private final HallucinationValidator hallucinationValidator;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final ObjectMapper objectMapper;
    private final ExplanationBatchAsyncService batchAsyncService;
    private final PipelineStatusTracker statusTracker;

    public ExplanationController(LLMExplanationService llmService,
                                  HallucinationValidator hallucinationValidator,
                                  AlertRepository alertRepository,
                                  ExplanationRepository explanationRepository,
                                  TransactionFeaturesRepository featuresRepository,
                                  ObjectMapper objectMapper,
                                  ExplanationBatchAsyncService batchAsyncService,
                                  PipelineStatusTracker statusTracker) {
        this.llmService = llmService;
        this.hallucinationValidator = hallucinationValidator;
        this.alertRepository = alertRepository;
        this.explanationRepository = explanationRepository;
        this.featuresRepository = featuresRepository;
        this.objectMapper = objectMapper;
        this.batchAsyncService = batchAsyncService;
        this.statusTracker = statusTracker;
    }

    /**
     * Generate a direct LLM explanation for an alert (no RAG context).
     */
    @PostMapping("/generate/direct/{alertId}")
    @Operation(summary = "Generate LLM_DIRECT explanation",
               description = "Sends transaction data and detection signals to the LLM without RAG context")
    public ResponseEntity<ExplanationDto> generateDirect(@PathVariable Long alertId) {
        return generateForAlert(alertId, false);
    }

    /**
     * Generate a RAG-augmented LLM explanation for an alert.
     */
    @PostMapping("/generate/rag/{alertId}")
    @Operation(summary = "Generate LLM_RAG explanation",
               description = "Enriches the prompt with fraud pattern context from pgvector before calling the LLM")
    public ResponseEntity<ExplanationDto> generateRag(@PathVariable Long alertId) {
        return generateForAlert(alertId, true);
    }

    /**
     * Asynchronously generate explanations for up to {@code limit} alerts that don't have one yet.
     * Returns 202 Accepted immediately; poll {@code GET /api/v1/explanations/batch-status/{jobId}}.
     */
    @PostMapping("/generate/batch")
    @Operation(summary = "Batch-generate explanations for alerts (async)",
               description = "Dispatches LLM explanation generation to a background thread. " +
                             "Scoped to the given detectionConfig so explanations link to the right alert cohort. " +
                             "Poll batch-status/{jobId} for progress.")
    public ResponseEntity<Map<String, String>> generateBatch(
            @RequestParam(defaultValue = "ML_LLM_RAG")
            dev.finguard.domain.enums.DetectionConfig config,
            @RequestParam(defaultValue = "LLM_RAG") ExplanationType type,
            @RequestParam(defaultValue = "50") int limit) {

        String jobId = UUID.randomUUID().toString();
        statusTracker.start(jobId, "EXPLANATION_BATCH");
        batchAsyncService.runAsync(jobId, config, type, limit);

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/explanations/batch-status/" + jobId
        ));
    }

    /**
     * Poll the status of an async batch explanation job.
     */
    @GetMapping("/batch-status/{jobId}")
    @Operation(summary = "Poll batch explanation job status")
    public ResponseEntity<PipelineStatusTracker.PipelineStatus> batchStatus(@PathVariable String jobId) {
        PipelineStatusTracker.PipelineStatus status = statusTracker.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Get all explanations for a specific alert.
     */
    @GetMapping("/alert/{alertId}")
    @Operation(summary = "Get explanations for an alert")
    public ResponseEntity<List<ExplanationDto>> getByAlert(@PathVariable Long alertId) {
        List<Explanation> explanations = explanationRepository.findByAlertId(alertId);
        List<ExplanationDto> dtos = explanations.stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a single explanation by ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get explanation by ID")
    public ResponseEntity<ExplanationDto> getById(@PathVariable Long id) {
        Explanation explanation = explanationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Explanation", "id", id));
        return ResponseEntity.ok(toDto(explanation));
    }

    /**
     * Re-run hallucination validation on an existing explanation.
     */
    @PostMapping("/{id}/validate")
    @Operation(summary = "Re-validate explanation for hallucinations")
    public ResponseEntity<Map<String, Object>> revalidate(@PathVariable Long id) {
        Explanation explanation = explanationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Explanation", "id", id));

        Alert alert = alertRepository.findByIdWithTransaction(explanation.getAlert().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Alert", "id", explanation.getAlert().getId()));
        validateAndUpdate(explanation, alert);
        return ResponseEntity.ok(Map.<String, Object>of(
                "id", explanation.getId(),
                "hallucinationFree", explanation.getHallucinationFree(),
                "hallucinationFlags", explanation.getHallucinationFlags() != null
                        ? explanation.getHallucinationFlags() : "[]"
        ));
    }

    // ================================================================
    // Internal helpers
    // ================================================================

    private ResponseEntity<ExplanationDto> generateForAlert(Long alertId, boolean useRag) {
        Alert alert = alertRepository.findByIdWithTransaction(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", "id", alertId));

        Explanation explanation = useRag
                ? llmService.generateRagExplanation(alert)
                : llmService.generateDirectExplanation(alert);

        // Run hallucination validation
        validateAndUpdate(explanation, alert);

        return ResponseEntity.ok(toDto(explanation));
    }

    /**
     * Run hallucination validation and update the explanation entity.
     */
    private void validateAndUpdate(Explanation explanation, Alert alert) {
        try {
            ExplanationResponse parsed = new ExplanationResponse(
                    explanation.getRiskSummary(),
                    explanation.getExplanationText(),
                    explanation.getSuspiciousPatterns() != null
                            ? objectMapper.readValue(explanation.getSuspiciousPatterns(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
                            : List.of(),
                    explanation.getRecommendedActions() != null
                            ? objectMapper.readValue(explanation.getRecommendedActions(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
                            : List.of(),
                    explanation.getConfidenceScore() != null ? explanation.getConfidenceScore() : 0.0
            );

            TransactionFeatures features = featuresRepository
                    .findByTransactionId(alert.getTransaction().getId())
                    .orElse(null);

            HallucinationValidator.ValidationResult validation =
                    hallucinationValidator.validate(parsed, alert, alert.getTransaction(), features);

            explanation.setHallucinationFree(validation.hallucinationFree());
            explanation.setHallucinationFlags(objectMapper.writeValueAsString(validation.flags()));

            // Update explanation type to LLM_RAG_VALIDATED if it was RAG + passes validation
            if (explanation.getExplanationType() == ExplanationType.LLM_RAG && validation.hallucinationFree()) {
                explanation.setExplanationType(ExplanationType.LLM_RAG_VALIDATED);
            }

            explanationRepository.save(explanation);
        } catch (Exception e) {
            log.warn("Hallucination validation failed for explanation {}: {}",
                    explanation.getId(), e.getMessage());
        }
    }

    private ExplanationDto toDto(Explanation e) {
        return new ExplanationDto(
                e.getId(),
                e.getAlert().getId(),
                e.getExplanationType(),
                e.getRiskSummary(),
                e.getExplanationText(),
                e.getSuspiciousPatterns(),
                e.getRecommendedActions(),
                e.getConfidenceScore(),
                e.getHallucinationFree(),
                e.getHallucinationFlags(),
                e.getPromptTokens(),
                e.getCompletionTokens(),
                e.getLatencyMs(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null
        );
    }

    /**
     * DTO for API responses — avoids exposing JPA entities.
     */
    public record ExplanationDto(
            Long id,
            Long alertId,
            ExplanationType explanationType,
            String riskSummary,
            String explanationText,
            String suspiciousPatterns,
            String recommendedActions,
            Double confidenceScore,
            Boolean hallucinationFree,
            String hallucinationFlags,
            Integer promptTokens,
            Integer completionTokens,
            Integer latencyMs,
            String createdAt
    ) {}
}
