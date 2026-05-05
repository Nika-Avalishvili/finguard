package dev.finguard.ingestion.service;

import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Computes ML features for imported transactions.
 *
 * <p>Per-row computation using virtual threads. <b>Audit B-3 / D-1:</b> the
 * bulk SQL path ({@link FeatureEngineeringBulkService}) is the default production
 * feature path. This per-row service is retained for small recomputes and
 * debugging; it issues 3 queries per transaction and does not scale to the
 * 6M-row PaySim dataset — use the bulk path instead.</p>
 *
 * <h2>Performance design</h2>
 * <ul>
 *   <li>Cursor-based pagination ({@code id > lastId}) avoids OFFSET scans on large tables.</li>
 *   <li>Virtual threads per transaction keep CPU busy while DB I/O is in-flight.</li>
 *   <li>Combined queries: velocity (1h+24h) in one round-trip; avg+stddev+diversity in one
 *       round-trip — 3 queries per transaction instead of 5.</li>
 *   <li>{@link FeatureEngineeringBatchService} commits each batch in its own
 *       {@code REQUIRES_NEW} transaction so a crash loses at most one batch,
 *       not all progress.</li>
 * </ul>
 */
@Service
public class FeatureEngineeringService {

    /**
     * Maximum rows allowed through the per-row path per call (audit B-3).
     * Above this, callers must use {@link FeatureEngineeringBulkService} — the
     * per-row path does 3 DB round-trips per row and becomes unworkable for
     * multi-million-row datasets.
     */
    public static final long MAX_PER_ROW_LIMIT = 10_000L;

    private static final Logger log = LoggerFactory.getLogger(FeatureEngineeringService.class);

    /** Transaction types that are historically high-risk for fraud in PaySim. */
    private static final Set<TransactionType> HIGH_RISK_TYPES =
            Set.of(TransactionType.TRANSFER, TransactionType.CASH_OUT);

    /**
     * Virtual-thread executor for concurrent feature computation within each batch.
     * Each transaction's DB queries (read-only) run in parallel, releasing carrier
     * threads while waiting for connections — ideal for I/O-bound workloads.
     */
    private static final Executor FEATURE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final TransactionRepository transactionRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final FeatureEngineeringBatchService batchSaver;

    @Value("${finguard.ingestion.batch-size:5000}")
    private int batchSize;

    public FeatureEngineeringService(TransactionRepository transactionRepository,
                                      TransactionFeaturesRepository featuresRepository,
                                      FeatureEngineeringBatchService batchSaver) {
        this.transactionRepository = transactionRepository;
        this.featuresRepository = featuresRepository;
        this.batchSaver = batchSaver;
    }

    /**
     * Compute features for all transactions that don't have them yet.
     * Delegates to {@link #computeAllFeatures(long)} with no limit.
     */
    public long computeAllFeatures() {
        return computeAllFeatures(Long.MAX_VALUE);
    }

    /**
     * Compute features for up to {@code limit} transactions that don't have features yet.
     *
     * <p>Each batch is committed independently via {@link FeatureEngineeringBatchService}
     * ({@code REQUIRES_NEW}). This means a crash loses at most one batch of work, not
     * the entire computation. It also releases DB connections and L1 cache between batches,
     * keeping memory usage flat for multi-million row datasets.</p>
     *
     * @param limit max number of transactions to process (use Long.MAX_VALUE for all)
     * @return number of feature rows computed
     */
    public long computeAllFeatures(long limit) {
        if (limit > MAX_PER_ROW_LIMIT) {
            throw new dev.finguard.config.exception.BadRequestException(
                    "Per-row feature computation is limited to " + MAX_PER_ROW_LIMIT +
                    " rows (requested " + limit + "). For bulk recomputes, use the bulk SQL path " +
                    "(FeatureEngineeringBulkService.computeAllBulk) instead.");
        }
        log.info("Starting per-row feature computation (limit={})", limit);
        long totalComputed = 0;
        long lastId = 0;

        while (totalComputed < limit) {
            int thisBatch = (int) Math.min(batchSize, limit - totalComputed);
            List<Transaction> batch = transactionRepository
                    .findWithoutFeaturesAfterId(lastId, PageRequest.of(0, thisBatch));

            if (batch.isEmpty()) {
                break;
            }

            // Compute features for all transactions in the batch concurrently.
            // Each virtual thread makes its own read-only DB connections via independent
            // repository transactions — safe because feature queries only look at
            // prior history (timestamp < tx.timestamp), never the batch being processed.
            List<CompletableFuture<TransactionFeatures>> futures = batch.stream()
                    .map(tx -> CompletableFuture.supplyAsync(() -> computeFeatures(tx), FEATURE_EXECUTOR))
                    .toList();

            List<TransactionFeatures> computed = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // Commit this batch independently (REQUIRES_NEW transaction in batchSaver).
            // Each batch is durable immediately — no single giant transaction spanning
            // the entire computation.
            batchSaver.saveBatch(computed);

            lastId = batch.getLast().getId();
            totalComputed += batch.size();

            if (totalComputed % 10_000 == 0) {
                log.info("Feature computation progress: {} computed so far", String.format("%,d", totalComputed));
            }
        }

        log.info("Feature computation complete: {} features computed", String.format("%,d", totalComputed));
        return totalComputed;
    }

    /**
     * Compute all features for a single transaction using 3 DB round-trips.
     *
     * <p>Query reduction vs the naive approach:
     * <ol>
     *   <li>{@code findVelocityCounts} — returns 1h and 24h counts in one aggregate query,
     *       avoiding loading full Transaction objects just to count them.</li>
     *   <li>{@code findSenderStats7d} — returns AVG + STDDEV_POP + COUNT(DISTINCT receiver)
     *       in one aggregate query, replacing three separate window queries.</li>
     *   <li>{@code existsPriorTransfer} — single COUNT query for new-receiver detection.</li>
     * </ol>
     * </p>
     *
     * @param tx the transaction to compute features for
     * @return a TransactionFeatures entity linked to the transaction
     */
    public TransactionFeatures computeFeatures(Transaction tx) {
        TransactionFeatures features = new TransactionFeatures();
        features.setTransaction(tx);

        LocalDateTime txTime = tx.getTimestamp();
        // Exclusive upper bound: subtract 1 microsecond to exclude the current transaction
        // from all "prior history" queries (minusNanos(1000) avoids JDBC rounding to same second).
        LocalDateTime beforeTx = txTime.minusNanos(1_000);
        String sender = tx.getSenderAccount();

        // --- Temporal features ---
        features.setHourOfDay((short) txTime.getHour());
        features.setDayOfWeek((short) txTime.getDayOfWeek().getValue());

        // --- Velocity features (1h and 24h windows, single round-trip) ---
        // findVelocityCounts returns [count_24h, count_1h] without loading Transaction objects.
        LocalDateTime oneHourBefore = txTime.minusHours(1);
        LocalDateTime twentyFourHoursBefore = txTime.minusHours(24);

        Object[] velocityCounts = transactionRepository
                .findVelocityCounts(sender, twentyFourHoursBefore, beforeTx, oneHourBefore).get(0);
        features.setTxVelocity24h(toInt(velocityCounts[0]));
        features.setTxVelocity1h(toInt(velocityCounts[1]));

        // --- Amount statistics + receiver diversity (7-day window, single round-trip) ---
        // findSenderStats7d returns [avg_amount, stddev_amount, receiver_diversity],
        // replacing 3 separate queries that all hit the same sender+7d window.
        LocalDateTime sevenDaysBefore = txTime.minusDays(7);

        Object[] senderStats = transactionRepository
                .findSenderStats7d(sender, sevenDaysBefore, beforeTx).get(0);
        BigDecimal avgAmount = toBigDecimal(senderStats[0]);
        BigDecimal stddev    = toBigDecimal(senderStats[1]);
        int receiverDiversity = toInt(senderStats[2]);

        features.setAvgAmount7d(avgAmount);
        features.setReceiverDiversity7d(receiverDiversity);

        // Amount ratio to average
        if (avgAmount.compareTo(BigDecimal.ZERO) > 0) {
            features.setAmountRatioToAvg(
                    tx.getAmount().divide(avgAmount, 6, RoundingMode.HALF_UP).doubleValue());
        } else {
            features.setAmountRatioToAvg(0.0);
        }

        // Z-score = (amount - mean) / stddev
        if (stddev.compareTo(BigDecimal.ZERO) > 0) {
            features.setAmountZscore(
                    tx.getAmount().subtract(avgAmount)
                            .divide(stddev, 6, RoundingMode.HALF_UP)
                            .doubleValue());
        } else {
            features.setAmountZscore(0.0);
        }

        // --- Balance change ratio ---
        if (tx.getSenderBalanceBefore() != null
                && tx.getSenderBalanceBefore().compareTo(BigDecimal.ZERO) > 0) {
            features.setBalanceChangeRatio(
                    tx.getAmount()
                            .divide(tx.getSenderBalanceBefore(), 6, RoundingMode.HALF_UP)
                            .doubleValue());
        } else {
            features.setBalanceChangeRatio(0.0);
        }

        // --- Is new receiver (first time this sender → receiver pair, all-time check) ---
        boolean hasPriorTransfer = transactionRepository
                .existsPriorTransfer(sender, tx.getReceiverAccount(), txTime);
        features.setIsNewReceiver(!hasPriorTransfer);

        // --- Simple derived features ---
        features.setIsRoundAmount(isRoundAmount(tx.getAmount()));
        features.setIsHighRiskType(HIGH_RISK_TYPES.contains(tx.getTransactionType()));

        // --- Feature vector as JSON (for ML model input) ---
        features.setFeatureVector(buildFeatureVectorJson(features, tx));

        return features;
    }

    /**
     * Check if an amount is "round" — a common structuring pattern.
     * Amounts like 10000.00, 5000.00, 50000.00 are suspicious.
     */
    private boolean isRoundAmount(BigDecimal amount) {
        BigDecimal remainder = amount.remainder(new BigDecimal("1000"));
        return remainder.compareTo(BigDecimal.ZERO) == 0 && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Build a JSON string of the feature vector for ML model consumption.
     */
    private String buildFeatureVectorJson(TransactionFeatures f, Transaction tx) {
        return String.format(
                "{\"amount\": %s, \"amountZscore\": %s, \"txVelocity1h\": %d, " +
                "\"txVelocity24h\": %d, \"avgAmount7d\": %s, \"amountRatioToAvg\": %.6f, " +
                "\"balanceChangeRatio\": %s, \"receiverDiversity7d\": %d, " +
                "\"isNewReceiver\": %b, \"hourOfDay\": %d, \"dayOfWeek\": %d, " +
                "\"isRoundAmount\": %b, \"isHighRiskType\": %b}",
                tx.getAmount().toPlainString(),
                f.getAmountZscore() != null ? f.getAmountZscore() : 0.0,
                f.getTxVelocity1h()  != null ? f.getTxVelocity1h()  : 0,
                f.getTxVelocity24h() != null ? f.getTxVelocity24h() : 0,
                f.getAvgAmount7d() != null ? f.getAvgAmount7d().toPlainString() : "0",
                f.getAmountRatioToAvg() != null ? f.getAmountRatioToAvg() : 0.0,
                f.getBalanceChangeRatio() != null ? f.getBalanceChangeRatio() : 0.0,
                f.getReceiverDiversity7d(),
                f.getIsNewReceiver(),
                f.getHourOfDay(),
                f.getDayOfWeek(),
                f.getIsRoundAmount(),
                f.getIsHighRiskType()
        );
    }

    /** Safe conversion of any numeric Object to int. Returns 0 for null. */
    private static int toInt(Object val) {
        if (val == null) return 0;
        return ((Number) val).intValue();
    }

    /**
     * Safe conversion of any numeric Object to BigDecimal. Returns BigDecimal.ZERO for null.
     * Handles BigDecimal (from AVG on NUMERIC) and Double (from STDDEV_POP on NUMERIC).
     */
    private static BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Double d) return BigDecimal.valueOf(d);
        return new BigDecimal(val.toString());
    }
}
