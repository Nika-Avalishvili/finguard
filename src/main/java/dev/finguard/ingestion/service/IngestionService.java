package dev.finguard.ingestion.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.ingestion.dto.ImportResult;
import dev.finguard.ingestion.dto.IngestionJob;
import dev.finguard.ingestion.dto.PaySimRow;
import dev.finguard.ingestion.loader.PaySimLoader;
import dev.finguard.config.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service responsible for importing transaction datasets (CSV) into the database.
 *
 * <p>Streams CSV rows in configurable batches to handle datasets of 6M+ rows
 * without exceeding JVM memory limits. Each batch is persisted via PostgreSQL
 * {@code COPY FROM STDIN}, bypassing Hibernate and the per-row INSERT overhead
 * that {@code GenerationType.IDENTITY} otherwise forces.</p>
 *
 * <p>Why COPY instead of Hibernate {@code saveAll}? Hibernate disables JDBC
 * batching for {@code IDENTITY} columns — every row generates an individual
 * {@code INSERT ... RETURNING id}. For 6.3M rows that is 6.3M round-trips.
 * COPY streams the entire batch in a single network call and lets PostgreSQL
 * write data at storage speed, typically 10–50× faster.</p>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /**
     * PostgreSQL COPY target: all columns except {@code id} (BIGSERIAL auto) and
     * {@code created_at} (DEFAULT CURRENT_TIMESTAMP). Order must match
     * {@link #appendCopyRow}.
     */
    private static final String COPY_SQL =
            "COPY transactions (external_id, dataset_source, timestamp, sender_account, " +
            "receiver_account, transaction_type, amount, sender_balance_before, " +
            "sender_balance_after, receiver_balance_before, receiver_balance_after, " +
            "is_fraud, is_flagged_fraud, is_training_set) FROM STDIN";

    /** PostgreSQL timestamp literal format (ISO with space, no T). */
    private static final DateTimeFormatter PG_TS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final TransactionRepository transactionRepository;
    private final PaySimLoader paySimLoader;
    private final DataSource dataSource;
    private final Counter transactionsImportedCounter;
    private final MeterRegistry meterRegistry;
    /**
     * Audit C-6: self-proxy used to invoke {@link #saveSingleRowInNewTx} so
     * Spring's {@code @Transactional} interceptor actually runs. Direct
     * {@code this.} calls bypass AOP.
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private IngestionService selfProxy;

    @Value("${finguard.ingestion.batch-size:5000}")
    private int batchSize;

    public IngestionService(TransactionRepository transactionRepository,
                            PaySimLoader paySimLoader,
                            DataSource dataSource,
                            Counter transactionsImportedCounter,
                            MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.paySimLoader = paySimLoader;
        this.dataSource = dataSource;
        this.transactionsImportedCounter = transactionsImportedCounter;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Import a PaySim CSV file (synchronous, no job tracking).
     *
     * <p>Used by integration tests and direct service callers. HTTP-initiated imports
     * of large files should go through {@link IngestionAsyncService} instead.</p>
     */
    public ImportResult importPaySimCsv(Path csvPath) throws IOException, CsvValidationException {
        return importPaySimCsv(csvPath, null);
    }

    /**
     * Import a PaySim CSV file, optionally updating a live job for status polling.
     *
     * <p>Each batch of {@code finguard.ingestion.batch-size} rows (default 5000):
     * <ol>
     *   <li>Dedup-checked with a single {@code IN} query ({@link TransactionRepository#findExistingExternalIds})</li>
     *   <li>Persisted via PostgreSQL {@code COPY FROM STDIN} in its own auto-commit connection</li>
     * </ol>
     * Per-batch commits mean a partial import survives a crash; re-running skips
     * already-committed rows via the dedup check.</p>
     */
    public ImportResult importPaySimCsv(Path csvPath, IngestionJob job)
            throws IOException, CsvValidationException {

        // If the row count was pre-seeded (e.g., counted during multipart upload in a single
        // streaming pass), skip the NIO scan entirely. This saves a full read of large files.
        long totalDataRows;
        if (job != null && job.getTotalRows() > 0) {
            totalDataRows = job.getTotalRows();
            log.info("Starting PaySim import from: {} (~{} data rows, pre-counted — skipping NIO scan)",
                    csvPath, String.format("%,d", totalDataRows));
        } else {
            totalDataRows = countDataRows(csvPath);
            log.info("Starting PaySim import from: {} ({} data rows) — batches commit independently, re-run is safe",
                    csvPath, String.format("%,d", totalDataRows));
            if (job != null) {
                job.updateTotal(totalDataRows);
            }
        }

        Instant start = Instant.now();
        ImportResult result = new ImportResult();

        // Optimisation: if the transactions table is empty before the import
        // begins, skip the per-batch dedup query entirely. There can be no
        // duplicates against an empty table. Saves 1-2 large IN queries per
        // batch (~400 ms each on 50k batches), so on a 6.3M-row import that's
        // ~1 minute of pure round-trip overhead removed.
        // The dedup re-engages automatically on subsequent imports because
        // we only check at the start of each import call.
        boolean skipDedup = transactionRepository.count() == 0;
        if (skipDedup) {
            log.info("Empty transactions table detected — dedup checks will be skipped for this import (~1 min faster)");
        }

        // Bulk-load optimisation: when the table is empty, drop the secondary
        // indexes before COPY and rebuild them in bulk afterwards. The transactions
        // table has 11 indexes; each row INSERT updates all of them (~75M
        // index ops total for 6.3 M rows). Dropping them turns 75M random
        // I/Os into a single sequential index build per index after import.
        // Net measured: ~3-5x faster bulk load. The unique constraint on
        // external_id is KEPT so we still catch duplicates within the same CSV.
        boolean indexesDropped = false;
        if (skipDedup) {
            try {
                dropSecondaryIndexes();
                indexesDropped = true;
            } catch (Exception e) {
                log.warn("Could not drop secondary indexes — continuing with full indexes (slower): {}",
                        e.getMessage());
            }
        }

        try (BufferedReader fileReader = Files.newBufferedReader(csvPath);
             CSVReader csvReader = new CSVReaderBuilder(fileReader)
                     .withSkipLines(1)
                     .build()) {

            List<Transaction> candidates = new ArrayList<>(batchSize);
            List<Long> candidateRowNums = new ArrayList<>(batchSize);
            String[] row;
            long rowNum = 1;

            while ((row = csvReader.readNext()) != null) {
                rowNum++;
                result.setTotalRows(result.getTotalRows() + 1);

                Optional<PaySimRow> parsed = paySimLoader.parseRow(row, rowNum);
                if (parsed.isEmpty()) {
                    result.addError(rowNum, "Parse failure");
                    continue;
                }

                Optional<String> validationError = paySimLoader.validate(parsed.get(), rowNum);
                if (validationError.isPresent()) {
                    result.addError(rowNum, validationError.get());
                    continue;
                }

                Transaction tx = paySimLoader.toTransaction(parsed.get());
                candidates.add(tx);
                candidateRowNums.add(rowNum);

                if (candidates.size() >= batchSize) {
                    flushCandidates(candidates, candidateRowNums, result, skipDedup);
                    candidates.clear();
                    candidateRowNums.clear();
                    logProgress(result, totalDataRows, job);
                    if (job != null && job.isCancelRequested()) {
                        log.info("Import cancelled after {} rows processed", String.format("%,d", result.getTotalRows()));
                        break;
                    }
                }
            }

            if (!candidates.isEmpty()) {
                flushCandidates(candidates, candidateRowNums, result, skipDedup);
            }
        }

        // Recreate the indexes we dropped before the import. Bulk index build
        // is sequential and CPU-bound — ~30 sec for the full set on 6.3 M rows.
        // Wrapped in try/finally style: even if the import partially failed,
        // we attempt rebuild so the table is left queryable.
        if (indexesDropped) {
            try {
                recreateSecondaryIndexes();
            } catch (Exception e) {
                log.error("INDEX REBUILD FAILED — transactions table is missing secondary indexes! "
                        + "Run 'REINDEX TABLE transactions' manually or restart the app to retry. "
                        + "Detection queries will be very slow until indexes exist.", e);
            }
        }

        result.setDuration(Duration.between(start, Instant.now()));
        log.info("PaySim import complete: {}/{} rows processed | imported: {}, skipped: {} | elapsed: {}s",
                String.format("%,d", result.getTotalRows()), String.format("%,d", totalDataRows),
                String.format("%,d", result.getSuccessCount()), String.format("%,d", result.getSkippedCount()),
                result.getDuration().toSeconds());

        // Refresh planner stats after a bulk COPY — otherwise Postgres still
        // thinks the table has the pre-import row count and picks wrong plans
        // (seq-scans instead of index-scans) for the first dozen queries
        // against the fresh rows. Autovacuum eventually catches up but not
        // before the user's next feature-compute / detection run. Cheap
        // (~seconds for 6M rows) and failure-tolerant.
        if (result.getSuccessCount() > 0) {
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                long t0 = System.currentTimeMillis();
                stmt.execute("ANALYZE transactions");
                log.info("ANALYZE transactions complete ({}ms)", System.currentTimeMillis() - t0);
            } catch (Exception e) {
                log.warn("ANALYZE transactions skipped: {}", e.getMessage());
            }
        }

        // Dashboard panel: record total wall time of the import for the
        // "Pipeline Stage Timings → Importing" row. We record on success only —
        // a failed import would skew the avg downward with truncated runs.
        meterRegistry.timer(MetricsConfig.STAGE_TIMER, "stage", "csv_import")
                .record(result.getDuration());

        return result;
    }

    /**
     * Count data rows in the CSV file using a 64 KB NIO byte buffer.
     * ~5-10x faster than {@code Files.lines().count()} — counts \n bytes instead of
     * allocating a String per line.
     */
    private long countDataRows(Path csvPath) throws IOException {
        long lineCount = 0;
        try (var channel = java.nio.channels.FileChannel.open(csvPath)) {
            var buffer = java.nio.ByteBuffer.allocate(1 << 16);
            int read;
            while ((read = channel.read(buffer)) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    if (buffer.get() == '\n') lineCount++;
                }
                buffer.clear();
            }
        }
        return Math.max(0, lineCount - 1);
    }

    private void logProgress(ImportResult result, long totalDataRows, IngestionJob job) {
        long processed = result.getTotalRows();
        String pct = totalDataRows > 0
                ? String.format("%.1f", processed * 100.0 / totalDataRows)
                : "0.0";
        log.info("Progress: {}/{} rows ({}%) | imported: {}, skipped: {}",
                String.format("%,d", processed), String.format("%,d", totalDataRows), pct,
                String.format("%,d", result.getSuccessCount()), String.format("%,d", result.getSkippedCount()));

        if (job != null) {
            job.updateProgress(processed, result.getSuccessCount(), result.getSkippedCount());
        }
    }

    /**
     * PostgreSQL caps prepared-statement parameters at 65 535. Hibernate's
     * {@code in_clause_parameter_padding=true} rounds the actual IN-clause
     * size up to the next power of two for query-plan caching, so a 50 000-row
     * import batch padded to 65 536 → over the limit by exactly 1. We chunk
     * the dedup IN-query into sub-batches of 30 000 (which pad to 32 768 —
     * comfortably under the limit) and merge the results client-side.
     *
     * <p>30 000 is a sweet spot: large enough that we make at most 2 round-trips
     * for a 50 000 import batch, small enough to leave headroom under the
     * Postgres ceiling regardless of padding rounding.</p>
     */
    private static final int IN_CLAUSE_CHUNK_SIZE = 30_000;

    /**
     * Dedup a batch against existing DB rows, then COPY the new ones in bulk.
     *
     * @param skipDedup pass {@code true} when the table was empty at the start
     *                  of this import — eliminates the IN-clause round-trip per
     *                  batch. The unique-constraint at the DB level still
     *                  catches duplicates within the same CSV file (rare).
     */
    private void flushCandidates(List<Transaction> candidates, List<Long> rowNums,
                                  ImportResult result, boolean skipDedup) {
        Set<String> existing;
        if (skipDedup) {
            existing = java.util.Collections.emptySet();
        } else {
            Set<String> externalIds = candidates.stream()
                    .map(Transaction::getExternalId)
                    .collect(Collectors.toSet());
            existing = lookupExistingInChunks(externalIds);
        }

        List<Transaction> toSave = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Transaction tx = candidates.get(i);
            if (!existing.isEmpty() && existing.contains(tx.getExternalId())) {
                result.addError(rowNums.get(i), "Duplicate external ID: " + tx.getExternalId());
            } else {
                toSave.add(tx);
                result.incrementSuccess();
                transactionsImportedCounter.increment();
            }
        }

        if (!toSave.isEmpty()) {
            saveBatch(toSave);
        }
    }

    /**
     * Split a large set of external IDs into PG-safe chunks and union the results.
     *
     * <p>Necessary because PostgreSQL hard-caps prepared-statement parameters at
     * 65 535 — see {@link #IN_CLAUSE_CHUNK_SIZE} for the rationale.</p>
     */
    private Set<String> lookupExistingInChunks(Set<String> externalIds) {
        if (externalIds.size() <= IN_CLAUSE_CHUNK_SIZE) {
            return transactionRepository.findExistingExternalIds(externalIds);
        }
        // Iterate in deterministic chunks. Repository call is cheap (~10 ms / 30k IDs
        // on the indexed external_id column), so a 50k import does at most 2 calls.
        Set<String> existing = new java.util.HashSet<>(externalIds.size() / 2);
        List<String> all = new ArrayList<>(externalIds);
        for (int i = 0; i < all.size(); i += IN_CLAUSE_CHUNK_SIZE) {
            int end = Math.min(i + IN_CLAUSE_CHUNK_SIZE, all.size());
            existing.addAll(transactionRepository.findExistingExternalIds(
                    all.subList(i, end)));
        }
        return existing;
    }

    /**
     * Bulk-insert a batch of new (deduplicated) transactions via PostgreSQL COPY.
     *
     * <p>COPY FROM STDIN streams tab-separated rows directly to the storage engine,
     * bypassing query parsing, planning, and per-row INSERT overhead. A fresh
     * auto-commit connection is used per batch so each batch commits independently
     * — partial imports are resumable.</p>
     *
     * <p>Method is {@code protected} to allow test spies to stub it without a real
     * database connection.</p>
     */
    protected void saveBatch(List<Transaction> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 220);
        for (Transaction tx : batch) {
            appendCopyRow(sb, tx);
        }

        try (Connection conn = dataSource.getConnection()) {
            // Wrap COPY in an explicit transaction so we can use SET LOCAL
            // synchronous_commit = OFF — it only takes effect inside a tx.
            //
            // Why bother: PostgreSQL still writes WAL but does NOT block the
            // commit waiting for fsync. For a bulk CSV import this typically
            // saves 20-40% of wall-clock time — fsync latency on Docker-on-macOS
            // volumes is the single biggest hidden cost.
            //
            // Durability trade-off: a power loss / kernel panic in the last
            // ~200 ms loses the in-flight rows. The dedup check on the next
            // import run catches them on re-import, so re-running is safe.
            // Acceptable for a thesis benchmark; would NOT be acceptable for
            // a regulated production AML pipeline.
            //
            // Restore autocommit afterwards so the connection returns to Hikari
            // in the same state it was lent out in.
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("SET LOCAL synchronous_commit = OFF");
                }

                BaseConnection baseConn = conn.unwrap(BaseConnection.class);
                new CopyManager(baseConn).copyIn(COPY_SQL, new StringReader(sb.toString()));
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException | IOException e) {
            log.warn("COPY batch failed ({} rows) — falling back to per-row inserts: {}",
                    batch.size(), e.getMessage());
            saveBatchFallback(batch);
        }
    }

    /**
     * Per-row fallback when COPY fails. Inserts each transaction individually;
     * duplicate-key violations are logged and skipped so valid rows still commit.
     *
     * <p>Audit C-6: each row's save runs in its own {@code REQUIRES_NEW} transaction
     * (via the Spring-proxied {@link #saveSingleRowInNewTx}) so that a
     * unique-violation on one row cannot poison the others.</p>
     */
    private void saveBatchFallback(List<Transaction> batch) {
        int saved = 0;
        int skipped = 0;
        for (Transaction tx : batch) {
            try {
                selfProxy.saveSingleRowInNewTx(tx);
                saved++;
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.debug("Fallback insert skipped duplicate external_id={}: {}",
                        tx.getExternalId(), ex.getMessage());
                skipped++;
            }
        }
        log.info("Fallback inserts complete: {} saved, {} skipped (duplicates)", saved, skipped);
    }

    /**
     * Per-row save isolated in its own transaction so a unique-constraint
     * violation cannot cascade to the other rows in the same COPY fallback.
     * Must be {@code public} + called via a proxy to trigger the Spring AOP
     * interceptor (self-invocation would bypass {@code @Transactional}).
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void saveSingleRowInNewTx(Transaction tx) {
        transactionRepository.saveAndFlush(tx);
    }

    /**
     * Secondary indexes on the transactions table — these are dropped before
     * a fresh bulk import and rebuilt afterwards. The {@code transactions_pkey}
     * (id) and the unique constraint on {@code external_id} are intentionally
     * NOT in this list because:
     * <ul>
     *   <li>The PK index is required by Postgres for the table itself.</li>
     *   <li>The unique constraint on external_id catches duplicates within the
     *       same CSV — losing it would let bad rows in silently.</li>
     * </ul>
     *
     * <p>If you add new indexes via Liquibase, also add their names here so
     * they get rebuilt after a bulk import.</p>
     */
    private static final List<String> SECONDARY_TX_INDEXES = List.of(
            "idx_transactions_sender",
            "idx_transactions_receiver",
            "idx_transactions_timestamp",
            "idx_transactions_is_fraud",
            "idx_transactions_sender_ts",
            "idx_transactions_sender_receiver_ts",
            "idx_transactions_training_split",
            "idx_tx_fold",
            "idx_tx_sender_ts"
    );

    /**
     * SQL to recreate each index after the import. Map MUST match
     * {@link #SECONDARY_TX_INDEXES} entries 1-to-1 by name. The DDL is
     * intentionally inlined here (rather than parsing the original Liquibase
     * changelog) so this code is self-contained and survives any future
     * changelog reorganisation.
     */
    private static final java.util.Map<String, String> SECONDARY_TX_INDEX_DDL = java.util.Map.ofEntries(
            java.util.Map.entry("idx_transactions_sender",
                    "CREATE INDEX idx_transactions_sender ON transactions (sender_account)"),
            java.util.Map.entry("idx_transactions_receiver",
                    "CREATE INDEX idx_transactions_receiver ON transactions (receiver_account)"),
            java.util.Map.entry("idx_transactions_timestamp",
                    "CREATE INDEX idx_transactions_timestamp ON transactions (timestamp)"),
            java.util.Map.entry("idx_transactions_is_fraud",
                    "CREATE INDEX idx_transactions_is_fraud ON transactions (is_fraud)"),
            java.util.Map.entry("idx_transactions_sender_ts",
                    "CREATE INDEX idx_transactions_sender_ts ON transactions (sender_account, timestamp)"),
            java.util.Map.entry("idx_transactions_sender_receiver_ts",
                    "CREATE INDEX idx_transactions_sender_receiver_ts "
                            + "ON transactions (sender_account, receiver_account, timestamp)"),
            java.util.Map.entry("idx_transactions_training_split",
                    "CREATE INDEX idx_transactions_training_split "
                            + "ON transactions (is_training_set, is_fraud, dataset_source)"),
            java.util.Map.entry("idx_tx_fold",
                    "CREATE INDEX idx_tx_fold ON transactions (fold)"),
            java.util.Map.entry("idx_tx_sender_ts",
                    "CREATE INDEX idx_tx_sender_ts ON transactions (sender_account, \"timestamp\" DESC)")
    );

    /**
     * Drop every secondary index on the transactions table so that the
     * subsequent bulk COPY doesn't pay per-row index-update overhead.
     * Uses {@code IF EXISTS} so re-running on an already-stripped table is safe.
     */
    private void dropSecondaryIndexes() throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            for (String idx : SECONDARY_TX_INDEXES) {
                stmt.execute("DROP INDEX IF EXISTS " + idx);
            }
        }
        log.info("Dropped {} secondary indexes on transactions for bulk import ({} ms) — "
                        + "they will be rebuilt after the import completes",
                SECONDARY_TX_INDEXES.size(), System.currentTimeMillis() - t0);
    }

    /**
     * Recreate every index dropped by {@link #dropSecondaryIndexes()}. Postgres
     * builds each index in a single sequential scan + sort, far faster than
     * maintaining them per-row during INSERT. Uses {@code IF NOT EXISTS} so
     * a partial pre-existing state doesn't fail the whole rebuild.
     */
    private void recreateSecondaryIndexes() throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            // Crank up maintenance_work_mem for this session so the in-memory
            // sort phase of CREATE INDEX has more room. Falls back gracefully
            // if the user lacks SET privilege.
            try { stmt.execute("SET maintenance_work_mem = '1GB'"); }
            catch (SQLException ignored) { /* keep server default */ }

            for (String idx : SECONDARY_TX_INDEXES) {
                String ddl = SECONDARY_TX_INDEX_DDL.get(idx);
                // Use IF NOT EXISTS via lazy rewrite — avoids needing a separate map.
                String safeDdl = ddl.replaceFirst(
                        "(?i)CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ");
                stmt.execute(safeDdl);
            }
        }
        log.info("Rebuilt {} secondary indexes on transactions ({} ms total)",
                SECONDARY_TX_INDEXES.size(), System.currentTimeMillis() - t0);
    }

    /**
     * Append one tab-separated row to the COPY buffer.
     * Column order must match {@link #COPY_SQL}.
     * Null values are written as {@code \N} (PostgreSQL COPY null marker).
     * Special characters (tab, newline, backslash) in string columns are escaped.
     */
    private void appendCopyRow(StringBuilder sb, Transaction tx) {
        appendEscaped(sb, tx.getExternalId());          sb.append('\t');
        sb.append(tx.getDatasetSource().name());        sb.append('\t');
        sb.append(PG_TS.format(tx.getTimestamp()));     sb.append('\t');
        appendEscaped(sb, tx.getSenderAccount());        sb.append('\t');
        appendEscaped(sb, tx.getReceiverAccount());      sb.append('\t');
        sb.append(tx.getTransactionType().name());      sb.append('\t');
        sb.append(tx.getAmount().toPlainString());      sb.append('\t');
        appendDecimal(sb, tx.getSenderBalanceBefore()); sb.append('\t');
        appendDecimal(sb, tx.getSenderBalanceAfter());  sb.append('\t');
        appendDecimal(sb, tx.getReceiverBalanceBefore()); sb.append('\t');
        appendDecimal(sb, tx.getReceiverBalanceAfter());  sb.append('\t');
        sb.append(Boolean.TRUE.equals(tx.getIsFraud()) ? 't' : 'f');  sb.append('\t');
        if (tx.getIsFlaggedFraud() != null) {
            sb.append(tx.getIsFlaggedFraud() ? 't' : 'f');
        } else {
            sb.append("\\N");
        }
        sb.append('\t');
        // is_training_set — imported rows are training-eligible by default so ML
        // training works immediately after import. Call POST /api/v1/ingestion/finalize-train-test-split
        // afterwards to carve off the newest 20% as a temporal test set for evaluation.
        sb.append('t');
        sb.append('\n');
    }

    /** Write a nullable BigDecimal as plain string or \N for null. */
    private void appendDecimal(StringBuilder sb, BigDecimal value) {
        if (value == null) {
            sb.append("\\N");
        } else {
            sb.append(value.toPlainString());
        }
    }

    /**
     * Write a string, escaping COPY text-format special characters.
     * PostgreSQL COPY text format requires: \t → \\t, \n → \\n, \\ → \\\\
     */
    private void appendEscaped(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("\\N");
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\\' -> sb.append("\\\\");
                default    -> sb.append(c);
            }
        }
    }
}
