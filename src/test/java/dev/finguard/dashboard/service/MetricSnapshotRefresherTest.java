package dev.finguard.dashboard.service;

import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.model.MetricSnapshot;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.domain.repository.MetricSnapshotRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the audit D-3 scheduled snapshot refresher.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MetricSnapshotRefresher")
class MetricSnapshotRefresherTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AlertRepository alertRepository;
    @Mock private ExplanationRepository explanationRepository;
    @Mock private FraudPatternRepository fraudPatternRepository;
    @Mock private ExperimentResultRepository experimentResultRepository;
    @Mock private MetricSnapshotRepository snapshotRepository;
    @Mock private PipelineStatusTracker pipelineStatusTracker;

    @Captor ArgumentCaptor<MetricSnapshot> snapshotCaptor;

    @InjectMocks MetricSnapshotRefresher refresher;

    @Test
    @DisplayName("refresh() writes a new row with counts from all repos")
    void refresh_writesSnapshot() {
        // No active pipeline job — refresh should run.
        when(pipelineStatusTracker.isAnyRunning()).thenReturn(false);
        when(transactionRepository.count()).thenReturn(1_000L);
        when(transactionRepository.countByIsFraudTrue()).thenReturn(50L);
        when(alertRepository.count()).thenReturn(200L);
        when(explanationRepository.count()).thenReturn(80L);
        when(fraudPatternRepository.count()).thenReturn(15L);
        when(experimentResultRepository.count()).thenReturn(7L);

        refresher.refresh();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snap = snapshotCaptor.getValue();
        assertThat(snap.getSnapshotAt()).isNotNull();
        assertThat(snap.getTotalTransactions()).isEqualTo(1_000L);
        assertThat(snap.getTotalFraudTx()).isEqualTo(50L);
        assertThat(snap.getTotalAlerts()).isEqualTo(200L);
        assertThat(snap.getTotalExplanations()).isEqualTo(80L);
        assertThat(snap.getTotalFraudPatterns()).isEqualTo(15L);
        assertThat(snap.getTotalExperiments()).isEqualTo(7L);
    }

    @Test
    @DisplayName("refresh() swallows repository failures (dashboard falls back to live queries)")
    void refresh_swallowsFailures() {
        when(pipelineStatusTracker.isAnyRunning()).thenReturn(false);
        when(transactionRepository.count())
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        // Must not propagate — the @Scheduled job is meant to be resilient.
        refresher.refresh();

        verifyNoInteractions(snapshotRepository);
    }

    @Test
    @DisplayName("refresh() skips entirely when a pipeline job is running (self-gating)")
    void refresh_skipsWhenPipelineBusy() {
        when(pipelineStatusTracker.isAnyRunning()).thenReturn(true);

        refresher.refresh();

        // No repository reads, no snapshot save — we got out early.
        verifyNoInteractions(transactionRepository, alertRepository,
                explanationRepository, fraudPatternRepository,
                experimentResultRepository, snapshotRepository);
    }
}
