# FinGuard — LLM-Based Transaction Anomaly Detection & Explanation

> **Master's Thesis Project** · Nika Avalishvili  
> Stack: Java 25 · Spring Boot 3.5 · Spring AI · Tribuo · PostgreSQL + pgvector · Ollama · Thymeleaf + HTMX

FinGuard is a research platform that combines rule-based detection, machine learning (Random Forest + XGBoost), and large language models with Retrieval-Augmented Generation (RAG) to detect and explain financial fraud. Five experimental configurations are evaluated using precision, recall, F1, AUC-ROC, and the custom **CAKR** explanation quality metric (Completeness, Accuracy, Actionability, Regulatory compliance).

---

## The 5 Experimental Configurations

| # | Name | Description |
|---|------|-------------|
| 1 | **Rules Only** | Threshold-based rules: large transaction, velocity, structuring, new-receiver. Fully deterministic, no ML. |
| 2 | **ML Only** | Random Forest + XGBoost ensemble with SHAP feature importance. No LLM explanations. |
| 3 | **ML + LLM (Direct)** | ML detection + LLM explanations via direct prompting (no retrieval context). |
| 4 | **ML + LLM + RAG** | ML detection + RAG-augmented LLM explanations using a pgvector fraud pattern knowledge base. |
| 5 | **Full System** | Rules + ML + RAG + hallucination mitigation. Evaluated on all CAKR dimensions. |

---

## Architecture

```
Presentation (controllers, templates)
    → Application (services, use cases)
        → Domain (entities, repositories, enums)
            ← Infrastructure (JPA, Spring AI, CSV parsers)
```

The detection pipeline flows: **Rules → ML scoring → LLM explanation → Hallucination validation**

LLM provider is swappable via Spring profile:
- **Default (dev):** Ollama with `llama3.2` running locally in Docker
- **Evaluation:** Anthropic Claude via `cloud-llm` profile

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java | 25+ | Required |
| Maven | 3.9+ | Or use `./mvnw` wrapper |
| Docker + Docker Compose | 24+ | For PostgreSQL + pgvector + Ollama |
| PaySim CSV | — | [Generate or download](https://github.com/EdgarLopezPhD/PaySim) |

---

## Quick Start

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL 16 + pgvector** on port `5432`
- **Ollama** on port `11434`

### 2. Pull LLM Models (first time only)

```bash
docker exec -it finguard-ollama ollama pull llama3.2
docker exec -it finguard-ollama ollama pull nomic-embed-text
```

`llama3.2` handles chat/explanation generation. `nomic-embed-text` (768-dim) handles RAG embeddings.

### 3. Run the Application

```bash
./mvnw spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080). Liquibase runs migrations automatically on startup.

### 4. Import Data

Navigate to **Data Ingestion** (`/ingestion`) and either:
- **Upload** a PaySim CSV file directly (up to 500 MB)
- **Import from server path** — provide an absolute path to a file already on disk (recommended for large files, e.g. the full 6M-row PaySim dataset)

The import runs asynchronously; a progress bar shows live status.

### 5. Train ML Models

Navigate to **ML Models** (`/models`) and click **Train Models**. This trains Random Forest and XGBoost on the ingested transaction features.

### 6. Seed Knowledge Base

Navigate to **Knowledge Base** (`/knowledge`) and click **Seed Patterns**. This loads the RAG fraud pattern library into pgvector.

### 7. Run Detection

Navigate to **Run Detection** (`/detection`), select a configuration, and click **Run Detection**. Alerts are created for every flagged transaction.

### 8. Evaluate

Navigate to **Evaluation** (`/evaluation`) to run experiments comparing configurations on precision, recall, F1, AUC-ROC, and CAKR explanation quality.

---

## Running with Claude API (Evaluation Mode)

To use Anthropic Claude instead of local Ollama:

```bash
# Uncomment spring-ai-starter-model-anthropic in pom.xml first, then:
ANTHROPIC_API_KEY=sk-ant-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=cloud-llm
```

---

## Project Structure

```
src/main/java/dev/finguard/
├── domain/             # Entities, repository interfaces, enums — no Spring deps
│   ├── model/          # Transaction, Alert, Explanation, FraudPattern, ...
│   ├── repository/     # Spring Data JPA interfaces
│   └── enums/          # DetectionConfig, AlertStatus, ExplanationType, ...
├── application/        # Orchestration services
├── ingestion/          # CSV import, feature engineering, async job tracking
├── detection/          # Rule engine, ML inference, detection pipeline
│   ├── rule/           # RuleCheck strategy implementations
│   ├── ml/             # TribuoModelService, FeatureTransformer
│   └── service/        # DetectionPipelineService, AlertPersistenceService
├── explanation/        # LLM explanations, RAG, prompt building, hallucination validation
│   ├── llm/            # LLMExplanationService
│   ├── rag/            # RAGContextService, FraudPatternSeeder
│   ├── prompt/         # PromptBuilder
│   └── validation/     # HallucinationValidator
├── evaluation/         # Experiment runner, CAKR scorer, detection metrics
├── dashboard/          # Thymeleaf controllers, DashboardService (Caffeine cache)
├── presentation/       # REST controllers, DTOs
└── config/             # Async, Cache, Retry, Metrics, CORS, GlobalExceptionHandler
```

---

## API Reference

The REST API is documented at [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

Key endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/ingestion/paysim/upload` | Import PaySim CSV via file upload (async) |
| `POST` | `/api/v1/ingestion/paysim/path` | Import PaySim CSV from server path (async) |
| `GET`  | `/api/v1/ingestion/status/{jobId}` | Poll import job status |
| `POST` | `/api/v1/detection/run?config=FULL_SYSTEM` | Run detection pipeline |
| `GET`  | `/api/v1/alerts` | List alerts (paginated) |
| `POST` | `/api/v1/models/train` | Train ML models |
| `POST` | `/api/v1/evaluation/run` | Run evaluation experiment |

---

## Running Tests

```bash
# Unit tests only (no Docker required)
./mvnw test

# Integration tests (requires docker-compose up -d)
./mvnw verify

# Single test class
./mvnw test -Dtest=IngestionServiceTest
./mvnw verify -Dit.test=DetectionPipelineIT -DskipTests=false
```

Integration tests connect to the `finguard_test` database on the running PostgreSQL container (not Testcontainers, not H2 — pgvector requires a real PostgreSQL instance).

---

## Configuration

Key settings in `src/main/resources/application.yml`:

```yaml
finguard:
  detection:
    rules:
      large-transaction-threshold: 200000
      rapid-succession-window-hours: 1
      rapid-succession-count: 3
      structuring-threshold: 9500
      new-receiver-high-value-threshold: 50000

spring:
  ai:
    ollama:
      chat:
        model: llama3.2
      embedding:
        model: nomic-embed-text
```

---

## CAKR Metric

CAKR is a custom explanation quality rubric scored 1–5 per dimension:

| Dimension | Description |
|-----------|-------------|
| **C**ompleteness | Does the explanation cover all relevant risk factors? |
| **A**ccuracy | Is the explanation factually consistent with the transaction data? |
| **K**nowledge / Actionability | Does it provide actionable guidance for the analyst? |
| **R**egulatory | Does it reference relevant compliance frameworks (AML, KYC, FATF)? |

Scoring is performed by Claude (`claude-sonnet-4-20250514`) using the `cloud-llm` profile.

---

## License

This project is developed for academic research purposes as part of a master's thesis.