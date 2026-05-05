package dev.finguard.presentation.controller;

import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.explanation.rag.FraudPatternSeeder;
import dev.finguard.explanation.rag.FraudPatternSeeder.SeedResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing the RAG knowledge base.
 *
 * <p>Provides endpoints for seeding fraud pattern data into the database
 * and vector store, as well as querying the persisted patterns.</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@Tag(name = "Knowledge Base", description = "RAG knowledge base management — fraud patterns and vector store")
public class KnowledgeBaseController {

    private final FraudPatternSeeder fraudPatternSeeder;
    private final FraudPatternRepository fraudPatternRepository;

    public KnowledgeBaseController(FraudPatternSeeder fraudPatternSeeder,
                                    FraudPatternRepository fraudPatternRepository) {
        this.fraudPatternSeeder = fraudPatternSeeder;
        this.fraudPatternRepository = fraudPatternRepository;
    }

    /**
     * Seed fraud patterns from the default classpath resource.
     *
     * <p>Idempotent — skips patterns that already exist in the database.</p>
     */
    @PostMapping("/seed")
    @Operation(summary = "Seed fraud patterns",
               description = "Seeds fraud pattern data from the default classpath resource into the DB and vector store. Idempotent.")
    public ResponseEntity<SeedResultDto> seedPatterns() {
        SeedResult result = fraudPatternSeeder.seed();
        SeedResultDto dto = SeedResultDto.from(result);

        if (!result.isSuccess()) {
            return ResponseEntity.internalServerError().body(dto);
        }
        return ResponseEntity.ok(dto);
    }

    /**
     * List all fraud patterns stored in the database.
     */
    @GetMapping("/patterns")
    @Operation(summary = "List all fraud patterns", description = "Returns all fraud patterns stored in the knowledge base.")
    public ResponseEntity<List<FraudPatternDto>> getAllPatterns() {
        List<FraudPatternDto> patterns = fraudPatternRepository.findAll().stream()
                .map(FraudPatternDto::from)
                .toList();
        return ResponseEntity.ok(patterns);
    }

    /**
     * Get fraud patterns by type.
     */
    @GetMapping("/patterns/{patternType}")
    @Operation(summary = "Get patterns by type", description = "Returns fraud patterns filtered by pattern type (e.g., STRUCTURING, MONEY_MULE).")
    public ResponseEntity<List<FraudPatternDto>> getPatternsByType(
            @PathVariable String patternType) {
        List<FraudPatternDto> patterns = fraudPatternRepository.findByPatternType(patternType)
                .stream()
                .map(FraudPatternDto::from)
                .toList();
        return ResponseEntity.ok(patterns);
    }

    /**
     * Get the count of seeded patterns.
     */
    @GetMapping("/patterns/count")
    @Operation(summary = "Get pattern count", description = "Returns the total number of fraud patterns in the knowledge base.")
    public ResponseEntity<Long> getPatternCount() {
        return ResponseEntity.ok(fraudPatternRepository.count());
    }

    record SeedResultDto(int saved, int skipped, int vectorized, boolean success, String error) {
        static SeedResultDto from(SeedResult result) {
            return new SeedResultDto(
                    result.saved(),
                    result.skipped(),
                    result.vectorized(),
                    result.isSuccess(),
                    result.error()
            );
        }
    }

    record FraudPatternDto(Long id, String patternType, String title, String description,
                           String regulatoryReference, String source) {
        static FraudPatternDto from(FraudPattern entity) {
            return new FraudPatternDto(
                    entity.getId(),
                    entity.getPatternType(),
                    entity.getTitle(),
                    entity.getDescription(),
                    entity.getRegulatoryReference(),
                    entity.getSource()
            );
        }
    }
}
