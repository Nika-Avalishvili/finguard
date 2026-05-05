package dev.finguard.ingestion.dto;

import java.math.BigDecimal;

/**
 * Immutable record mapping a single row from the PaySim CSV dataset.
 * PaySim columns: step, type, amount, nameOrig, oldbalanceOrg, newbalanceOrig,
 *                 nameDest, oldbalanceDest, newbalanceDest, isFraud, isFlaggedFraud
 */
public record PaySimRow(
        int step,
        String type,
        BigDecimal amount,
        String nameOrig,
        BigDecimal oldBalanceOrig,
        BigDecimal newBalanceOrig,
        String nameDest,
        BigDecimal oldBalanceDest,
        BigDecimal newBalanceDest,
        int isFraud,
        int isFlaggedFraud
) {

    /**
     * PaySim uses integer "steps" where 1 step = 1 hour.
     * The simulation spans 744 steps (31 days).
     */
    public static final int MAX_STEP = 744;
}
