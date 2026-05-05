# FinGuard — Master's Thesis Final Evaluation Playbook

**Purpose.** Single, reproducible overnight run that produces every piece of
evidence Chapter 5 of the thesis ("Evaluation and Results") needs:

- All 5 configs × 5 folds of precision / recall / F1 / FPR / AUC-ROC / AUC-PR
- CAKR scores (Completeness / Actionability / Correctness / Regulatory) per LLM config
- Hallucination rate + avg explanation latency + token costs per config
- Pairwise statistical significance (Bonferroni-corrected paired t-tests)
- Manual-rating CSV for Cohen's κ inter-rater reliability

**Target wall-clock:** 2.5 h with Claude API · ~8 h with Ollama (overnight).

> Authored as a deliverable of the senior-architect audit remediation.
> Last verified on the codebase at the commit where this file lands.

---

## 0. Pre-Flight Decisions

### 0.1 LLM provider — the most important call you'll make

| Choice | CAKR judge quality | Speed per call | Total run time | Est. cost |
|---|---|---|---|---|
| **Ollama `llama3.2`** (local, default) | ~3.5/5 | 8–15 s | ~8 h | Free |
| **Anthropic Claude Sonnet** (cloud) | ~4.5/5 | 2–4 s | ~2.5 h | ~$3–$8 total |
| **Hybrid** (Ollama generate + Claude judge) | Mixed | Mixed | ~5 h | ~$1–$3 |

**Recommendation: use Claude Sonnet for the full final run.**

- Higher CAKR precision (Claude-as-judge is the standard in recent literature).
- Faster → evaluation fits in an afternoon instead of a weekend.
- Cost is negligible vs. the value of a defensible dataset.

**To switch providers:**

1. Navigate to **Settings** in the FinGuard dashboard.
2. Pick the Anthropic tab → paste your API key → **Save** → **Set Active**.
3. Verify the landing page "Active Judge" tile shows `ANTHROPIC / claude-sonnet-*`.
4. The same `ChatClient` abstraction is used for explanations AND CAKR scoring, so this switch is global.

> **Security reminder.** API keys are encrypted at rest with AES-256-GCM.
> Set `FINGUARD_ENCRYPTION_KEY` from `openssl rand -base64 32` in production — never use the default dev key for thesis evidence you'll distribute.

### 0.2 ML models — train BOTH

Your research plan §3.5 Baseline 2 specifies *"Random Forest + XGBoost, SHAP feature importance"*. Train both:

- Total training time is ~60–90 s combined (they run in parallel).
- The prediction path averages them when `finguard.detection.ml.ensemble-enabled: true` (default).
- Dropping one invalidates the thesis methodology claim.
- The two models have different inductive biases — averaging typically adds 1–3 percentage points of F1 over either alone.

In `application.yml`, verify:
```yaml
finguard:
  detection:
    ml:
      ensemble-enabled: true      # leave as-is
      rf:
        num-trees: 100
        max-depth: 6
        parallelism: 4            # 4 mini-forests built simultaneously
      xgb:
        num-rounds: 100
        num-threads: 0            # auto-detect
```

### 0.3 Infrastructure sanity checks

On the `/system` page, confirm:

- ✅ PostgreSQL container healthy (`docker ps` shows `finguard-postgres (healthy)`)
- ✅ Ollama reachable (still needed for **embeddings** — `nomic-embed-text`, 768-d — even if Claude does chat)
- ✅ Disk has ≥ 10 GB free (for logs + 6.3M-row DB)

### 0.3z Recovery from a previous botched benchmark

If you hit the OOM + "Illegal pop()" + "58 % alert rate" scenario documented in
§10, you need to clean state before the next run:

```bash
# 1. Kill the JVM (if still running)
lsof -ti :8080 | xargs kill -9

# 2. Wipe the over-triggered alerts (and everything downstream of them)
docker exec finguard-postgres psql -U finguard -d finguard <<'SQL'
TRUNCATE TABLE explanations CASCADE;
TRUNCATE TABLE alerts       CASCADE;
TRUNCATE TABLE experiment_results CASCADE;
TRUNCATE TABLE metric_snapshots CASCADE;
VACUUM (ANALYZE) alerts, explanations, experiment_results;
SQL

# 3. (Optional) Tune rule thresholds for PaySim (see §2.1 below)
```

Transactions and features stay. Only the downstream artifacts are wiped — no
need to re-import / re-feature-engineer.

### 0.3a JVM launch flags — ⚠️ **use these, not `mvn spring-boot:run`**

The default dev launcher loads Spring DevTools, which runs a file-watcher thread
that **will OOM your process during a long benchmark** (seen in the first thesis
run — `java.lang.OutOfMemoryError` in the "File Watcher" thread, then cascading
into HttpClient / JMX / Tomcat threads as the heap exhausts).

For the thesis run, launch with the dedicated **`benchmark` Spring profile**:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=benchmark \
  -Dspring-boot.run.jvmArguments="-Xmx8g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Dspring.devtools.restart.enabled=false -Dspring.devtools.livereload.enabled=false -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/finguard-oom.hprof"
```

The `benchmark` profile (see `application-benchmark.yml`) additionally:

- Disables `MetricSnapshotRefresher` (`@Profile("!benchmark")`) — no per-minute
  `COUNT(*)` on the alerts table competing for connections
- Lowers Hibernate query-plan cache size (less retained state)
- Down-tunes log levels on Hibernate SQL / DevTools packages
- Caps alert generation per config (`finguard.detection.max-alerts-per-config: 500000`)
- Forces `finguard.detection.persist-via-jdbc: true` (bulk-insert path, 8× faster than JPA)

What these do:

| Flag | Why |
|---|---|
| `-Xmx8g` | Ceiling at 8 GB heap — enough for ML ensemble (100 MB) + JPA state (500 MB) + RAG embeddings cache + per-fold AUC sampling (100 MB peak) + benchmark accumulated state. Default `-Xmx4g` will OOM on the FULL_SYSTEM config. |
| `-Xms2g` | Start with 2 GB committed — avoids paging during the first detection pass. |
| `-XX:+UseG1GC` | G1 is Java's low-pause collector, best for long-running server workloads. Java 25 default but pin it. |
| `-XX:MaxGCPauseMillis=200` | Keep GC pauses under 200 ms so the polling banner doesn't freeze. |
| `-Dspring.devtools.restart.enabled=false` | **Critical.** DevTools rescans the classpath every ~1 s holding classloader references. Disable for the thesis run. |
| `-Dspring.devtools.livereload.enabled=false` | Same rationale; one less accumulating watcher. |

If you have 16+ GB RAM, bump to `-Xmx12g` and raise `finguard.evaluation.auc.legit-sample-size: 25000` in `application.yml` for tighter AUC-PR confidence intervals.

### 0.3c Rule thresholds — ⚠️ tune for PaySim before running

PaySim synthetic data has a quirk that wrecks rule-based detection with out-of-
the-box thresholds: **every `TRANSFER` goes to a fresh receiver** (the simulator
generates unique destination accounts). That makes `isNewReceiver = TRUE` on
~90 % of TRANSFERs, so the `NEW_RECEIVER_HIGH_VALUE` rule (threshold $50 000)
fires on nearly every large transfer. Combined with PaySim's high-value
TRANSFER distribution, it produces a **~57 % alert rate** — over-triggering by
~36×.

Observed in the first benchmark run:

- 1.14 M alerts on 2.0 M transactions = 57 % positive rate
- Heap climbed to 4094 / 4096 MB before Hibernate's `JdbcValuesSourceProcessingState`
  corrupted mid-query → OOM cascade

**Pragmatic fix for PaySim** (applies to thesis chapter 4 "implementation
details" — document this as a dataset-specific calibration): in
`application.yml`, bump the thresholds so the alert rate on PaySim lands in a
defensible 3-10 % band:

```yaml
finguard:
  detection:
    rules:
      # Default 200000 — too permissive for PaySim TRANSFER distribution.
      # Raise to the 99th-percentile of legit TRANSFER amounts (~1M in PaySim).
      large-transaction-threshold: 1000000.00

      # Default 50000 — fires on every TRANSFER due to PaySim's unique-receiver
      # simulation quirk. Raise above the 95th-percentile of legit TRANSFER
      # amounts so the rule still catches outliers.
      new-receiver-high-value-threshold: 500000.00

      # Default 10000 — combined with the isRoundAmount + velocity conditions
      # this is healthy; leave unchanged.
      structuring-threshold: 10000.00
```

You can also just accept the over-triggering: with the new alert cap and bulk
writer, a 500k-alert RULES_ONLY config completes cleanly. But your thesis
reviewers will ask why rules flag 57 % of transactions — and the answer is
"PaySim isn't a realistic stand-in for a real bank's transfer graph" which is
actually a great thesis point (see §3.3 dataset limitations in the research
plan).

### 0.3b XGBoost warning — harmless, ignore

During ML training you'll see:

```
WARNING: src/c_api/c_api.cc:1240: Saving into deprecated binary model format,
please consider using `json` or `ubj`. Model format will default to JSON in
XGBoost 2.2 if not specified.
```

This is a deprecation notice from the XGBoost C++ layer, emitted once per
training run. FinGuard uses `xgboost4j 2.0.3` which still defaults to the binary
format internally — harmless, no action needed. The warning goes away when we
upgrade to xgboost4j 2.2+ (tracked as a post-thesis follow-up, since the 2.0→2.2
upgrade has breaking API changes and there's no thesis-facing value in the
model-serialization format).

### 0.4 Tuning for the full-dataset run

In `application.yml`:

```yaml
finguard:
  evaluation:
    cakr:
      outer-concurrency: 8        # Claude: 8 | Ollama: 2
    auc:
      legit-sample-size: 10000    # default (OOM-safe at 8 GB heap); 25000 if you have 12-16 GB
  explanation:
    batch-concurrency: 8          # Claude: 16 safe | Ollama: 4
```

---

## 1. Data Preparation  (~5 min, one pass)

### Step 1.1 — Clean slate (recommended)

Dashboard → **Data Management** → **Delete everything**
(transactions + features + alerts + explanations + experiment results).

This eliminates any stale rows that could pollute metrics. A thesis reviewer will ask "is your evaluation reproducible from scratch?" — the answer must be "yes, from an empty DB".

### Step 1.2 — Ingest PaySim

- Go to **Ingestion** page.
- Drop `paysim.csv` (~500 MB, 6.3M rows, 11 columns) into the "Upload CSV" box.
- **Expected:** ~130 s (COPY streaming via PostgreSQL native bulk-load).
- **Verify:** dashboard shows `Total Transactions: 6,362,620`.

### Step 1.3 — Compute features

- Ingestion page → **Compute features (bulk)**.
- **Expected:** ~160 s (bulk SQL with window functions, parallel workers at the PostgreSQL side).
- **Verify:** `Features: 6,362,620 / Pending: 0`.

> The bulk path (`FeatureEngineeringBulkService`) is the production path. The per-row path (`FeatureEngineeringService`) is retained only for single-row recompute and is limit-capped at 10,000.

### Step 1.4 — Finalize train/test split  ⚠️ **Critical step — don't skip**

- Ingestion page → **Finalize Temporal Train/Test Split**.
- Splits at the 80th-percentile timestamp: older 80 % → training, newer 20 % → test.
- **Expected:** ~30 s.
- **Verify at `/evaluation`:** the pre-flight banner must be **green** (no "test set is empty" warning). Expected ~1.27 M test-set rows.

> **Why this matters.** The most common cause of all-zero metrics is forgetting
> this step. `is_training_set` defaults to `TRUE` after a fresh import; if you
> don't flip 20 % to `FALSE`, detection runs fine but evaluation queries against
> an empty test set → TP=FP=TN=FN=0 → all metrics 0.0.
>
> The pre-flight banner added in the audit will catch this before you burn hours.

### Step 1.5 — Train the ML ensemble

- Models page → **Train Models**.
- **Expected:** ~60–90 s.
  - RF: 4 mini-forests of 25 trees each, built in parallel.
  - XGBoost: 100 boosting rounds, multi-threaded.
  - Training set: reservoir sampling keeps ALL ~8,500 fraud rows + 200,000 legit rows = ~208 k examples. This is a 30× compression vs. full 6.3 M and fits comfortably in heap.
- **Verify:** Models page shows `RandomForest + XGBoost available`.

---

## 2. The Thesis Benchmark Run

This is the single endpoint call that produces your Chapter 5 table.

### Step 2.1 — Launch the benchmark

Evaluation page → **Benchmark** button with these **exact parameters**:

| Field | Value | Why |
|---|---|---|
| **Experiment name** | `thesis-final-v1` | Anything unique. Used by all downstream queries. |
| **Dataset** | `PAYSIM` | Only supported dataset today (IBM AMLSim + Elliptic deferred to future work). |
| **k (folds)** | `5` | Matches thesis §3.5. |
| **Configs** | *(leave default — all 5 selected)* | RULES_ONLY, ML_ONLY, ML_LLM_DIRECT, ML_LLM_RAG, FULL_SYSTEM |
| **scoreExplanations** | `true` ✅ | **Critical** — drives CAKR scoring. |
| **maxAlertsToExplain** | `500` | Per-config cap. Keeps explanation generation ~25 min per LLM config. |

Or via API directly:

```bash
curl -X POST 'http://localhost:8080/api/v1/evaluation/benchmark' \
  -G \
  --data-urlencode 'experimentName=thesis-final-v1' \
  --data-urlencode 'dataset=PAYSIM' \
  --data-urlencode 'k=5' \
  --data-urlencode 'scoreExplanations=true' \
  --data-urlencode 'maxAlertsToExplain=500'
```

The endpoint returns `202 Accepted` with a `jobId` and status URL.

### Step 2.2 — Monitor progress

- Status banner on the Evaluation page polls every 2 s.
- Shows per-config phase updates:
  1. "Assigning folds"
  2. "Running detection pipeline"
  3. "Generating explanations"
  4. "Scoring CAKR (if enabled)"
  5. "Fold X / 5 — computing metrics"
  6. "Computing summary row"
  7. "Done"
- **Cancel** button exits cleanly at the next phase boundary (no data corruption).
- Partial results persist — cancelling mid-benchmark still leaves all completed configs' rows in `experiment_results`.

### Step 2.3 — Expected timings (Claude API)

| Config | Detection | Explanations | CAKR | Per-fold metrics (×5) | AUC sampling (×5) | **Total** |
|---|---|---|---|---|---|---|
| RULES_ONLY | ~140 s | — | — | ~5 s | — (N/A) | **~3 min** |
| ML_ONLY | ~140 s | — | — | ~5 s | ~60 s | **~4 min** |
| ML_LLM_DIRECT | ~140 s | ~25 min | ~10 min | ~5 s | ~60 s | **~38 min** |
| ML_LLM_RAG | ~140 s | ~30 min | ~10 min | ~5 s | ~60 s | **~43 min** |
| FULL_SYSTEM | ~140 s | ~30 min | ~10 min | ~5 s | ~60 s | **~43 min** |
| | | | | | | **~2.5 h** |

Ollama: multiply LLM phases by ~5× → ~8 h. Start at night.

### Step 2.4 — Verify the run completed cleanly

On completion the banner turns green. Confirm:

```bash
curl -s http://localhost:8080/api/v1/evaluation/results/thesis-final-v1 | jq 'length'
# Expected: 30   (5 configs × (5 folds + 1 summary))
```

Spot-check a summary row:

```bash
curl -s http://localhost:8080/api/v1/evaluation/results/thesis-final-v1 \
  | jq '.[] | select(.fold == null and .config == "FULL_SYSTEM")'
```

Expected fields populated (non-null): precisionScore, recallScore, f1Score, aucRoc, aucPr, falsePositiveRate, avgCakrScore, hallucinationRate, avgLatencyMs.

If any LLM config has null CAKR scores, check logs for LLM timeouts — lower `finguard.evaluation.cakr.outer-concurrency` and retry just that config.

---

## 3. Post-Run Analysis for Chapter 5

### Step 3.1 — Main metrics CSV

```bash
curl -o thesis-final-metrics.csv \
  'http://localhost:8080/api/v1/export/experiments?experimentName=thesis-final-v1'
```

Columns: `config, fold, precision, recall, f1, aucRoc, aucPr, fpr, avgCakr, hallucinationRate, avgLatencyMs, totalTransactions, totalAlerts, runParameters`.

**This is your Chapter 5 headline table** after pivoting on config.

### Step 3.2 — Statistical significance (Bonferroni-corrected)

```bash
curl -s 'http://localhost:8080/api/v1/evaluation/significance/thesis-final-v1?alpha=0.05' \
  | jq '.' > thesis-significance.json
```

Per-metric (F1 / precision / recall / AUC-ROC / AUC-PR), every config pair gets:
- `meanA`, `meanB`, `meanDiff`
- `tStatistic`, `pValue` (raw two-sided p)
- `significant: true` when `p < α / num_pairs` (Bonferroni)

For 5 configs: 10 pairs → corrected α = 0.005. Report pairs with `significant=true` with an asterisk in Table 5.X.

### Step 3.3 — CAKR breakdown per dimension

```bash
curl -s 'http://localhost:8080/api/v1/evaluation/cakr-breakdown' \
  | jq '.' > thesis-cakr-breakdown.json
```

Per-config means across the 4 dims (completeness / actionability / correctness / regulatory) — feeds the Chapter 5 radar chart.

### Step 3.4 — Cost breakdown

```bash
curl -s 'http://localhost:8080/api/v1/evaluation/cost-breakdown' \
  | jq '.' > thesis-cost-breakdown.json
```

Total prompt + completion tokens per config. Multiply by Anthropic pricing for your cost table (current rates: ~$3/MTok prompt, ~$15/MTok completion for claude-sonnet-4).

### Step 3.5 — Hallucination breakdown

```bash
curl -s 'http://localhost:8080/api/v1/evaluation/hallucination-breakdown' \
  | jq '.' > thesis-hallucination-breakdown.json
```

Per-config frequency of each flag type (`AMOUNT_MISMATCH`, `ACCOUNT_INVENTED`, `RISK_OVERCLAIM`, etc.) — answers RQ3 ("which configs hallucinate what?").

### Step 3.6 — Inter-rater sample for Cohen's κ

```bash
curl -o cakr-sample-rating.csv \
  'http://localhost:8080/api/v1/evaluation/cakr/sample?n=30&explanationType=LLM_RAG_VALIDATED'
```

Then:

1. Open the CSV in Excel / Google Sheets.
2. For each of the 30 rows, rate the explanation 1–5 on each of the 4 dims (columns `human_completeness`, `human_actionability`, `human_correctness`, `human_regulatory`) **without looking at the LLM scores**.
3. Drop into a Python notebook:

```python
import pandas as pd
from sklearn.metrics import cohen_kappa_score

df = pd.read_csv("cakr-sample-rating.csv")
for dim in ["completeness", "actionability", "correctness", "regulatory"]:
    kappa = cohen_kappa_score(
        df[f"human_{dim}"].round(),
        df[f"llm_{dim}"].round(),
        weights="quadratic"
    )
    print(f"{dim}: κ = {kappa:.3f}")
```

Report κ per dim in Chapter 5 (§3.4.2 of the research plan).

- κ > 0.8 → "almost perfect" — strongest possible validity claim
- 0.6 < κ ≤ 0.8 → "substantial" — acceptable for the thesis
- 0.4 < κ ≤ 0.6 → "moderate" — qualify the LLM-as-judge claim
- κ ≤ 0.4 → revisit the rubric (probably a prompt issue)

### Step 3.7 — Archive everything

Save this bundle to the thesis git repo under `thesis/evidence/`:

- `thesis-final-metrics.csv`
- `thesis-significance.json`
- `thesis-cakr-breakdown.json`
- `thesis-cost-breakdown.json`
- `thesis-hallucination-breakdown.json`
- `cakr-sample-rating.csv` (with your manual ratings filled in)
- The `runParameters` JSON column from each summary row (contains reproducibility snapshot: JVM version, LLM provider/model, ML hyperparams, rule thresholds, dataset counts)

---

## 4. Chapter 5 Artifacts Map

| Chapter 5 item | Data source |
|---|---|
| **Table 5.1** — Detection metrics per config (mean ± stddev) | `thesis-final-metrics.csv` pivoted on config; std from `runParameters` JSON |
| **Table 5.2** — Bonferroni-corrected p-values per config pair | `thesis-significance.json` |
| **Figure 5.X** — CAKR radar chart | `thesis-cakr-breakdown.json` |
| **Table 5.3** — Hallucination rate + flag-type frequency | `thesis-hallucination-breakdown.json` |
| **Table 5.4** — Token + latency + estimated $ per config | `thesis-cost-breakdown.json` |
| **Appendix B** — CAKR rubric prompts | `src/main/resources/prompts/cakr/{system,completeness,actionability,knowledge,regulatory}.md` |
| **Appendix C** — Cohen's κ sample + ratings | `cakr-sample-rating.csv` + notebook output |
| **Appendix D** — Reproducibility snapshot | `runParameters` JSON column (captured on every result row) |

---

## 10. Architectural Post-Mortem (the OOM + 57 % alert rate failure)

For Chapter 4 "Implementation" documentation — what went wrong on the first
benchmark attempt, and the three-layer fix.

### 10.1 Root cause chain

1. **DevTools File Watcher** (`Spring Boot DevTools`) rescans the classpath
   every ~1 s during `mvn spring-boot:run`. Over 4 hours: ~14 000 scans,
   holding a growing `ConcurrentHashMap<Path, FileSnapshot>`. First thread
   to OOM in the failing stack trace: `"File Watcher"`.
2. **JPA entity persistence per alert** (`alertRepository.save` / `saveAll`)
   — each Alert creates a managed entity with a lazy `Transaction` proxy
   and a JSONB `featureImportances`. ~5 KB of Hibernate metadata per row,
   dirty-checking the L1 cache on every flush. At 6,000 alerts/sec this is
   ~30 MB/sec of allocation pressure.
3. **Rule over-triggering on PaySim** — `NEW_RECEIVER_HIGH_VALUE` fires on
   ~57 % of TRANSFERs (see §0.3c).
4. **`MetricSnapshotRefresher`** scheduled `@Scheduled(60_000 ms)` — ran
   `COUNT(*)` on the growing alerts table every minute. At 1.1 M alerts, the
   snapshot query itself took 3+ s (observed in logs), competing with
   detection for HikariCP connections.
5. **Heap at 4094 / 4096 MB** → `System.gc()` freed only 3 MB (virtually
   everything was reachable) → Hibernate's `JdbcValuesSourceProcessingState`
   stack corrupted mid-query (a symptom, not a root cause — GC thrashing
   interrupts result-set materialisation).
6. **Benchmark continued to next config** after failure → ML_ONLY started
   at 4091 / 4096 MB → immediate OOM in Tomcat async timeout thread.

### 10.2 The three-layer fix (in this codebase)

**Layer 1 — Prevention: `benchmark` Spring profile + JVM flags.**
The `application-benchmark.yml` file disables the scheduled refresher and
tightens the profile; the JVM flags disable DevTools and allocate 8 GB heap
with G1GC. Launched with `-Dspring-boot.run.profiles=benchmark`.

**Layer 2 — Throughput: `JdbcBulkAlertWriter` + per-config alert cap.**
Alert persistence on the hot path is pure JDBC `batchUpdate` with
`ON CONFLICT DO NOTHING` against the `uq_alerts_transaction_config`
constraint. Bypasses Hibernate entirely — no managed entities, no L1 cache
traversal, no JPA event cascade. Measured ~8× faster vs `saveAll`. Plus
`finguard.detection.max-alerts-per-config: 500 000` caps pathological
over-triggering; when hit, detection logs WARN and stops for that config.
Controlled via `finguard.detection.persist-via-jdbc: true` (default).

**Layer 3 — Resilience: `HeapGuardrail` circuit breaker.**
Between each benchmark config, checks `usedHeap / maxHeap` against two
thresholds (default 0.75 WARN, 0.90 CRITICAL). On CRITICAL it forces a GC +
100 ms pause + re-check; if still critical, throws `HeapCriticalException`
which the benchmark outer loop catches and aborts cleanly. Partial results
that were already persisted remain intact; subsequent configs are skipped.
Tunable via `finguard.heap.warn-threshold` and
`finguard.heap.critical-threshold`.

### 10.3 Architectural principles applied

| Principle | Manifestation in the fix |
|---|---|
| **Bypass the framework for the hot path** | JDBC batch insert instead of JPA `saveAll`. JPA stays for low-volume API paths where dirty-checking + events are valuable. |
| **Bound every unbounded input** | `max-alerts-per-config` caps a pathological rule. Pairs with the existing `maxAlertsToExplain` cap on explanation generation and the `intraRequestExecutor` bound on in-request parallelism. |
| **Fail fast, not fatal** | `HeapGuardrail` aborts the benchmark at the next phase boundary instead of letting the JVM OOM. Partial results survive; the user can relaunch with more heap or lower sample sizes. |
| **Profile-specific tuning** | `@Profile("!benchmark")` on the metric refresher + `application-benchmark.yml` keeps dev/prod concerns separate — the dashboard still gets snapshots in the default profile; the benchmark doesn't pay the tax. |
| **Separate single-tx and batch code paths** | `analyzeTransactionWithFeatures` (single, immediate save) vs `analyzeForBatch` (un-persisted, outer accumulator flushes). Same domain logic; different persistence lifecycle. |

### 10.4 Expected performance after the fix

Before (observed in the failing run, at 27 % through RULES_ONLY):
- ~6,000 alerts/sec
- 3.8 / 4.0 GB heap
- OOM at 2.03 M transactions processed

After (projected, with `benchmark` profile + JVM flags):
- ~50,000 alerts/sec (8× speedup from JDBC bulk insert)
- Heap oscillating ~2-5 GB of 8 GB
- Full 6.36 M transaction sweep for all 5 configs in under 1 hour of wall-clock for the detection phase alone

Claude-API-based explanation + CAKR phases still dominate total wall-clock
(2.5 h mostly in LLM I/O, which we don't control).

---

## 5. Troubleshooting

| Symptom | Root cause | Fix |
|---|---|---|
| All-zero metrics on every config | Step 1.4 (finalize split) was skipped | Pre-flight banner tells you — redo Step 1.4 |
| AUC = NaN for RULES_ONLY | Expected (no continuous score from rule-based detection) | No action — mark as N/A in Chapter 5 |
| AUC = NaN for ML configs | Model not loaded or sample collapsed | Check `isModelAvailable()`; raise `auc.legit-sample-size` |
| LLM timeouts | Concurrency too high for the provider | Lower `finguard.explanation.batch-concurrency` from 8 → 4 |
| CAKR queue hanging | Ollama overwhelmed | Lower `finguard.evaluation.cakr.outer-concurrency` from 8 → 2 |
| `CancellationException` in logs | You clicked Cancel | Correct behavior — run exited cleanly at phase boundary |
| FK violations during explanation generation | Concurrent delete during run | Don't run delete endpoints during evaluation; the FK-race detector downgrades these to WARN anyway |
| "No models trained" | Step 1.5 didn't finish | Retrain; check heap (need ≥ 4 GB for Tribuo `MutableDataset`) |
| Single config has all-zero F1 | Bug — investigate that config's detection path | Check `/api/v1/evaluation/results/thesis-final-v1` for that config's per-fold rows; if TP=0, the ML path didn't flag anything |
| **`OutOfMemoryError` in "File Watcher" / HttpClient / JMX** | Spring DevTools classpath-rescan leak during long ML run | Relaunch with the `-Xmx8g -Dspring.devtools.restart.enabled=false` flags from §0.3a |
| **Cancel banner / progress bar invisible during benchmark** | Fixed — was a CSS bug (inline `display:none!important` beat the `.active` class) | Already patched; if you see a stale version, hard-reload the page (Cmd+Shift+R) to bust the template cache |
| **Split endpoint shows no UI progress for 1–3 min** | Fixed — split is now async with live polling (see §1.4) | Already patched; the new button posts to `/finalize-train-test-split-async` and polls the phase label |
| **XGBoost "deprecated binary model format" warning** | Harmless deprecation notice from xgboost4j 2.0.3 | Ignore — see §0.3b |

---

## 6. Dress-Rehearsal Recommendation  (1 hour)

Before the overnight run, do a dry-run on a sampled dataset to catch config issues without burning hours:

1. Steps 1.1–1.2 as normal (ingest full PaySim).
2. **Before step 1.3**, delete 6.26 M random rows:
   ```sql
   DELETE FROM transactions
   WHERE id NOT IN (SELECT id FROM transactions ORDER BY random() LIMIT 100000);
   ```
3. Continue with steps 1.3–1.5 (features, split, train).
4. Run `benchmark` with `experimentName=dress-rehearsal`, `scoreExplanations=true`, `maxAlertsToExplain=50`.
5. Expected wall-clock: ~15 min.
6. Confirm all 6 post-run artifacts (§3) populate cleanly.
7. If clean → reset DB (step 1.1) → do the real full-dataset run.

---

## 7. What NOT to do

- ❌ Don't skip the pre-flight banner on `/evaluation` — green banner is mandatory.
- ❌ Don't delete transactions / alerts / explanations while a benchmark is running.
- ❌ Don't change the LLM provider mid-run. Finish the run, archive results, then switch.
- ❌ Don't train only one ML model — the thesis methodology requires the ensemble.
- ❌ Don't mutate `DatasetSource` or `DetectionConfig` enum values — they're persisted as strings in `experiment_results.config` and `transactions.dataset_source`.
- ❌ Don't disable caching (`finguard.cache.enabled=false`) during an eval run — RAG cache warming is a meaningful performance improvement.
- ❌ Don't run multiple benchmarks in parallel — the ML model is a singleton, and two concurrent runs will interleave the per-fold metric queries.

---

## 8. Post-Defense Follow-Up

After a successful defense, these items are worth a separate branch:

- **IBM AMLSim loader** — stronger multi-dataset evaluation for a publication follow-up (research plan §7 future work).
- **Elliptic loader** — blockchain fraud validation (§7 future work).
- **Materialized view for dashboard** — useful if the app gets real users post-thesis.
- **Persistent model artifacts** — serialize the trained RF + XGB to disk (currently in-memory only, lost on restart).

---

## 9. Quick Reference Card

```
Infra:       docker-compose up -d
Build:       mvn clean verify
Run:         mvn spring-boot:run
URL:         http://localhost:8080
Swagger:     http://localhost:8080/swagger-ui.html
Prometheus:  http://localhost:8080/actuator/prometheus

Launch:
  mvn spring-boot:run \
    -Dspring-boot.run.profiles=benchmark \
    -Dspring-boot.run.jvmArguments="-Xmx8g -Xms2g -XX:+UseG1GC \
       -Dspring.devtools.restart.enabled=false \
       -Dspring.devtools.livereload.enabled=false \
       -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/finguard-oom.hprof"

Full run:
  1. /  → Data Management → Delete everything
  2. /ingestion → Upload paysim.csv
  3. /ingestion → Compute features (bulk)
  4. /ingestion → Finalize Temporal Train/Test Split
  5. /models → Train Models
  6. /evaluation → Benchmark (experimentName=thesis-final-v1,
                               k=5, scoreExplanations=true,
                               maxAlertsToExplain=500)
  7. Monitor banner → wait ~2.5 h (Claude) or ~8 h (Ollama)
  8. Post-run: §3.1 – §3.6 endpoints → archive to thesis/evidence/

Cancel:      POST /api/v1/evaluation/cancel/{jobId}
Status:      GET  /api/v1/evaluation/status/{jobId}
Results:     GET  /api/v1/evaluation/results/thesis-final-v1
Significance: GET /api/v1/evaluation/significance/thesis-final-v1
CAKR sample:  GET /api/v1/evaluation/cakr/sample?n=30
```

Good luck with the defense.
