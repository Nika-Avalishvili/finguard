package dev.finguard.explanation.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.repository.FraudPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the fraud pattern knowledge base from a JSON file.
 *
 * <p>Performs two operations:</p>
 * <ol>
 *   <li>Saves {@link FraudPattern} entities to the database (metadata, searchable by type)</li>
 *   <li>Adds documents to the {@link VectorStore} (embedded via nomic-embed-text for
 *       semantic similarity search in the RAG pipeline)</li>
 * </ol>
 *
 * <p>Each fraud pattern is converted into a rich text document that combines the title,
 * description, indicators, regulatory references, and example scenario. This composite
 * text is what gets embedded and used for similarity matching.</p>
 *
 * <p>The seeder is idempotent — it skips patterns that already exist in the database
 * (matched by title).</p>
 */
@Service
public class FraudPatternSeeder {

    private static final Logger log = LoggerFactory.getLogger(FraudPatternSeeder.class);
    private static final String DEFAULT_SEED_FILE = "data/fraud-patterns.json";

    private final FraudPatternRepository fraudPatternRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public FraudPatternSeeder(FraudPatternRepository fraudPatternRepository,
                               VectorStore vectorStore,
                               ObjectMapper objectMapper,
                               JdbcTemplate jdbcTemplate) {
        this.fraudPatternRepository = fraudPatternRepository;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Seed fraud patterns from the default classpath resource.
     *
     * @return result summary
     */
    public SeedResult seed() {
        return seed(DEFAULT_SEED_FILE);
    }

    /**
     * Seed fraud patterns from a specified classpath resource.
     *
     * @param resourcePath path to the JSON seed file on the classpath
     * @return result summary
     */
    public SeedResult seed(String resourcePath) {
        log.info("Starting fraud pattern seeding from: {}", resourcePath);

        List<FraudPatternDto> patterns;
        try {
            patterns = loadPatterns(resourcePath);
        } catch (IOException e) {
            log.error("Failed to load fraud patterns from {}: {}", resourcePath, e.getMessage());
            return new SeedResult(0, 0, 0, "Failed to load seed file: " + e.getMessage());
        }

        int saved = 0;
        int skipped = 0;
        List<Document> documents = new ArrayList<>();

        for (FraudPatternDto dto : patterns) {
            // Check if pattern already exists in fraud_patterns table
            FraudPattern existing = fraudPatternRepository.findByPatternType(dto.patternType())
                    .stream()
                    .filter(p -> p.getTitle().equals(dto.title()))
                    .findFirst()
                    .orElse(null);

            FraudPattern entity;
            if (existing != null) {
                log.debug("Pattern already in DB: {}", dto.title());
                skipped++;
                entity = existing;
            } else {
                entity = new FraudPattern();
                entity.setPatternType(dto.patternType());
                entity.setTitle(dto.title());
                entity.setDescription(dto.description());
                entity.setRegulatoryReference(dto.regulatoryReference());
                entity.setExampleScenario(dto.exampleScenario());
                entity.setSource(dto.source());

                try {
                    entity.setIndicators(objectMapper.writeValueAsString(dto.indicators()));
                } catch (Exception e) {
                    log.warn("Failed to serialize indicators for {}: {}", dto.title(), e.getMessage());
                }

                fraudPatternRepository.save(entity);
                saved++;
            }

            // Always include in documents — deterministic ID enables upsert in vector store.
            // This ensures the vector store is populated even if patterns were pre-seeded in a
            // previous run when the vector_store table didn't exist yet.
            documents.add(buildDocument(entity, dto));
        }

        // Upsert all documents into the vector store.
        // Spring AI PgVectorStore uses INSERT ... ON CONFLICT DO UPDATE, so re-adding the same
        // deterministic UUID is safe and idempotent.
        int vectorized = 0;
        if (!documents.isEmpty()) {
            try {
                vectorStore.add(documents);
                vectorized = documents.size();
                log.info("Upserted {} documents into vector store", vectorized);
            } catch (Exception e) {
                log.error("Failed to add documents to vector store: {}", e.getMessage());
            }
        }

        SeedResult result = new SeedResult(saved, skipped, vectorized, null);
        log.info("Fraud pattern seeding complete: {} saved, {} skipped, {} vectorized",
                saved, skipped, vectorized);
        return result;
    }

    /**
     * Build a rich text document for the vector store.
     *
     * <p>Combines all pattern fields into a single text that captures the
     * full semantic meaning for embedding. Metadata is stored alongside
     * for filtering and display.</p>
     */
    Document buildDocument(FraudPattern entity, FraudPatternDto dto) {
        StringBuilder text = new StringBuilder();
        text.append("Fraud Pattern: ").append(dto.title()).append("\n\n");
        text.append("Type: ").append(dto.patternType()).append("\n\n");
        text.append("Description: ").append(dto.description()).append("\n\n");

        if (dto.indicators() != null && !dto.indicators().isEmpty()) {
            text.append("Key Indicators:\n");
            for (String indicator : dto.indicators()) {
                text.append("- ").append(indicator).append("\n");
            }
            text.append("\n");
        }

        if (dto.regulatoryReference() != null) {
            text.append("Regulatory References: ").append(dto.regulatoryReference()).append("\n\n");
        }

        if (dto.exampleScenario() != null) {
            text.append("Example Scenario: ").append(dto.exampleScenario()).append("\n");
        }

        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("patternType", dto.patternType());
        metadata.put("title", dto.title());
        metadata.put("source", dto.source() != null ? dto.source() : "unknown");
        if (entity.getId() != null) {
            metadata.put("fraudPatternId", entity.getId());
        }

        // Deterministic UUID from the DB primary key (or title as fallback) — enables idempotent
        // upserts in PgVectorStore so re-seeding doesn't create duplicate documents.
        String idSource = entity.getId() != null
                ? "fraud-pattern:" + entity.getId()
                : "fraud-pattern:" + entity.getTitle();
        UUID deterministicId = UUID.nameUUIDFromBytes(idSource.getBytes(StandardCharsets.UTF_8));

        return new Document(deterministicId.toString(), text.toString(), metadata);
    }

    /**
     * Load fraud pattern DTOs from a classpath JSON resource.
     */
    List<FraudPatternDto> loadPatterns(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<>() {});
        }
    }

    /**
     * Result of a seeding operation.
     *
     * @param saved      number of new patterns saved to the database
     * @param skipped    number of patterns skipped (already existed)
     * @param vectorized number of documents added to the vector store
     * @param error      error message if the operation failed, null otherwise
     */
    public record SeedResult(int saved, int skipped, int vectorized, String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * DTO for deserializing fraud patterns from the seed JSON file.
     */
    record FraudPatternDto(
            String patternType,
            String title,
            String description,
            List<String> indicators,
            String regulatoryReference,
            String exampleScenario,
            String source
    ) {}
}
