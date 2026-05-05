package dev.finguard.detection.dto;

import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;

import java.time.LocalDateTime;

/**
 * REST API response representing a single alert.
 * Never exposes internal entity relationships — only summary data.
 */
public record AlertResponse(
        Long id,
        Long transactionId,
        DetectionConfig detectionConfig,
        String ruleTriggered,
        Double mlRiskScore,
        String mlModelName,
        boolean isAnomaly,
        AlertStatus status,
        LocalDateTime createdAt
) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getTransaction().getId(),
                alert.getDetectionConfig(),
                alert.getRuleTriggered(),
                alert.getMlRiskScore(),
                alert.getMlModelName(),
                alert.getIsAnomaly(),
                alert.getStatus(),
                alert.getCreatedAt()
        );
    }
}
