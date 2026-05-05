package dev.finguard.ingestion.service;

import com.opencsv.exceptions.CsvValidationException;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.ingestion.dto.ImportResult;
import dev.finguard.ingestion.dto.PaySimRow;
import dev.finguard.ingestion.loader.PaySimLoader;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.finguard.testutil.TestTransactionBuilder.aTransaction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IngestionService")
class IngestionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaySimLoader paySimLoader;

    @Mock
    private DataSource dataSource;

    @Mock
    private io.micrometer.core.instrument.Counter transactionsImportedCounter;

    /**
     * RETURNS_DEEP_STUBS lets {@code meterRegistry.timer(...).record(...)} chain
     * without explicit per-method stubbing. Plain {@code @Mock} would NPE on the
     * intermediate {@code .timer()} call. We don't assert on timer values from
     * this test class — those are covered by DashboardServiceTest.
     */
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Spy
    @InjectMocks
    private IngestionService ingestionService;

    @Captor
    private ArgumentCaptor<List<Transaction>> batchCaptor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestionService, "batchSize", 3);
        // Audit C-6: saveBatchFallback now delegates to a Spring-proxied self
        // reference (selfProxy) so @Transactional(REQUIRES_NEW) is applied per
        // row. In a pure Mockito unit test there is no Spring context, so we
        // wire selfProxy to `this` — the bypassed @Transactional is fine
        // because we're only verifying routing/dedupe behaviour here.
        ReflectionTestUtils.setField(ingestionService, "selfProxy", ingestionService);
        // Stub the COPY-based saveBatch — avoids real DB connection in unit tests
        lenient().doNothing().when(ingestionService).saveBatch(anyList());
    }

    /**
     * Configure the repository mock to report no existing external IDs (no duplicates).
     *
     * <p>Also reports a non-zero {@code count()} so the import does NOT take the
     * "empty-table fast path" that skips the dedup IN-query — these tests exercise
     * the dedup logic explicitly, so the fast path would invalidate their assertions.</p>
     */
    private void noDuplicates() {
        when(transactionRepository.findExistingExternalIds(anyCollection())).thenReturn(Set.of());
        when(transactionRepository.count()).thenReturn(1L);
    }

    // ==============================================================
    // Happy path tests
    // ==============================================================

    @Nested
    @DisplayName("Successful imports")
    class SuccessfulImports {

        @Test
        @DisplayName("Should import all valid rows from CSV file")
        void importPaySimCsv_shouldImportAllValidRows() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,TRANSFER,50000.00,C100,100000.00,50000.00,C200,0.00,50000.00,0,0",
                    "2,PAYMENT,500.00,C101,10000.00,9500.00,M100,0.00,500.00,0,0"
            );

            PaySimRow row1 = new PaySimRow(1, "TRANSFER", new BigDecimal("50000.00"),
                    "C100", BigDecimal.ZERO, BigDecimal.ZERO, "C200", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            PaySimRow row2 = new PaySimRow(2, "PAYMENT", new BigDecimal("500.00"),
                    "C101", BigDecimal.ZERO, BigDecimal.ZERO, "M100", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Transaction tx1 = aTransaction().withExternalId("EXT-1").build();
            Transaction tx2 = aTransaction().withExternalId("EXT-2").build();

            // parseRow returns valid rows
            when(paySimLoader.parseRow(any(), eq(2L))).thenReturn(Optional.of(row1));
            when(paySimLoader.parseRow(any(), eq(3L))).thenReturn(Optional.of(row2));

            // validate passes
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());

            // toTransaction maps correctly
            when(paySimLoader.toTransaction(row1)).thenReturn(tx1);
            when(paySimLoader.toTransaction(row2)).thenReturn(tx2);

            // No duplicates in DB
            noDuplicates();

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getTotalRows()).isEqualTo(2);
            assertThat(result.getDuration()).isNotNull();
        }

        @Test
        @DisplayName("Should flush batch when batch size is reached")
        void importPaySimCsv_shouldFlushBatchAtBatchSize() throws Exception {
            // batchSize is set to 3 in setUp()
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,TRANSFER,100,C1,0,0,C2,0,0,0,0",
                    "2,TRANSFER,200,C3,0,0,C4,0,0,0,0",
                    "3,TRANSFER,300,C5,0,0,C6,0,0,0,0",
                    "4,TRANSFER,400,C7,0,0,C8,0,0,0,0"
            );

            PaySimRow anyRow = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                    "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            when(paySimLoader.parseRow(any(), anyLong())).thenReturn(Optional.of(anyRow));
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());
            when(paySimLoader.toTransaction(any())).thenReturn(aTransaction().withExternalId("unique-" + System.nanoTime()).build());
            noDuplicates();

            ingestionService.importPaySimCsv(csv);

            // 4 rows with batch size 3 → saveBatch called twice (batch of 3 + remainder of 1)
            verify(ingestionService, times(2)).saveBatch(anyList());
        }

        @Test
        @DisplayName("Should handle empty CSV (header only) gracefully")
        void importPaySimCsv_shouldHandleEmptyFile() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud"
            );

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getTotalRows()).isZero();
            verify(ingestionService, never()).saveBatch(anyList());
        }
    }

    // ==============================================================
    // Error handling tests
    // ==============================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should skip rows that fail parsing and continue")
        void importPaySimCsv_shouldSkipParseFailures() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "bad,TRANSFER,abc,C1,0,0,C2,0,0,0,0",
                    "1,TRANSFER,100,C3,0,0,C4,0,0,0,0"
            );

            // First row fails parse, second succeeds
            when(paySimLoader.parseRow(any(), eq(2L))).thenReturn(Optional.empty());

            PaySimRow validRow = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                    "C3", BigDecimal.ZERO, BigDecimal.ZERO, "C4", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            when(paySimLoader.parseRow(any(), eq(3L))).thenReturn(Optional.of(validRow));
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());
            when(paySimLoader.toTransaction(any())).thenReturn(aTransaction().withExternalId("X").build());
            noDuplicates();

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).reason()).isEqualTo("Parse failure");
        }

        @Test
        @DisplayName("Should skip rows that fail validation and record reason")
        void importPaySimCsv_shouldSkipValidationFailures() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,INVALID_TYPE,100,C1,0,0,C2,0,0,0,0"
            );

            PaySimRow row = new PaySimRow(1, "INVALID_TYPE", BigDecimal.TEN,
                    "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            when(paySimLoader.parseRow(any(), anyLong())).thenReturn(Optional.of(row));
            when(paySimLoader.validate(row, 2L)).thenReturn(Optional.of("Unknown transaction type: INVALID_TYPE"));

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0).reason()).contains("Unknown transaction type");
        }

        @Test
        @DisplayName("Should skip duplicate external IDs and record them as errors")
        void importPaySimCsv_shouldSkipDuplicates() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,TRANSFER,100,C1,0,0,C2,0,0,0,0"
            );

            PaySimRow row = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                    "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            Transaction tx = aTransaction().withExternalId("PAYSIM-1-C1-C2-100").build();

            when(paySimLoader.parseRow(any(), anyLong())).thenReturn(Optional.of(row));
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());
            when(paySimLoader.toTransaction(row)).thenReturn(tx);
            // Duplicate — already exists in DB (batch dedup returns the ID as existing).
            // Force the dedup path (count() > 0 so the empty-table fast path is skipped).
            when(transactionRepository.findExistingExternalIds(anyCollection()))
                    .thenReturn(Set.of("PAYSIM-1-C1-C2-100"));
            when(transactionRepository.count()).thenReturn(1L);

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0).reason()).contains("Duplicate external ID");
        }

        @Test
        @DisplayName("Should throw IOException for non-existent file")
        void importPaySimCsv_shouldThrow_whenFileNotFound() {
            Path nonExistent = tempDir.resolve("does-not-exist.csv");

            assertThatThrownBy(() -> ingestionService.importPaySimCsv(nonExistent))
                    .isInstanceOf(IOException.class);
        }
    }

    // ==============================================================
    // Mixed data test
    // ==============================================================

    @Test
    @DisplayName("Should correctly count successes and failures in mixed data")
    void importPaySimCsv_shouldHandleMixedData() throws Exception {
        Path csv = writeCsv(
                "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                "1,TRANSFER,100,C1,0,0,C2,0,0,0,0",   // valid
                "bad,row,data,C3,0,0,C4,0,0,0,0",       // parse fail
                "2,TRANSFER,200,C5,0,0,C6,0,0,0,0",     // valid
                "-1,TRANSFER,300,C7,0,0,C8,0,0,0,0"     // validation fail
        );

        PaySimRow valid1 = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        PaySimRow valid2 = new PaySimRow(2, "TRANSFER", BigDecimal.TEN,
                "C5", BigDecimal.ZERO, BigDecimal.ZERO, "C6", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        PaySimRow invalid = new PaySimRow(-1, "TRANSFER", BigDecimal.TEN,
                "C7", BigDecimal.ZERO, BigDecimal.ZERO, "C8", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        when(paySimLoader.parseRow(any(), eq(2L))).thenReturn(Optional.of(valid1));
        when(paySimLoader.parseRow(any(), eq(3L))).thenReturn(Optional.empty()); // parse fail
        when(paySimLoader.parseRow(any(), eq(4L))).thenReturn(Optional.of(valid2));
        when(paySimLoader.parseRow(any(), eq(5L))).thenReturn(Optional.of(invalid));

        when(paySimLoader.validate(valid1, 2L)).thenReturn(Optional.empty());
        when(paySimLoader.validate(valid2, 4L)).thenReturn(Optional.empty());
        when(paySimLoader.validate(invalid, 5L)).thenReturn(Optional.of("Invalid step value: -1"));

        when(paySimLoader.toTransaction(any())).thenReturn(aTransaction().withExternalId("X").build());
        noDuplicates();

        ImportResult result = ingestionService.importPaySimCsv(csv);

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isEqualTo(2);
        assertThat(result.getTotalRows()).isEqualTo(4);
    }

    // ==============================================================
    // Batch dedup optimization tests
    // ==============================================================

    @Nested
    @DisplayName("Batch deduplication")
    class BatchDedup {

        @Test
        @DisplayName("Should use batch query instead of per-row duplicate check")
        void importPaySimCsv_shouldUseBatchDedupQuery() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,TRANSFER,100,C1,0,0,C2,0,0,0,0",
                    "2,TRANSFER,200,C3,0,0,C4,0,0,0,0"
            );

            PaySimRow anyRow = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                    "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            when(paySimLoader.parseRow(any(), anyLong())).thenReturn(Optional.of(anyRow));
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());
            when(paySimLoader.toTransaction(any())).thenReturn(aTransaction().withExternalId("Y").build());
            noDuplicates();

            ingestionService.importPaySimCsv(csv);

            // Should call batch dedup, NOT per-row findByExternalId
            verify(transactionRepository, atLeastOnce()).findExistingExternalIds(anyCollection());
            verify(transactionRepository, never()).findByExternalId(anyString());
        }

        @Test
        @DisplayName("Should filter duplicates from batch and save only new ones")
        void importPaySimCsv_shouldFilterDuplicatesFromBatch() throws Exception {
            Path csv = writeCsv(
                    "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud",
                    "1,TRANSFER,100,C1,0,0,C2,0,0,0,0",
                    "2,TRANSFER,200,C3,0,0,C4,0,0,0,0"
            );

            PaySimRow row1 = new PaySimRow(1, "TRANSFER", BigDecimal.TEN,
                    "C1", BigDecimal.ZERO, BigDecimal.ZERO, "C2", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
            PaySimRow row2 = new PaySimRow(2, "TRANSFER", BigDecimal.TEN,
                    "C3", BigDecimal.ZERO, BigDecimal.ZERO, "C4", BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

            Transaction tx1 = aTransaction().withExternalId("EXT-DUP").build();
            Transaction tx2 = aTransaction().withExternalId("EXT-NEW").build();

            when(paySimLoader.parseRow(any(), eq(2L))).thenReturn(Optional.of(row1));
            when(paySimLoader.parseRow(any(), eq(3L))).thenReturn(Optional.of(row2));
            when(paySimLoader.validate(any(), anyLong())).thenReturn(Optional.empty());
            when(paySimLoader.toTransaction(row1)).thenReturn(tx1);
            when(paySimLoader.toTransaction(row2)).thenReturn(tx2);

            // First ID exists, second is new
            when(transactionRepository.findExistingExternalIds(anyCollection()))
                    .thenReturn(Set.of("EXT-DUP"));
            // Force the dedup path (count() > 0 means table is not empty so the
            // empty-table fast path is skipped — see flushCandidates skipDedup arg).
            when(transactionRepository.count()).thenReturn(1L);

            ImportResult result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);

            // saveBatch should be called with only the new transaction
            verify(ingestionService).saveBatch(batchCaptor.capture());
            List<Transaction> saved = batchCaptor.getValue();
            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getExternalId()).isEqualTo("EXT-NEW");
        }
    }


    // ==============================================================
    // COPY fallback tests
    // ==============================================================

    @Nested
    @DisplayName("saveBatch COPY fallback")
    class SaveBatchFallback {

        @Test
        @DisplayName("Falls back to per-row saves when COPY fails with SQLException")
        void saveBatch_shouldFallBackToPerRowSaves_whenCopyFails() throws Exception {
            // Reset the global doNothing stub so saveBatch runs the real code
            doCallRealMethod().when(ingestionService).saveBatch(anyList());

            // Simulate COPY failure by making getConnection throw
            when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("COPY failed: unique violation"));

            Transaction tx1 = aTransaction().withExternalId("EXT-A").build();
            Transaction tx2 = aTransaction().withExternalId("EXT-B").build();
            when(transactionRepository.saveAndFlush(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

            ingestionService.saveBatch(List.of(tx1, tx2));

            // Both transactions should be saved individually via fallback
            verify(transactionRepository).saveAndFlush(tx1);
            verify(transactionRepository).saveAndFlush(tx2);
        }

        @Test
        @DisplayName("Skips duplicate rows in fallback without throwing")
        void saveBatch_shouldSkipDuplicates_inFallback() throws Exception {
            doCallRealMethod().when(ingestionService).saveBatch(anyList());
            when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("COPY failed"));

            Transaction tx1 = aTransaction().withExternalId("DUP").build();
            Transaction tx2 = aTransaction().withExternalId("NEW").build();

            // tx1 triggers duplicate-key violation, tx2 saves fine
            when(transactionRepository.saveAndFlush(tx1))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"));
            when(transactionRepository.saveAndFlush(tx2)).thenReturn(tx2);

            // Should not throw — duplicate is swallowed
            assertThatCode(() -> ingestionService.saveBatch(List.of(tx1, tx2)))
                    .doesNotThrowAnyException();

            verify(transactionRepository).saveAndFlush(tx2);
        }
    }

    // ==============================================================
    // Helper
    // ==============================================================

    private Path writeCsv(String... lines) throws IOException {
        Path file = tempDir.resolve("test.csv");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }
}
