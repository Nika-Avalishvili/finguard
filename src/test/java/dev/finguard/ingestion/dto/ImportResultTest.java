package dev.finguard.ingestion.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ImportResult")
class ImportResultTest {

    private ImportResult result;

    @BeforeEach
    void setUp() {
        result = new ImportResult();
    }

    @Test
    @DisplayName("Should start with zero counts")
    void shouldStartWithZeroCounts() {
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getSkippedCount()).isZero();
        assertThat(result.getTotalRows()).isZero();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("incrementSuccess should increase success count by 1")
    void incrementSuccess_shouldIncrementByOne() {
        result.incrementSuccess();
        result.incrementSuccess();
        result.incrementSuccess();

        assertThat(result.getSuccessCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("addError should increase skipped count and record error detail")
    void addError_shouldIncrementSkippedAndRecordError() {
        result.addError(5, "Invalid amount");
        result.addError(12, "Unknown type");

        assertThat(result.getSkippedCount()).isEqualTo(2);
        assertThat(result.getErrors()).hasSize(2);
        assertThat(result.getErrors().get(0).rowNumber()).isEqualTo(5);
        assertThat(result.getErrors().get(0).reason()).isEqualTo("Invalid amount");
        assertThat(result.getErrors().get(1).rowNumber()).isEqualTo(12);
    }

    @Test
    @DisplayName("Should cap error list at 100 to prevent memory issues on bad files")
    void addError_shouldCapErrorListAt100() {
        for (int i = 0; i < 150; i++) {
            result.addError(i, "Error " + i);
        }

        // Skipped count should track ALL errors
        assertThat(result.getSkippedCount()).isEqualTo(150);

        // But error details list should be capped at 100
        assertThat(result.getErrors()).hasSize(100);

        // First and last recorded errors should be correct
        assertThat(result.getErrors().get(0).rowNumber()).isZero();
        assertThat(result.getErrors().get(99).rowNumber()).isEqualTo(99);
    }
}
