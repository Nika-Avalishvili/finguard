# FinGuard User Guide

## Complete Guide to the Transaction Anomaly Detection & Explanation System

**Version:** 1.0
**Date:** March 2026
**Author:** Nika Avalishvili

---

## Table of Contents

1. [What is FinGuard?](#1-what-is-finguard)
2. [What is Anti-Money Laundering (AML)?](#2-what-is-anti-money-laundering-aml)
3. [System Architecture](#3-system-architecture)
4. [Prerequisites & Setup](#4-prerequisites--setup)
5. [Step-by-Step: Your First Complete Walkthrough](#5-step-by-step-your-first-complete-walkthrough)
6. [Page-by-Page Guide](#6-page-by-page-guide)
7. [How Detection Works (In Detail)](#7-how-detection-works-in-detail)
8. [How LLM Explanations Work](#8-how-llm-explanations-work)
9. [How Evaluation Works](#9-how-evaluation-works)
10. [Test Data & Expected Results](#10-test-data--expected-results)
11. [API Reference](#11-api-reference)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. What is FinGuard?

FinGuard is an LLM-augmented transaction anomaly detection and explanation system. It combines three detection approaches — rule-based, machine learning, and large language models — to identify suspicious financial transactions and generate human-readable explanations for compliance analysts.

### What it does:

- **Ingests** financial transaction data from CSV datasets (PaySim format)
- **Engineers features** for each transaction (velocity, z-scores, diversity metrics)
- **Detects anomalies** using configurable rule thresholds and/or ML classifiers
- **Generates explanations** using LLMs (either direct or RAG-augmented with fraud pattern knowledge)
- **Evaluates** detection quality with precision/recall/F1 and explanation quality with the CAKR framework
- **Presents** everything through a dark-themed web dashboard

### Who is it for?

This is a master's thesis project demonstrating how LLMs can augment traditional AML systems. It is not a production AML system.

---

## 2. What is Anti-Money Laundering (AML)?

Anti-Money Laundering (AML) refers to laws, regulations, and procedures designed to prevent criminals from disguising illegally obtained funds as legitimate income. Financial institutions must monitor transactions for suspicious activity and file reports with regulators.

### Common Fraud Patterns FinGuard Detects:

| Pattern | What It Is | Real-World Example |
|---------|-----------|-------------------|
| **Structuring (Smurfing)** | Breaking a large transaction into many smaller ones just below a reporting threshold (e.g., $10,000 in the US) | Someone deposits $9,000 four times across different branches instead of one $36,000 deposit |
| **Large Unusual Transfer** | A transaction significantly larger than normal for that account | An account that usually moves $500 suddenly transfers $250,000 |
| **Rapid Succession** | Multiple transactions from the same account in a very short time | 5 transfers from one account within 30 minutes |
| **New Receiver + High Value** | A large amount sent to a receiver the sender has never transacted with before | First-ever transfer of $75,000 to an unknown account |
| **Account Takeover** | Account behavior suddenly changes (velocity, amounts, destinations) | A dormant account suddenly drains its entire balance |

### Regulatory Context:

- **BSA (Bank Secrecy Act)**: US law requiring financial institutions to report suspicious activity
- **FinCEN**: US Financial Crimes Enforcement Network, receives Suspicious Activity Reports (SARs)
- **FATF**: International body setting global AML standards
- **EU AMLD**: European Anti-Money Laundering Directives

FinGuard's knowledge base includes references to these regulatory frameworks.

---

## 3. System Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │              FinGuard System                 │
                    │                                             │
  CSV Upload ──────►│  ┌──────────┐    ┌───────────┐             │
                    │  │ Ingestion │───►│  Feature   │             │
                    │  │  Service  │    │ Engineering│             │
                    │  └──────────┘    └─────┬─────┘             │
                    │                        │                    │
                    │                        ▼                    │
                    │  ┌──────────────────────────────────┐      │
                    │  │      Detection Pipeline           │      │
                    │  │  ┌────────┐  ┌─────┐  ┌───────┐ │      │
                    │  │  │ Rules  │  │ ML  │  │ Alert │ │      │
                    │  │  │ Engine │  │Model│  │ Store │ │      │
                    │  │  └────────┘  └─────┘  └───┬───┘ │      │
                    │  └───────────────────────────┼──────┘      │
                    │                              │              │
                    │                              ▼              │
                    │  ┌──────────────────────────────────┐      │
                    │  │    LLM Explanation Engine          │      │
                    │  │  ┌────────┐  ┌─────┐  ┌───────┐ │      │
                    │  │  │ Prompt │  │ RAG │  │Hallu- │ │      │
                    │  │  │Builder │  │Store│  │cinator│ │      │
                    │  │  └────────┘  └─────┘  │ Check │ │      │
                    │  │                       └───────┘ │      │
                    │  └─────────────────────────────────┘      │
                    │                                             │
                    │  ┌──────────────────────────────────┐      │
                    │  │       Evaluation Framework         │      │
                    │  │  Precision/Recall/F1 + CAKR       │      │
                    │  └──────────────────────────────────┘      │
                    └─────────────────────────────────────────────┘
```

### Technology Stack:

| Component | Technology |
|-----------|-----------|
| Backend | Java 21, Spring Boot 3.4 |
| Database | PostgreSQL 16 + pgvector extension |
| ML | Tribuo 4.3 (Random Forest + XGBoost) |
| LLM (Dev) | Ollama with Llama 3.2 |
| LLM (Eval) | Claude Sonnet 4 (Anthropic API) |
| Embeddings | nomic-embed-text (768 dimensions) |
| Vector Store | pgvector with HNSW indexing |
| Frontend | Thymeleaf + Bootstrap 5 + Chart.js |
| Schema | Liquibase migrations |

---

## 4. Prerequisites & Setup

### Required Software:

1. **Java 21** (JDK) — for running the application
2. **Docker & Docker Compose** — for PostgreSQL + pgvector
3. **Ollama** — for local LLM inference (optional but needed for explanations)
4. **Maven** — for building (or use the Maven wrapper if present)

### Setup Steps:

#### Step 1: Start the database

```bash
cd finguard
docker-compose up -d
```

This starts PostgreSQL 16 with the pgvector extension. The database `finguard` is created automatically.

#### Step 2: Install Ollama models (if using LLM features)

```bash
# Install Ollama from https://ollama.ai
ollama pull llama3.2           # Chat model
ollama pull nomic-embed-text   # Embedding model
```

#### Step 3: Build and run the application

```bash
mvn clean package -DskipTests
java -jar target/finguard-0.0.1-SNAPSHOT.jar
```

Or with Maven:

```bash
mvn spring-boot:run
```

#### Step 4: Open the dashboard

Navigate to **http://localhost:8080/** in your browser.

---

## 5. Step-by-Step: Your First Complete Walkthrough

This section walks you through the entire system using the included test dataset. Follow every step in order.

### Step 1: Import test data

1. Open the browser at **http://localhost:8080/ingestion**
2. Under "Import from Server Path", enter: `classpath:data/test-transactions.csv`
3. Click **Import**
4. Expected result: **40 transactions imported**

Alternatively, upload the file located at `src/main/resources/data/test-transactions.csv` using the CSV upload form.

### Step 2: Compute features

1. Still on the Ingestion page, click **Compute Features**
2. Expected result: **40 features computed**
3. This calculates z-scores, velocity metrics, receiver diversity, etc. for every transaction

### Step 3: Seed the knowledge base

1. Navigate to **http://localhost:8080/knowledge**
2. Click **Seed Knowledge Base**
3. Expected result: **10 saved, 0 skipped, 10 vectorized**
4. These are 10 real-world fraud typologies (structuring, money mule, layering, etc.) now stored in the vector database for RAG

### Step 4: Run rule-based detection

1. Navigate to **http://localhost:8080/detection**
2. Select **RULES_ONLY** from the dropdown
3. Click **Run Detection**
4. Expected result: Several alerts created (see Section 10 for exact counts)
5. Click **View Alerts** to see the results

### Step 5: Train ML models

1. Navigate to **http://localhost:8080/models**
2. Click **Train Models**
3. This trains Random Forest and XGBoost classifiers on the labeled transaction data
4. Wait for the training to complete (should take a few seconds with 40 transactions)

### Step 6: Run full-system detection

1. Navigate to **http://localhost:8080/detection**
2. Select **FULL_SYSTEM** from the dropdown
3. Click **Run Detection**
4. This runs rules + ML + RAG-augmented LLM explanations on any remaining transactions without alerts
5. Alerts will now include ML risk scores and LLM-generated explanations

### Step 7: Review alerts and explanations

1. Navigate to **http://localhost:8080/alerts**
2. Click on any alert to see its full detail
3. You'll see: transaction details, rule triggers, ML risk scores, and (if LLM was used) the AI-generated explanation with CAKR quality scores
4. Use the status dropdown to mark alerts as REVIEWED, CONFIRMED_FRAUD, or FALSE_POSITIVE

### Step 8: View the dashboard

1. Navigate to **http://localhost:8080/** (the Dashboard)
2. You'll see: system overview stats, charts showing alert distributions, explanation quality metrics, and dataset breakdowns

### Step 9: Run an evaluation (optional)

1. Navigate to **http://localhost:8080/evaluation**
2. Enter a name like "baseline-test"
3. Select FULL_SYSTEM and PAYSIM
4. Click **Run Experiment**
5. See precision, recall, F1, and other metrics computed against the ground truth labels

---

## 6. Page-by-Page Guide

### Dashboard (/)

The main overview page. Shows:

- **Stat Cards**: Total transactions, alerts, fraud rate, explanations count
- **Charts**: Alert distribution by status (pie), alerts by detection config (bar), transaction types (pie)
- **Detection Analytics**: Total anomalies, average ML risk score, dataset breakdown table
- **Explanation Quality**: CAKR dimension averages (1-5 scale), hallucination rate, average latency
- **Experiments Table**: Results from evaluation runs with precision/recall/F1

### Transactions (/transactions)

Paginated table of all imported transactions. Features:

- **Filters**: By dataset source (PAYSIM, IBM_AML, CUSTOM) and fraud-only toggle
- **Columns**: ID, external ID, type, amount, sender, receiver, timestamp, source, fraud label
- **Pagination**: 25 per page, sorted by ID descending (newest first)

### Alerts (/alerts)

Paginated table of all detection alerts. Features:

- **Filters**: By alert status (NEW, REVIEWED, CONFIRMED_FRAUD, FALSE_POSITIVE) and detection config
- **Columns**: ID, transaction link, config, rule triggered, ML risk score (with visual bar), anomaly badge, status badge, creation time
- **Detail View**: Click the eye icon or transaction link to see full alert details

### Alert Detail (/alerts/{id})

Full detail view for a single alert:

- **Alert Info**: Status (with update form), detection config, rules triggered, ML risk score, model name, creation time
- **Transaction Info**: All transaction fields including ground truth fraud label
- **Explanations**: All LLM-generated explanations with risk summary, full explanation text, confidence, latency, hallucination status, and CAKR dimension scores (if evaluated)

### Explanations (/explanations)

Paginated table of all LLM-generated explanations:

- **Columns**: ID, linked alert, explanation type, risk summary preview, confidence, latency, hallucination status, CAKR average

### Data Ingestion (/ingestion)

Three actions:

1. **CSV Upload**: Select a PaySim-format CSV file from your computer. Max 500MB.
2. **Server Path**: Enter a filesystem path to a CSV already on the server.
3. **Compute Features**: Triggers feature engineering for all transactions that don't have features yet.

### Run Detection (/detection)

Execute the detection pipeline:

- **Config Selector**: Choose one of 5 detection configurations (see Section 7)
- **Current State**: Shows total transactions, alerts, and whether ML models are loaded
- **Config Guide**: Table explaining what each configuration does

### ML Models (/models)

Manage machine learning models:

- **Status**: Shows whether models are currently loaded
- **Train**: Trains new Random Forest + XGBoost classifiers on the current dataset
- **Load**: Loads previously trained models from disk

### Knowledge Base (/knowledge)

Manage the RAG fraud pattern library:

- **Seed**: Loads 10 fraud typologies into the database and vector store
- **Pattern Table**: Shows all loaded patterns with type, title, description, regulatory reference, and source
- **Idempotent**: Seeding again skips already-existing patterns

### Evaluation (/evaluation)

Run experiments and compare configurations:

- **Single Experiment**: Choose a config + dataset, optionally enable CAKR scoring
- **Compare All**: Runs all 5 configs side-by-side for comparison
- **Results Table**: Shows precision, recall, F1, FPR, CAKR scores, hallucination rate, latency

---

## 7. How Detection Works (In Detail)

### Five Detection Configurations

FinGuard supports 5 detection modes, selectable when running the pipeline:

#### 1. RULES_ONLY

Runs 4 rule-based checks. Creates an alert if **any** rule triggers.

**Rule 1 — LARGE_TRANSACTION**
- Triggers when: `amount >= $200,000`
- Example: A $250,000 transfer triggers this rule

**Rule 2 — RAPID_VELOCITY**
- Triggers when: The sender has **3 or more** transactions in the **last 1 hour**
- Requires computed features (txVelocity1h)
- Example: Account C1003 sends 3 cash-outs within the same hour

**Rule 3 — STRUCTURING**
- Triggers when ALL three conditions are met:
  1. Amount is a round number (divisible by $1,000)
  2. Amount is between $0 and $10,000 (just below reporting thresholds)
  3. Sender has 3+ transactions in the last 24 hours
- Example: Account C1002 sends $9,000 three times (round amounts, below $10K, in 24h)

**Rule 4 — NEW_RECEIVER_HIGH_VALUE**
- Triggers when BOTH conditions are met:
  1. The sender has **never** sent money to this receiver before
  2. Amount is >= $50,000
- Example: First-ever transfer of $75,000 to a new account

#### 2. ML_ONLY

Runs Tribuo ML classifiers (Random Forest + XGBoost ensemble). Creates an alert if the model predicts fraud (risk score >= 0.5). The alert stores:

- `mlRiskScore`: probability 0.0 to 1.0
- `mlModelName`: which model made the prediction
- `featureImportances`: JSON showing which features influenced the decision most

#### 3. ML_LLM_DIRECT

ML detection + LLM explanation **without** RAG context. If ML flags a transaction, an LLM generates a natural language explanation using only the transaction data and detection signals.

#### 4. ML_LLM_RAG

ML detection + LLM explanation **with** RAG context. The system:

1. Runs ML detection
2. If flagged, queries the vector store for the 5 most similar fraud patterns
3. Includes those patterns as context in the LLM prompt
4. The LLM generates an explanation grounded in real fraud typologies

This is the key innovation of the thesis — RAG-augmented explanations that reference real regulatory frameworks and fraud patterns.

#### 5. FULL_SYSTEM

The complete pipeline: Rules + ML + RAG-augmented LLM explanation. An alert is created if **either** rules trigger **or** ML predicts fraud. The explanation always uses RAG context.

### Feature Engineering Details

Before ML detection or some rules can work, features must be computed. Each transaction gets these engineered features:

| Feature | Description | Type |
|---------|------------|------|
| `amountZscore` | How many standard deviations the amount is from the sender's mean | Double |
| `txVelocity1h` | Number of sender's transactions in the last hour | Integer |
| `txVelocity24h` | Number of sender's transactions in the last 24 hours | Integer |
| `avgAmount7d` | Sender's average transaction amount over the last 7 days | BigDecimal |
| `amountRatioToAvg` | Current amount / 7-day average (how unusual is this amount?) | Double |
| `balanceChangeRatio` | Amount / sender's balance before (what % of balance was moved?) | Double |
| `receiverDiversity7d` | Number of distinct receivers in the last 7 days | Integer |
| `isNewReceiver` | Has this sender ever sent to this receiver before? | Boolean |
| `isRoundAmount` | Is the amount divisible by $1,000? | Boolean |
| `isHighRiskType` | Is this a TRANSFER or CASH_OUT? | Boolean |
| `hourOfDay` | Hour of transaction (0-23) | Short |
| `dayOfWeek` | Day of week (1=Monday, 7=Sunday) | Short |

---

## 8. How LLM Explanations Work

### The Explanation Pipeline

When an alert is created with an LLM-enabled config (ML_LLM_DIRECT, ML_LLM_RAG, or FULL_SYSTEM):

1. **Prompt Construction**: The system builds a structured prompt containing:
   - Transaction details (amount, type, sender, receiver, balances, timestamp)
   - Computed features (z-score, velocity, diversity, etc.)
   - Detection signals (which rules triggered, ML risk score, feature importances)
   - RAG context (if using RAG mode): top 5 most relevant fraud patterns from the knowledge base

2. **LLM Call**: The prompt is sent to the configured LLM (Ollama's Llama 3.2 by default, or Claude via API)

3. **Response Parsing**: The LLM responds with structured JSON containing:
   - `riskSummary`: A one-sentence summary of the risk
   - `explanationText`: A detailed 2-4 paragraph explanation
   - `suspiciousPatterns`: List of identified fraud patterns
   - `recommendedActions`: Suggested next steps for the analyst
   - `confidenceScore`: The LLM's self-assessed confidence (0.0-1.0)

4. **Hallucination Validation**: The system checks the explanation for:
   - Empty or missing fields
   - Confidence scores outside the valid range
   - Whether the transaction amount is referenced correctly
   - Possible fabricated account numbers
   - Risk direction mismatches (e.g., claiming low risk for a high-score alert)

5. **Persistence**: The explanation is saved to the database with metadata (token counts, latency, hallucination flags)

### RAG (Retrieval-Augmented Generation)

RAG improves explanation quality by giving the LLM real fraud pattern knowledge:

1. The system takes the alert's triggered rules and converts them to search terms
2. These terms are used to query the pgvector store for semantically similar fraud patterns
3. The top 5 matching patterns (with descriptions, indicators, and regulatory references) are included in the LLM prompt
4. The LLM can now reference real AML regulations and known fraud typologies

### CAKR Evaluation Framework

CAKR evaluates explanation quality across 4 dimensions (scored 1-5 each):

| Dimension | What It Measures | Score 5 Means |
|-----------|-----------------|---------------|
| **C**ompleteness | Does the explanation cover all relevant detection signals? | Addresses every rule trigger and ML signal |
| **A**ctionability | Are the recommended actions practical for a compliance analyst? | Clear, specific steps the analyst can follow |
| **K**nowledge | Is the explanation grounded in real fraud typologies? | References specific AML patterns and typologies |
| **R**egulatory | Does it reference relevant regulations? | Cites specific BSA/FinCEN/FATF requirements |

CAKR scoring uses a "LLM-as-judge" approach: a stronger model evaluates the output of the explanation model.

---

## 9. How Evaluation Works

### Detection Metrics

FinGuard compares detection results against ground truth labels in the dataset:

| Metric | Formula | What It Means |
|--------|---------|---------------|
| **Precision** | TP / (TP + FP) | Of all alerts raised, what % were actually fraud? |
| **Recall** | TP / (TP + FN) | Of all actual fraud, what % did we detect? |
| **F1 Score** | 2 * (P * R) / (P + R) | Harmonic mean of precision and recall |
| **False Positive Rate** | FP / (FP + TN) | Of all legitimate transactions, what % did we falsely flag? |

Where:
- **TP** (True Positive): Alert raised AND transaction was actually fraud
- **FP** (False Positive): Alert raised BUT transaction was legitimate
- **TN** (True Negative): No alert AND transaction was legitimate
- **FN** (False Negative): No alert BUT transaction was actually fraud

### Running Experiments

The Evaluation page lets you run experiments that:

1. Execute the detection pipeline on all transactions
2. Compare alerts against ground truth fraud labels
3. Compute precision, recall, F1, and FPR
4. Optionally score LLM explanations with CAKR
5. Record hallucination rates and latency statistics

### Comparing Configurations

The "Compare All" feature runs all 5 detection configs side-by-side, producing a table that shows how rule-based, ML, and hybrid approaches compare.

---

## 10. Test Data & Expected Results

### About the Test Dataset

The file `src/main/resources/data/test-transactions.csv` contains 40 hand-crafted transactions designed to trigger specific fraud patterns. Here's what's in it:

### Fraud Scenarios (isFraud = 1):

**Scenario 1 — Large Transfer (Row 1)**
- Account C1001 transfers $250,000 (exceeds $200K threshold)
- Expected: LARGE_TRANSACTION rule triggers

**Scenario 2 — Structuring (Rows 2-4)**
- Account C1002 makes three $9,000 transfers in consecutive hours
- $9,000 is round, below $10K, and 3+ transactions in 24h
- Expected: STRUCTURING rule triggers

**Scenario 3 — Rapid Cash-Out (Rows 5-7)**
- Account C1003 makes three CASH_OUT transactions in the same hour (step 5)
- Expected: RAPID_VELOCITY rule triggers

**Scenario 4 — Large Cash-Out (Row 12)**
- Account C1008 cashes out $350,000
- Expected: LARGE_TRANSACTION rule triggers

**Scenario 5 — New Receiver + High Value (Row 13)**
- Account C1009 transfers $100,000 to a new receiver
- Expected: NEW_RECEIVER_HIGH_VALUE rule triggers

**Scenario 6 — Structuring with Multiple Receivers (Rows 14-17)**
- Account C1010 sends $8,000 to four different new receivers in consecutive hours
- Round amounts, near structuring thresholds, multiple receivers

**Scenario 7 — Large Transfers Draining Account (Rows 25-26)**
- Account C1017 transfers $500,000 then $400,000, draining the account completely
- Expected: LARGE_TRANSACTION rule triggers on both

**Scenario 8 — High-Value Cash-Out (Row 27)**
- Account C1021 cashes out $220,000
- Expected: LARGE_TRANSACTION rule triggers

### Legitimate Transactions (isFraud = 0):

Rows 8-11, 18-21, 23-24, 29-30, 32-40 are legitimate transactions of various types and sizes to provide negative examples for ML training.

### Expected Rule-Based Alert Counts:

When running RULES_ONLY detection on this dataset:

- **LARGE_TRANSACTION**: Should flag rows with amounts >= $200,000 (rows 1, 12, 25, 26, 27 = approximately 5 alerts)
- **STRUCTURING**: Should flag rows matching all three conditions (depends on feature computation timing)
- **RAPID_VELOCITY**: Should flag accounts with 3+ hourly transactions
- **NEW_RECEIVER_HIGH_VALUE**: Should flag high-value transfers to new receivers

Note: Exact alert counts depend on processing order and feature state. The important thing is that you see multiple alerts with clear rule triggers.

---

## 11. API Reference

All REST endpoints are available at `http://localhost:8080/api/v1/`. Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

### Ingestion

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ingestion/paysim/upload` | Upload PaySim CSV (multipart) |
| POST | `/api/v1/ingestion/paysim/path` | Import from server path |
| POST | `/api/v1/ingestion/features/compute` | Compute features for all transactions |

### Detection

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/detection/run?config=RULES_ONLY` | Run detection pipeline |
| GET | `/api/v1/detection/alerts?page=0&size=20` | List alerts (paginated) |
| GET | `/api/v1/detection/alerts/{id}` | Get single alert |
| PATCH | `/api/v1/detection/alerts/{id}/status?status=REVIEWED` | Update alert status |

### Explanations

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/explanations/generate/direct/{alertId}` | Generate direct LLM explanation |
| POST | `/api/v1/explanations/generate/rag/{alertId}` | Generate RAG explanation |
| POST | `/api/v1/explanations/generate/batch?type=LLM_RAG&limit=10` | Batch generate |
| GET | `/api/v1/explanations/alert/{alertId}` | Get explanations for alert |
| GET | `/api/v1/explanations/{id}` | Get single explanation |
| POST | `/api/v1/explanations/{id}/validate` | Re-run hallucination check |

### Models

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/models/train` | Train RF + XGBoost |
| GET | `/api/v1/models/status` | Check if models are loaded |
| POST | `/api/v1/models/load` | Load models from disk |

### Knowledge Base

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/knowledge/seed` | Seed fraud patterns |
| GET | `/api/v1/knowledge/patterns` | List all patterns |
| GET | `/api/v1/knowledge/patterns/{type}` | Get patterns by type |
| GET | `/api/v1/knowledge/patterns/count` | Pattern count |

### Evaluation

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/evaluation/run` | Run single experiment |
| POST | `/api/v1/evaluation/compare` | Compare all 5 configs |
| GET | `/api/v1/evaluation/results` | All experiment results |
| GET | `/api/v1/evaluation/results/{name}` | Results by experiment name |

### Dashboard

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/dashboard/overview` | System overview |
| GET | `/api/v1/dashboard/detection` | Detection analytics |
| GET | `/api/v1/dashboard/explanation-quality` | Explanation quality |
| GET | `/api/v1/dashboard/datasets` | Dataset breakdown |
| GET | `/api/v1/dashboard/full` | All sections combined |

---

## 12. Troubleshooting

### "No models loaded" when trying ML detection

**Cause**: ML models haven't been trained yet.
**Fix**: Go to /models and click "Train Models" first. You need at least some labeled transactions imported.

### "0 alerts created" after running detection

**Cause**: Either all transactions already have alerts, or features haven't been computed.
**Fix**:
1. Make sure you've imported transactions (check /transactions page)
2. Make sure you've computed features (go to /ingestion and click "Compute Features")
3. Run detection again

### Explanations are empty or missing

**Cause**: Ollama is not running or the model isn't pulled.
**Fix**:
1. Ensure Ollama is running: `ollama serve`
2. Pull the model: `ollama pull llama3.2`
3. Pull embeddings: `ollama pull nomic-embed-text`

### Database connection refused

**Cause**: PostgreSQL container isn't running.
**Fix**: Run `docker-compose up -d` from the finguard directory.

### "Features not found" errors

**Cause**: Feature engineering hasn't been run for the transaction.
**Fix**: Go to /ingestion and click "Compute Features". This computes features for ALL transactions that don't have them yet.

### Knowledge base seeding shows "0 vectorized"

**Cause**: The embedding model isn't available or Ollama isn't running.
**Fix**: Ensure Ollama is running and `nomic-embed-text` is pulled. The patterns are still saved to the database but won't be available for RAG retrieval until vectorized.

### Swagger UI not loading

**URL**: http://localhost:8080/swagger-ui.html
**Fix**: Make sure the application is running and try clearing browser cache.

---

## PaySim CSV Format Reference

If you want to create your own test data, here is the exact CSV format expected:

```
step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud
```

| Column | Type | Description | Valid Values |
|--------|------|-------------|-------------|
| step | int | Hours since simulation start | 0-744 |
| type | string | Transaction type | TRANSFER, PAYMENT, CASH_OUT, CASH_IN, DEBIT |
| amount | decimal | Transaction amount | >= 0 |
| nameOrig | string | Sender account ID | Non-blank |
| oldbalanceOrg | decimal | Sender balance before | Any |
| newbalanceOrig | decimal | Sender balance after | Any |
| nameDest | string | Receiver account ID | Non-blank |
| oldbalanceDest | decimal | Receiver balance before | Any |
| newbalanceDest | decimal | Receiver balance after | Any |
| isFraud | int | Ground truth fraud label | 0 or 1 |
| isFlaggedFraud | int | System flag (informational) | 0 or 1 |

**Tips for creating test data**:
- Use account IDs like C1001 (sender) and C2001 (receiver) for clarity
- Set `step` values close together (e.g., 1, 2, 3) to trigger velocity rules
- Use round amounts below $10,000 with high velocity to trigger structuring
- Use amounts above $200,000 to trigger large transaction rules
- Send to new receivers with amounts above $50,000 to trigger new receiver rules
- Set `isFraud=1` on transactions you consider fraudulent for evaluation purposes
