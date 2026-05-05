-- liquibase formatted sql

-- changeset nika.avalishvili:016-temporal-train-test-split splitStatements:false
-- comment: Replace random-modulo train/test split with a temporal 80/20 split.
--          Audit A-5: random split allowed feature windows to span the entire
--          timeline so test rows' features were computed using data the model
--          never should have seen at deployment. A temporal split (older 80%
--          train, newer 20% test) satisfies temporal causality.
--
--          The 80th-percentile timestamp (percentile_disc) is used as the cut
--          so exactly ~80% of rows are training — robust to uneven time
--          distributions. No-op when the table is empty (fresh install); the
--          feature-computation pipeline then populates is_training_set on ingest.
-- runOnChange: false

UPDATE transactions t
   SET is_training_set = (t.timestamp < sub.cutoff)
  FROM (
      SELECT percentile_disc(0.80) WITHIN GROUP (ORDER BY timestamp) AS cutoff
        FROM transactions
  ) sub
 WHERE sub.cutoff IS NOT NULL;
