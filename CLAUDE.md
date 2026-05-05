# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# FinGuard — Claude Code Instructions

## Project Overview

FinGuard is a master's thesis application demonstrating **LLM-Based Transaction Anomaly Detection and Explanation in Fintech**. It combines rule-based detection, ML (Tribuo Random Forest + XGBoost), and LLM explanation generation (Spring AI + Ollama locally, Claude API for evaluation) with a RAG pipeline using pgvector.

**Author:** Nika Avalishvili
**Stack:** Java 25 · Spring Boot 3.5.3 · Maven · PostgreSQL + pgvector · Liquibase · Spring AI · Tribuo · Thymeleaf + HTMX + Chart.js

---

## Skills — Read Before Every Task

Skills are located at `../skills/` (one level above this project). **Always read the relevant skill file before writing any code.** They contain deep domain knowledge built specifically for this project.

| Task | Skill file to read first |
|------|--------------------------|
| Any Java / Spring Boot code | `../skills/finguard-dev/SKILL.md` |
| Any Thymeleaf template, HTMX interaction, or Chart.js chart | `../skills/finguard-ui/SKILL.md` |
| Any test — unit, integration, or MockMvc | `../skills/finguard-qa/SKILL.md` |
| ML model training, feature engineering, evaluation metrics, CAKR | `../skills/ml-evaluator/SKILL.md` |

**Reading the skill is not optional.** It prevents common mistakes with architecture, class imbalance, HTMX-Chart.js interaction, Testcontainers setup, and more.

---

## Architecture

Clean Architecture — dependency direction is strictly **inward only**:

```
Presentation (controllers, templates)
    → Application (services, use cases)
        → Domain (entities, repositories interfaces, enums)
            ← Infrastructure (JPA impl, AI clients, CSV parsers)
```

- `dev.finguard.domain` — entities, repository interfaces, enums. **No Spring annotations except `@Entity`, `@Repository` interfaces.**
- `dev.finguard.application` — services orchestrating use cases. No HTTP, no JPA.
- `dev.finguard.infrastructure` — JPA implementations, Spring AI, CSV import, RAG.
- `dev.finguard.presentation` — REST controllers, Thymeleaf controllers, DTOs.

**Never inject infrastructure classes directly into controllers.** Go through application services.

---

## Key Design Patterns

- **Strategy** — `RuleCheck` interface with multiple implementations (`LargeTransactionRule`, `VelocityRule`, etc.)
- **Chain of Responsibility** — detection pipeline stages (Rules → ML → Explanation → Validation)
- **Builder** — LLM prompt construction
- **Repository** — all DB access through Spring Data JPA interfaces in `domain.repository`

---

## Domain Model

| Entity | Table | Notes |
|--------|-------|-------|
| `Transaction` | `transactions` | Core entity, PaySim-compatible fields |
| `TransactionFeatures` | `transaction_features` | Computed ML features, 1:1 with Transaction |
| `Alert` | `alerts` | Detection result per transaction per config |
| `Explanation` | `explanations` | LLM output with CAKR scores, linked to Alert |
| `FraudPattern` | `fraud_patterns` | RAG knowledge base entries |
| `ExperimentResult` | `experiment_results` | CV fold metrics per config |

---

## The 5 Experimental Configurations

Always reference them in this exact order:

1. **RULES_ONLY** — Rule-based thresholds, template alert text
2. **ML_ONLY** — Random Forest + XGBoost, SHAP feature importance
3. **ML_LLM_DIRECT** — ML + LLM direct prompting (no RAG)
4. **ML_LLM_RAG** — ML + LLM + pgvector RAG pipeline
5. **FULL_SYSTEM** — ML + LLM + RAG + hallucination mitigation

---

## Database & Migrations

- **Liquibase** manages all schema changes. Never use `ddl-auto: create` or `update` in production profile.
- Changelog files: `src/main/resources/db/changelog/` — numbered `001` through `007` (next is `008`).
- Master file: `db.changelog-master.yml`
- Author on all changesets: `nika.avalishvili`
- When adding a new table or column, always create a new numbered changelog file.

---

## LLM Configuration

- **Default (local):** Ollama with `llama3.2` (chat) and `nomic-embed-text` (embeddings) via Docker
- **Evaluation:** Activate `cloud-llm` Spring profile to switch to Anthropic Claude (`claude-sonnet-4-20250514`)
- **Abstraction:** Always use `ChatClient` from Spring AI — never call Ollama or Anthropic SDKs directly. This keeps the same code working for both providers.
- pgvector dimensions: **768** (nomic-embed-text), similarity: `COSINE_DISTANCE`, index: `HNSW`

---

## Code Conventions

- **Constructor injection** everywhere. Never `@Autowired` on fields.
- **No `@Transactional` in controllers.** Service layer only.
- **DTOs at the boundary.** Never expose JPA entities in REST responses.
- **`Optional<T>` for nullable returns** from repositories and services.
- **`BigDecimal` for all monetary amounts.** Never `double` or `float`.
- **`@Column(name = "...")` required** for any field whose name contains numbers (JPA naming strategy doesn't handle them well).
- Logging: SLF4J `log.info/warn/error` — never `System.out.println`.
- Exception handling: `@ControllerAdvice` + `@ExceptionHandler`, not try/catch in controllers.

---

## Commands

```bash
# Start infrastructure (PostgreSQL + Ollama) — required before running app or ITs
docker-compose up -d

# Pull LLM models (first time only)
docker exec -it finguard-ollama ollama pull llama3.2
docker exec -it finguard-ollama ollama pull nomic-embed-text

# Run application (local Ollama profile)
./mvnw spring-boot:run

# Run with Claude API instead of Ollama (requires ANTHROPIC_API_KEY env var)
# NOTE: spring-ai-starter-model-anthropic dependency is commented out in pom.xml — uncomment first
ANTHROPIC_API_KEY=sk-... ./mvnw spring-boot:run -Dspring-boot.run.profiles=cloud-llm

# Build (skip tests)
./mvnw package -DskipTests

# Unit tests only (classes ending in *Test)
./mvnw test

# Integration tests + unit tests (classes ending in *IT require docker-compose up)
./mvnw verify

# Run a single unit test class
./mvnw test -Dtest=IngestionServiceTest

# Run a single integration test class
./mvnw verify -Dit.test=DetectionPipelineIT -DskipTests=false
```

Application runs on `http://localhost:8080`
Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## Package Structure

Beyond the clean-architecture layers, the codebase is organized by feature:

- `dev.finguard.ingestion` — CSV import (PaySimLoader, IngestionService, FeatureEngineeringService, async job tracking)
- `dev.finguard.detection` — pipeline: `rule/` (RuleCheck strategy implementations), `ml/` (TribuoModelService, FeatureTransformer), `service/` (DetectionPipelineService, AlertPersistenceService)
- `dev.finguard.explanation` — `llm/` (LLMExplanationService), `rag/` (RAGContextService, FraudPatternSeeder), `prompt/` (PromptBuilder), `validation/` (HallucinationValidator)
- `dev.finguard.evaluation` — `metrics/` (DetectionMetrics, CAKRScorer), `runner/` (ExperimentRunner)
- `dev.finguard.dashboard` — Thymeleaf controllers and DashboardService (Spring Cache with Caffeine)
- `dev.finguard.config` — cross-cutting: AsyncConfig, CacheConfig, RetryConfig, MetricsConfig, GlobalExceptionHandler, CorrelationIdFilter

---

## Testing

- **Unit tests** (`*Test`): no Spring context, Mockito + AssertJ.
- **Integration tests** (`*IT`): require `docker-compose up -d`. Tests connect to the `finguard_test` database on the running PostgreSQL container — **not Testcontainers, not H2**.
- **Mocking LLM**: use `MockAiConfig` (`src/test/java/dev/finguard/config/MockAiConfig.java`) to stub Spring AI `ChatClient` without Ollama running.
- **Test data builder**: `TestTransactionBuilder` in `src/test/java/dev/finguard/testutil/` — use this instead of mocking `Transaction`.
- Naming: `methodName_shouldExpectedBehavior_whenCondition()`
- Every test must have at least one `assertThat(...)` or `verify(...)`.
- Always run `./mvnw verify` after any code changes to confirm all tests pass
- When changing repository method signatures, immediately check and update all test mocks that reference the old signature
- When fixing one test, run the full suite — fixes often break other tests via mock/cache interactions
- Use `@MockitoBean` (not `@MockBean`) for Spring Boot 3.4+

---

## Build & Run

- Build: `./mvnw clean verify`
- Language: Java 25, Spring Boot 3.5.3
- Database: PostgreSQL (must be running before tests)

---

## Debugging Approach

- When fixing a specific failing test, focus directly on that test first — do not investigate tangential issues (metrics, Prometheus, etc.) until the original issue is resolved
- When a test returns an unexpected HTTP status, check exception handlers and validation interceptors before exploring caching or unrelated subsystems

---
## What NOT to Do

- Do not read entire 30+ page papers end to end — use page ranges
- Do not use `float`/`double` for money
- Do not put business logic in controllers or entities
- Do not skip Liquibase — never alter the DB schema directly
- Do not call `entityManager.flush()` without `entityManager.clear()` in batch processing
- Do not mock value objects (Transaction, DTOs) — create real instances
- Do not use H2 for integration tests — it doesn't support pgvector
