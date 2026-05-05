package dev.finguard.explanation.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.explanation.rag.FraudPatternSeeder.SeedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FraudPatternSeeder")
class FraudPatternSeederTest {

    @Mock
    private FraudPatternRepository fraudPatternRepository;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private FraudPatternSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new FraudPatternSeeder(fraudPatternRepository, vectorStore, new ObjectMapper(), jdbcTemplate);
    }

    @Nested
    @DisplayName("Loading and seeding")
    class LoadingAndSeeding {

        @Test
        @DisplayName("loads default seed file and saves all patterns")
        void loadsDefaultSeedFile() {
            when(fraudPatternRepository.findByPatternType(anyString())).thenReturn(List.of());
            when(fraudPatternRepository.save(any(FraudPattern.class))).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });

            SeedResult result = seeder.seed();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.saved()).isEqualTo(10);
            assertThat(result.skipped()).isEqualTo(0);
            assertThat(result.error()).isNull();
            verify(fraudPatternRepository, times(10)).save(any(FraudPattern.class));
        }

        @Test
        @DisplayName("adds documents to vector store in batch")
        @SuppressWarnings("unchecked")
        void addsDocumentsToVectorStore() {
            when(fraudPatternRepository.findByPatternType(anyString())).thenReturn(List.of());
            when(fraudPatternRepository.save(any(FraudPattern.class))).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });

            SeedResult result = seeder.seed();

            assertThat(result.vectorized()).isEqualTo(10);
            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(captor.capture());
            assertThat(captor.getValue()).hasSize(10);
        }

        @Test
        @DisplayName("pattern entity fields are correctly populated")
        void patternFieldsPopulated() {
            when(fraudPatternRepository.findByPatternType(anyString())).thenReturn(List.of());
            ArgumentCaptor<FraudPattern> captor = ArgumentCaptor.forClass(FraudPattern.class);
            when(fraudPatternRepository.save(captor.capture())).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });

            seeder.seed();

            List<FraudPattern> saved = captor.getAllValues();
            assertThat(saved).isNotEmpty();

            // Verify first pattern has all required fields
            FraudPattern first = saved.get(0);
            assertThat(first.getPatternType()).isNotBlank();
            assertThat(first.getTitle()).isNotBlank();
            assertThat(first.getDescription()).isNotBlank();
            assertThat(first.getRegulatoryReference()).isNotBlank();
            assertThat(first.getSource()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Idempotent behavior")
    class IdempotentBehavior {

        @Test
        @DisplayName("skips patterns that already exist in the database")
        void skipsExistingPatterns() {
            // First pattern type returns an existing match
            FraudPattern existing = new FraudPattern();
            existing.setPatternType("STRUCTURING");
            existing.setTitle("Transaction Structuring (Smurfing)");

            when(fraudPatternRepository.findByPatternType("STRUCTURING"))
                    .thenReturn(List.of(existing));
            // All other types return empty
            when(fraudPatternRepository.findByPatternType(argThat(type ->
                    type != null && !type.equals("STRUCTURING"))))
                    .thenReturn(List.of());
            when(fraudPatternRepository.save(any(FraudPattern.class))).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });

            SeedResult result = seeder.seed();

            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.saved()).isEqualTo(9);
        }

        @Test
        @DisplayName("skips all when everything already exists")
        void skipsAllWhenAllExist() {
            // Return a matching entity for every findByPatternType call
            when(fraudPatternRepository.findByPatternType(anyString())).thenAnswer(invocation -> {
                String type = invocation.getArgument(0);
                FraudPattern match = new FraudPattern();
                match.setPatternType(type);
                // Set a title that will match — use a wildcard-like approach
                // The seeder checks title equality, so we need the actual title
                // We'll match any title by returning a pattern with matching title
                return List.of(); // This won't skip — let's take a different approach
            });

            // Actually: to skip all, we need titles to match exactly.
            // Instead, mock at a higher level: make the first seed() call real,
            // then on second call, everything is skipped.
            // Simpler approach: just mock findByPatternType to return a pattern
            // whose title matches the DTO title. Since we know the first pattern
            // is "Transaction Structuring (Smurfing)" with type STRUCTURING,
            // let's just verify the count behavior.

            // Reset and use a simpler approach: no patterns exist → seed → then
            // verify that seeding again with existing patterns skips all
            when(fraudPatternRepository.findByPatternType(anyString())).thenReturn(List.of());
            when(fraudPatternRepository.save(any(FraudPattern.class))).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });

            SeedResult firstRun = seeder.seed();
            assertThat(firstRun.saved()).isEqualTo(10);

            // Now make all types return matching patterns
            ArgumentCaptor<FraudPattern> captor = ArgumentCaptor.forClass(FraudPattern.class);
            verify(fraudPatternRepository, times(10)).save(captor.capture());
            List<FraudPattern> allSaved = captor.getAllValues();

            // Group saved patterns by type for the mock
            reset(fraudPatternRepository, vectorStore);
            for (FraudPattern fp : allSaved) {
                when(fraudPatternRepository.findByPatternType(fp.getPatternType()))
                        .thenReturn(List.of(fp));
            }

            SeedResult secondRun = seeder.seed();
            assertThat(secondRun.saved()).isEqualTo(0);
            assertThat(secondRun.skipped()).isEqualTo(10);
            // Vectorized = 10 because we always upsert all patterns to ensure the vector store
            // is populated even when DB patterns were pre-existing (e.g. vector_store was empty).
            assertThat(secondRun.vectorized()).isEqualTo(10);
            verify(fraudPatternRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns error result when seed file not found")
        void errorWhenFileNotFound() {
            SeedResult result = seeder.seed("nonexistent/file.json");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("Failed to load seed file");
            assertThat(result.saved()).isEqualTo(0);
        }

        @Test
        @DisplayName("continues saving patterns when vector store fails")
        void continuesWhenVectorStoreFails() {
            when(fraudPatternRepository.findByPatternType(anyString())).thenReturn(List.of());
            when(fraudPatternRepository.save(any(FraudPattern.class))).thenAnswer(invocation -> {
                FraudPattern fp = invocation.getArgument(0);
                fp.setId(1L);
                return fp;
            });
            doThrow(new RuntimeException("Embedding service unavailable"))
                    .when(vectorStore).add(anyList());

            SeedResult result = seeder.seed();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.saved()).isEqualTo(10);
            assertThat(result.vectorized()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Document building")
    class DocumentBuilding {

        @Test
        @DisplayName("document contains all pattern information")
        void documentContainsAllFields() {
            FraudPattern entity = new FraudPattern();
            entity.setId(42L);
            entity.setPatternType("TEST_TYPE");
            entity.setTitle("Test Pattern");

            FraudPatternSeeder.FraudPatternDto dto = new FraudPatternSeeder.FraudPatternDto(
                    "TEST_TYPE",
                    "Test Pattern",
                    "A test fraud pattern description",
                    List.of("Indicator 1", "Indicator 2"),
                    "BSA Section 5318(g)",
                    "An example scenario for testing",
                    "Unit Test"
            );

            Document doc = seeder.buildDocument(entity, dto);

            assertThat(doc.getText()).contains("Test Pattern");
            assertThat(doc.getText()).contains("TEST_TYPE");
            assertThat(doc.getText()).contains("A test fraud pattern description");
            assertThat(doc.getText()).contains("Indicator 1");
            assertThat(doc.getText()).contains("Indicator 2");
            assertThat(doc.getText()).contains("BSA Section 5318(g)");
            assertThat(doc.getText()).contains("An example scenario for testing");
        }

        @Test
        @DisplayName("document metadata contains expected keys")
        void documentMetadataCorrect() {
            FraudPattern entity = new FraudPattern();
            entity.setId(42L);
            entity.setPatternType("LAYERING");
            entity.setTitle("Layering Transactions");

            FraudPatternSeeder.FraudPatternDto dto = new FraudPatternSeeder.FraudPatternDto(
                    "LAYERING",
                    "Layering Transactions",
                    "Description",
                    List.of(),
                    "FATF",
                    null,
                    "FinCEN"
            );

            Document doc = seeder.buildDocument(entity, dto);

            assertThat(doc.getMetadata())
                    .containsEntry("patternType", "LAYERING")
                    .containsEntry("title", "Layering Transactions")
                    .containsEntry("source", "FinCEN")
                    .containsEntry("fraudPatternId", 42L);
        }

        @Test
        @DisplayName("handles null optional fields gracefully")
        void handlesNullFields() {
            FraudPattern entity = new FraudPattern();
            entity.setId(1L);
            entity.setPatternType("MINIMAL");
            entity.setTitle("Minimal Pattern");

            FraudPatternSeeder.FraudPatternDto dto = new FraudPatternSeeder.FraudPatternDto(
                    "MINIMAL",
                    "Minimal Pattern",
                    "Just a description",
                    null,
                    null,
                    null,
                    null
            );

            Document doc = seeder.buildDocument(entity, dto);

            assertThat(doc.getText()).contains("Minimal Pattern");
            assertThat(doc.getText()).contains("Just a description");
            assertThat(doc.getText()).doesNotContain("Regulatory References");
            assertThat(doc.getText()).doesNotContain("Example Scenario");
            assertThat(doc.getMetadata()).containsEntry("source", "unknown");
        }
    }
}
