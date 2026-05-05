package dev.finguard.presentation.dto;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Validated request body for running an evaluation experiment.
 */
public record RunExperimentRequest(

        @NotBlank(message = "Experiment name is required")
        @Size(min = 2, max = 100, message = "Experiment name must be between 2 and 100 characters")
        String experimentName,

        @NotNull(message = "Detection config is required")
        DetectionConfig config,

        DatasetSource dataset,

        Integer fold,

        boolean scoreExplanations,

        @Min(value = 1, message = "Max explanations to score must be at least 1")
        @Max(value = 500, message = "Max explanations to score must be at most 500")
        int maxExplanationsToScore,

        /**
         * Caps how many alerts will actually receive an LLM-generated explanation per run.
         * Without this cap a run that flagged hundreds of thousands of alerts would
         * trigger one LLM call per alert — infeasible on local hardware. The CAKR judge
         * (controlled by {@link #maxExplanationsToScore}) samples from the generated
         * pool, so there's no value in generating more than it will read. Defaults to 500.
         */
        @Min(value = 1, message = "Max alerts to explain must be at least 1")
        @Max(value = 10_000, message = "Max alerts to explain must be at most 10 000")
        Integer maxAlertsToExplain
) {
    public RunExperimentRequest {
        if (dataset == null) {
            dataset = DatasetSource.PAYSIM;
        }
        if (maxExplanationsToScore == 0) {
            maxExplanationsToScore = 50;
        }
        if (maxAlertsToExplain == null || maxAlertsToExplain <= 0) {
            maxAlertsToExplain = 500;
        }
    }
}
