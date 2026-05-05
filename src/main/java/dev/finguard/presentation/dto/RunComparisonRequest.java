package dev.finguard.presentation.dto;

import dev.finguard.domain.enums.DatasetSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated request body for running a comparative evaluation across all configs.
 */
public record RunComparisonRequest(

        @NotBlank(message = "Experiment name is required")
        @Size(min = 2, max = 100, message = "Experiment name must be between 2 and 100 characters")
        String experimentName,

        DatasetSource dataset,

        boolean scoreExplanations
) {
    public RunComparisonRequest {
        if (dataset == null) {
            dataset = DatasetSource.PAYSIM;
        }
    }
}
