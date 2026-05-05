package dev.finguard.presentation.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.repository.FraudPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the KnowledgeBaseController REST API.
 *
 * <p>Tests fraud pattern seeding, retrieval, and count endpoints
 * against a real PostgreSQL database. The VectorStore is mocked
 * via MockAiConfig since we don't need actual embedding in tests.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("KnowledgeBaseController (REST API integration)")
class KnowledgeBaseControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private FraudPatternRepository fraudPatternRepository;
    @Autowired private dev.finguard.testutil.AsyncITCleaner cleaner;

    @BeforeEach
    void cleanUp() {
        cleaner.drainAndClean();
    }

    // ==============================================================
    // Seeding
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/knowledge/seed")
    class SeedEndpoint {

        @Test
        @DisplayName("seeds all 10 fraud patterns from default JSON file")
        void seedsAllPatterns() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/seed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(10))
                    .andExpect(jsonPath("$.skipped").value(0))
                    .andExpect(jsonPath("$.vectorized").value(10))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.error").isEmpty());
        }

        @Test
        @DisplayName("is idempotent — second call skips all existing patterns")
        void idempotentSeeding() throws Exception {
            // First seed
            mockMvc.perform(post("/api/v1/knowledge/seed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(10));

            // Second seed — all should be skipped
            mockMvc.perform(post("/api/v1/knowledge/seed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(0))
                    .andExpect(jsonPath("$.skipped").value(10))
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("partial seeding — only seeds new patterns")
        void partialSeeding() throws Exception {
            // Pre-seed one pattern manually
            FraudPattern existing = new FraudPattern();
            existing.setPatternType("STRUCTURING");
            existing.setTitle("Transaction Structuring (Smurfing)");
            existing.setDescription("Pre-existing pattern");
            fraudPatternRepository.save(existing);

            mockMvc.perform(post("/api/v1/knowledge/seed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.saved").value(9))
                    .andExpect(jsonPath("$.skipped").value(1))
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ==============================================================
    // Retrieval
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/knowledge/patterns")
    class GetAllPatterns {

        @Test
        @DisplayName("returns empty list when no patterns seeded")
        void emptyWhenNoPatterns() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/patterns"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("returns all seeded patterns with correct fields")
        void returnsAllAfterSeed() throws Exception {
            // Seed first
            mockMvc.perform(post("/api/v1/knowledge/seed"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/knowledge/patterns"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(10)))
                    .andExpect(jsonPath("$[0].id").isNumber())
                    .andExpect(jsonPath("$[0].patternType").isString())
                    .andExpect(jsonPath("$[0].title").isString())
                    .andExpect(jsonPath("$[0].description").isString());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/knowledge/patterns/{patternType}")
    class GetPatternsByType {

        @Test
        @DisplayName("returns patterns matching the given type")
        void filtersByType() throws Exception {
            persistPattern("STRUCTURING", "Smurfing Pattern");
            persistPattern("STRUCTURING", "Smurfing Variant");
            persistPattern("LAYERING", "Layer Pattern");

            mockMvc.perform(get("/api/v1/knowledge/patterns/STRUCTURING"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].patternType").value("STRUCTURING"))
                    .andExpect(jsonPath("$[1].patternType").value("STRUCTURING"));
        }

        @Test
        @DisplayName("returns empty list for non-existent type")
        void emptyForUnknownType() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/patterns/NON_EXISTENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ==============================================================
    // Count
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/knowledge/patterns/count")
    class PatternCount {

        @Test
        @DisplayName("returns zero when no patterns exist")
        void zeroCount() throws Exception {
            mockMvc.perform(get("/api/v1/knowledge/patterns/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(0));
        }

        @Test
        @DisplayName("returns correct count after seeding")
        void correctCountAfterSeed() throws Exception {
            mockMvc.perform(post("/api/v1/knowledge/seed"));

            mockMvc.perform(get("/api/v1/knowledge/patterns/count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(10));
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private void persistPattern(String patternType, String title) {
        FraudPattern fp = new FraudPattern();
        fp.setPatternType(patternType);
        fp.setTitle(title);
        fp.setDescription("Test description for " + title);
        fp.setRegulatoryReference("BSA");
        fp.setSource("Integration Test");
        fraudPatternRepository.save(fp);
    }
}
