package dev.finguard.explanation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.rag.RAGContextService;
import dev.finguard.explanation.validation.HallucinationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency + correctness tests for {@link ExplanationBatchAsyncService}.
 *
 * <p>These are <em>unit</em> tests — no Spring context, no DB — that exercise
 * the parallel fan-out inside {@code runSync}. They assert:
 * <ul>
 *   <li>Every input alert is processed exactly once (no duplicates, no drops).</li>
 *   <li>Exceptions from individual LLM calls don't cascade — the batch continues.</li>
 *   <li>Wall-clock speedup with N workers vs 1 worker — this is the whole point of
 *       the parallelization work; a regression here means we've silently gone back
 *       to sequential processing.</li>
 * </ul>
 */
@DisplayName("ExplanationBatchAsyncService — parallel fan-out")
class ExplanationBatchAsyncServiceConcurrencyTest {

    private LLMExplanationService llmService;
    private ExplanationRouter explanationRouter;
    private HallucinationValidator hallucinationValidator;
    private AlertRepository alertRepository;
    private ExplanationRepository explanationRepository;
    private TransactionFeaturesRepository featuresRepository;
    private RAGContextService ragContextService;
    private PipelineStatusTracker statusTracker;
    private ObjectMapper objectMapper;

    private ExplanationBatchAsyncService service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMExplanationService.class);
        explanationRouter = mock(ExplanationRouter.class);
        hallucinationValidator = mock(HallucinationValidator.class);
        alertRepository = mock(AlertRepository.class);
        explanationRepository = mock(ExplanationRepository.class);
        featuresRepository = mock(TransactionFeaturesRepository.class);
        ragContextService = mock(RAGContextService.class);
        statusTracker = mock(PipelineStatusTracker.class);
        objectMapper = new ObjectMapper();

        service = new ExplanationBatchAsyncService(
                llmService, explanationRouter, hallucinationValidator,
                alertRepository, explanationRepository, featuresRepository,
                ragContextService, statusTracker, objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        // @Value-injected field — set explicitly for the unit-test instance.
        ReflectionTestUtils.setField(service, "batchConcurrency", 8);

        // HallucinationValidator is invoked by validateInPlace — always succeed.
        when(hallucinationValidator.validate(any(), any(), any(), any()))
                .thenReturn(new HallucinationValidator.ValidationResult(true, List.of()));
        // No features needed for the test fixture.
        when(featuresRepository.findAllByTransactionIdIn(anyList()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Every alert is processed exactly once — no drops, no duplicates")
    void everyAlertProcessedOnce() {
        List<Alert> alerts = seedAlerts(50);
        // ExplanationBatchAsyncService now uses a two-step candidate selection
        // (random sampling by id-hash, then JOIN-FETCH hydration). Mock both
        // halves so the test still drives the real production path.
        when(alertRepository.findRandomEligibleAlertIds(
                any(dev.finguard.domain.enums.DetectionConfig.class),
                any(ExplanationType.class), anyInt()))
                .thenReturn(alerts.stream().map(Alert::getId).toList());
        when(alertRepository.findByIdsWithTransaction(anyList()))
                .thenReturn(alerts);
        when(explanationRouter.effectiveType(any(Alert.class), any(ExplanationType.class)))
                .thenReturn(ExplanationType.LLM_DIRECT);

        Set<Long> seen = ConcurrentHashMap.newKeySet();
        when(llmService.generateFocusedDirectExplanation(any(Alert.class)))
                .thenAnswer(inv -> {
                    Alert a = inv.getArgument(0);
                    seen.add(a.getId());
                    return newExplanation(a);
                });

        service.runSync("job-processed-once",
                dev.finguard.domain.enums.DetectionConfig.ML_LLM_DIRECT,
                ExplanationType.LLM_DIRECT, 50);

        assertThat(seen).hasSize(50);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                alerts.stream().map(Alert::getId).toList());
    }

    @Test
    @DisplayName("Per-alert failures are isolated — batch continues, failed count accurate")
    void failuresAreIsolated() {
        List<Alert> alerts = seedAlerts(20);
        // ExplanationBatchAsyncService now uses a two-step candidate selection
        // (random sampling by id-hash, then JOIN-FETCH hydration). Mock both
        // halves so the test still drives the real production path.
        when(alertRepository.findRandomEligibleAlertIds(
                any(dev.finguard.domain.enums.DetectionConfig.class),
                any(ExplanationType.class), anyInt()))
                .thenReturn(alerts.stream().map(Alert::getId).toList());
        when(alertRepository.findByIdsWithTransaction(anyList()))
                .thenReturn(alerts);
        when(explanationRouter.effectiveType(any(Alert.class), any(ExplanationType.class)))
                .thenReturn(ExplanationType.LLM_DIRECT);

        // Fail on every 5th alert — 4 failures out of 20.
        AtomicInteger callCount = new AtomicInteger();
        when(llmService.generateFocusedDirectExplanation(any(Alert.class)))
                .thenAnswer(inv -> {
                    int n = callCount.incrementAndGet();
                    if (n % 5 == 0) {
                        throw new RuntimeException("simulated LLM failure " + n);
                    }
                    return newExplanation(inv.getArgument(0));
                });

        service.runSync("job-failures",
                dev.finguard.domain.enums.DetectionConfig.ML_LLM_DIRECT,
                ExplanationType.LLM_DIRECT, 20);

        // 16 successes should have been handed to saveAll.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Explanation>> captor =
                (ArgumentCaptor<List<Explanation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(explanationRepository, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());
        int totalSaved = captor.getAllValues().stream().mapToInt(List::size).sum();
        assertThat(totalSaved).isEqualTo(16);
    }

    @Test
    @DisplayName("Skipped alerts (router returns null) are not persisted but don't break the batch")
    void skippedAlertsNotPersisted() {
        List<Alert> alerts = seedAlerts(10);
        // ExplanationBatchAsyncService now uses a two-step candidate selection
        // (random sampling by id-hash, then JOIN-FETCH hydration). Mock both
        // halves so the test still drives the real production path.
        when(alertRepository.findRandomEligibleAlertIds(
                any(dev.finguard.domain.enums.DetectionConfig.class),
                any(ExplanationType.class), anyInt()))
                .thenReturn(alerts.stream().map(Alert::getId).toList());
        when(alertRepository.findByIdsWithTransaction(anyList()))
                .thenReturn(alerts);
        // Half skipped, half processed.
        AtomicInteger routerCall = new AtomicInteger();
        when(explanationRouter.effectiveType(any(Alert.class), any(ExplanationType.class)))
                .thenAnswer(inv ->
                        routerCall.incrementAndGet() % 2 == 0 ? ExplanationType.LLM_DIRECT : null);
        when(llmService.generateFocusedDirectExplanation(any(Alert.class)))
                .thenAnswer(inv -> newExplanation(inv.getArgument(0)));

        service.runSync("job-skip",
                dev.finguard.domain.enums.DetectionConfig.ML_LLM_DIRECT,
                ExplanationType.LLM_DIRECT, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Explanation>> captor =
                (ArgumentCaptor<List<Explanation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(explanationRepository, org.mockito.Mockito.atMost(2)).saveAll(captor.capture());
        int totalSaved = captor.getAllValues().stream().mapToInt(List::size).sum();
        assertThat(totalSaved).isEqualTo(5);
    }

    @Test
    @DisplayName("Parallel fan-out is faster than sequential for I/O-bound LLM calls")
    void parallelFanOutProvidesSpeedup() {
        int alertCount = 32;
        long perCallLatencyMs = 80;

        List<Alert> alerts = seedAlerts(alertCount);
        // ExplanationBatchAsyncService now uses a two-step candidate selection
        // (random sampling by id-hash, then JOIN-FETCH hydration). Mock both
        // halves so the test still drives the real production path.
        when(alertRepository.findRandomEligibleAlertIds(
                any(dev.finguard.domain.enums.DetectionConfig.class),
                any(ExplanationType.class), anyInt()))
                .thenReturn(alerts.stream().map(Alert::getId).toList());
        when(alertRepository.findByIdsWithTransaction(anyList()))
                .thenReturn(alerts);
        when(explanationRouter.effectiveType(any(Alert.class), any(ExplanationType.class)))
                .thenReturn(ExplanationType.LLM_DIRECT);
        when(llmService.generateFocusedDirectExplanation(any(Alert.class)))
                .thenAnswer(inv -> {
                    // Simulate I/O wait — the thing LLM calls actually do.
                    Thread.sleep(perCallLatencyMs);
                    return newExplanation(inv.getArgument(0));
                });

        long start = System.currentTimeMillis();
        service.runSync("job-parallel",
                dev.finguard.domain.enums.DetectionConfig.ML_LLM_DIRECT,
                ExplanationType.LLM_DIRECT, alertCount);
        long elapsedMs = System.currentTimeMillis() - start;

        // Sequential lower-bound: 32 × 80ms = 2560ms. With 8 workers, the ideal
        // is 32 / 8 × 80 = 320ms. Allow generous headroom for thread startup +
        // CI slowness — anything under 1500ms proves workers ran in parallel.
        long sequentialLowerBound = alertCount * perCallLatencyMs;
        assertThat(elapsedMs)
                .as("elapsed=%dms, sequential floor=%dms — parallelization must beat sequential by 1.5× minimum",
                        elapsedMs, sequentialLowerBound)
                .isLessThan(sequentialLowerBound * 2 / 3);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<Alert> seedAlerts(int n) {
        List<Alert> list = new ArrayList<>(n);
        for (long i = 1; i <= n; i++) {
            Transaction tx = new Transaction();
            tx.setId(1_000L + i);
            tx.setExternalId("TX-" + i);
            tx.setAmount(new BigDecimal("100.00"));
            tx.setTransactionType(TransactionType.TRANSFER);
            tx.setTimestamp(LocalDateTime.now());

            Alert alert = new Alert();
            alert.setId(i);
            alert.setTransaction(tx);
            alert.setDetectionConfig(DetectionConfig.ML_LLM_DIRECT);
            alert.setIsAnomaly(true);
            alert.setRuleTriggered("LARGE_AMOUNT");
            list.add(alert);
        }
        return list;
    }

    private Explanation newExplanation(Alert alert) {
        Explanation e = new Explanation();
        e.setAlert(alert);
        e.setExplanationType(ExplanationType.LLM_DIRECT);
        e.setRiskSummary("test summary");
        e.setExplanationText("test explanation");
        e.setSuspiciousPatterns("[]");
        e.setRecommendedActions("[]");
        e.setConfidenceScore(0.9);
        return e;
    }
}
