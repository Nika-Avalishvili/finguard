package dev.finguard.explanation.validation;

import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.explanation.llm.ExplanationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates LLM-generated explanations against known transaction facts.
 *
 * <p>Checks for common hallucination patterns:</p>
 * <ul>
 *   <li><b>Amount fabrication</b> — explanation references an amount that doesn't match the transaction</li>
 *   <li><b>Account fabrication</b> — explanation references account IDs not involved in the transaction</li>
 *   <li><b>Score fabrication</b> — explanation claims a risk score inconsistent with the alert</li>
 *   <li><b>Confidence sanity</b> — LLM self-assessed confidence is within valid range</li>
 *   <li><b>Empty critical fields</b> — required fields are missing or empty</li>
 * </ul>
 *
 * <p>Returns a {@link ValidationResult} containing a list of flags (empty = no hallucinations)
 * and a boolean indicating whether the explanation passes validation.</p>
 */
@Component
public class HallucinationValidator {

    private static final Logger log = LoggerFactory.getLogger(HallucinationValidator.class);

    /**
     * Regex that matches standalone numbers (with optional commas, decimals, $ prefix)
     * and an optional K/k suffix for thousands. Used in fuzzy amount matching.
     */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
            "\\$?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)\\s*([Kk])?");

    /**
     * Audit B-7: compiled once at class-load so each call to
     * {@link #containsPlausibleAccountId(String, String, String)} reuses it —
     * previously re-compiled per explanation (~1–5 ms/1000 rows in a batch).
     */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("[A-Z]\\d{7,}");

    /** LLMs often round or approximate amounts — allow up to 20% deviation. */
    private static final double AMOUNT_TOLERANCE = 0.20;

    @Value("${finguard.explanation.confidence-threshold:0.6}")
    private double confidenceThreshold;

    /**
     * Validate an explanation response against the source transaction and alert data.
     *
     * @param response the parsed LLM explanation
     * @param alert    the originating alert
     * @param tx       the flagged transaction
     * @param features pre-computed features (may be null)
     * @return validation result with hallucination flags
     */
    public ValidationResult validate(ExplanationResponse response, Alert alert,
                                      Transaction tx, TransactionFeatures features) {
        List<String> flags = new ArrayList<>();

        checkEmptyFields(response, flags);
        checkConfidenceRange(response, flags);
        checkAmountConsistency(response, tx, flags);
        checkAccountConsistency(response, tx, flags);
        checkRiskScoreConsistency(response, alert, flags);

        boolean hallucinationFree = flags.isEmpty();

        if (!hallucinationFree) {
            log.warn("Hallucination flags for alert {}: {}", alert.getId(), flags);
        }

        return new ValidationResult(hallucinationFree, flags);
    }

    /**
     * Check that critical fields are not empty or null.
     */
    private void checkEmptyFields(ExplanationResponse response, List<String> flags) {
        if (response.riskSummary() == null || response.riskSummary().isBlank()) {
            flags.add("EMPTY_RISK_SUMMARY");
        }
        if (response.explanationText() == null || response.explanationText().isBlank()) {
            flags.add("EMPTY_EXPLANATION_TEXT");
        }
        if (response.suspiciousPatterns() == null || response.suspiciousPatterns().isEmpty()) {
            flags.add("EMPTY_SUSPICIOUS_PATTERNS");
        }
        if (response.recommendedActions() == null || response.recommendedActions().isEmpty()) {
            flags.add("EMPTY_RECOMMENDED_ACTIONS");
        }
    }

    /**
     * Check that the LLM's self-assessed confidence is within valid range.
     */
    private void checkConfidenceRange(ExplanationResponse response, List<String> flags) {
        double score = response.confidenceScore();
        if (score < 0.0 || score > 1.0) {
            flags.add("CONFIDENCE_OUT_OF_RANGE: " + score);
        }
    }

    /**
     * Check that the explanation text references an amount consistent with the transaction.
     *
     * <p>Scans both {@code explanationText} and {@code riskSummary} for the transaction amount.
     * Accepts several format variants (plain, comma-grouped, K-suffix) and falls back to a
     * fuzzy numeric scan that tolerates ±20% rounding — matching how LLMs naturally describe
     * monetary values (e.g., "approximately $181K" for an actual amount of 181,234.23).</p>
     */
    private void checkAmountConsistency(ExplanationResponse response, Transaction tx,
                                         List<String> flags) {
        if (tx.getAmount() == null) {
            return;
        }

        // Scan both fields — LLMs often mention the amount in riskSummary, not just explanationText
        String combinedText = buildCombinedText(response.explanationText(), response.riskSummary());
        if (combinedText.isBlank()) {
            return;
        }

        BigDecimal amount = tx.getAmount();
        if (containsAmount(combinedText, amount)) {
            return;
        }

        flags.add("AMOUNT_NOT_REFERENCED: transaction amount " + amount.toPlainString()
                + " not found in explanation");
    }

    /**
     * Returns true if the text contains the given amount in any recognisable format:
     * <ul>
     *   <li>Plain: "181234.23"</li>
     *   <li>Integer: "181234"</li>
     *   <li>Comma-grouped with decimal: "181,234.23"</li>
     *   <li>Comma-grouped without decimal: "181,234"</li>
     *   <li>K-abbreviated: "181K", "181k", "$181K"</li>
     *   <li>Fuzzy: any number within ±20% of the actual value</li>
     * </ul>
     */
    private boolean containsAmount(String text, BigDecimal amount) {
        String plain        = amount.toPlainString();
        String integer      = amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        String withDecimal  = new DecimalFormat("#,###.##").format(amount);
        String withoutDecimal = new DecimalFormat("#,###").format(amount);
        long thousands      = amount.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValue();

        if (text.contains(plain)
                || text.contains(integer)
                || text.contains(withDecimal)
                || text.contains(withoutDecimal)
                || text.contains("$" + plain)
                || text.contains("$" + integer)
                || text.contains("$" + withDecimal)
                || text.contains("$" + withoutDecimal)) {
            return true;
        }

        if (thousands > 0) {
            String kUpper = thousands + "K";
            String kLower = thousands + "k";
            if (text.contains(kUpper) || text.contains(kLower)
                    || text.contains("$" + kUpper) || text.contains("$" + kLower)) {
                return true;
            }
        }

        // Fuzzy fallback: extract every numeric value in the text and accept ±AMOUNT_TOLERANCE
        return containsApproximateAmount(text, amount);
    }

    /**
     * Scan text for numeric tokens and return true if any falls within ±20% of {@code expected}.
     * This handles LLM rounding: "$181K" ≈ 181,000 is within 20% of 181,234.23.
     */
    private boolean containsApproximateAmount(String text, BigDecimal expected) {
        double target     = expected.doubleValue();
        double lowerBound = target * (1.0 - AMOUNT_TOLERANCE);
        double upperBound = target * (1.0 + AMOUNT_TOLERANCE);

        Matcher m = NUMERIC_PATTERN.matcher(text);
        while (m.find()) {
            String numStr = m.group(1).replace(",", "");
            String suffix = m.group(2);
            try {
                double val = Double.parseDouble(numStr);
                if (suffix != null) {
                    val *= 1_000;
                }
                if (val >= lowerBound && val <= upperBound) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // non-numeric match — skip
            }
        }
        return false;
    }

    /**
     * Check that the explanation doesn't reference account IDs that don't exist in the transaction.
     *
     * <p>This is a soft check — only flags if the explanation mentions a plausible-looking
     * account ID (8+ alphanumeric chars) that doesn't match sender or receiver.</p>
     */
    private void checkAccountConsistency(ExplanationResponse response, Transaction tx,
                                          List<String> flags) {
        if (response.explanationText() == null) {
            return;
        }

        String text = response.explanationText();
        String sender = tx.getSenderAccount();
        String receiver = tx.getReceiverAccount();

        // Check if the explanation mentions the actual accounts when it references accounts at all
        boolean mentionsAccount = text.toLowerCase().contains("sender")
                || text.toLowerCase().contains("receiver")
                || text.toLowerCase().contains("account");

        if (mentionsAccount && sender != null && receiver != null) {
            // If the explanation discusses specific account identifiers, they should match
            boolean mentionsSender = text.contains(sender);
            boolean mentionsReceiver = text.contains(receiver);

            // Only flag if explanation appears to cite specific account IDs that don't match
            // This is a lenient check — we don't require accounts to be mentioned
            if (!mentionsSender && !mentionsReceiver
                    && containsPlausibleAccountId(text, sender, receiver)) {
                flags.add("POSSIBLE_ACCOUNT_FABRICATION: explanation may reference "
                        + "account IDs not matching sender/receiver");
            }
        }
    }

    /**
     * Check that the explanation's risk assessment is directionally consistent with the ML score.
     */
    private void checkRiskScoreConsistency(ExplanationResponse response, Alert alert,
                                            List<String> flags) {
        if (alert.getMlRiskScore() == null || response.explanationText() == null) {
            return;
        }

        double mlScore = alert.getMlRiskScore();
        String text = response.explanationText().toLowerCase();

        // If ML score is very high (>0.9), the explanation shouldn't say "low risk"
        if (mlScore > 0.9 && (text.contains("low risk") || text.contains("unlikely fraud"))) {
            flags.add("RISK_DIRECTION_MISMATCH: ML score is " + String.format("%.2f", mlScore)
                    + " but explanation suggests low risk");
        }

        // If ML score is moderate (<0.6), the explanation shouldn't claim certainty
        if (mlScore < 0.6 && (text.contains("certainly fraud") || text.contains("definitely fraudulent"))) {
            flags.add("RISK_OVERCLAIM: ML score is only " + String.format("%.2f", mlScore)
                    + " but explanation claims certainty");
        }
    }

    /**
     * Check if text contains what looks like a fabricated account ID.
     */
    private boolean containsPlausibleAccountId(String text, String sender, String receiver) {
        // Simple heuristic: look for patterns like "C12345678" that don't match known accounts.
        // Pattern is now class-level (B-7) — no per-call compilation cost.
        Matcher matcher = ACCOUNT_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            String found = matcher.group();
            if (!found.equals(sender) && !found.equals(receiver)) {
                return true;
            }
        }
        return false;
    }

    private String buildCombinedText(String explanationText, String riskSummary) {
        StringBuilder sb = new StringBuilder();
        if (explanationText != null) sb.append(explanationText);
        if (riskSummary != null) sb.append(' ').append(riskSummary);
        return sb.toString();
    }

    private String formatWithCommas(BigDecimal amount) {
        DecimalFormat df = new DecimalFormat("#,###.##");
        return df.format(amount);
    }

    /**
     * Result of hallucination validation.
     *
     * @param hallucinationFree true if no hallucinations were detected
     * @param flags             list of detected hallucination flags (empty if clean)
     */
    public record ValidationResult(
            boolean hallucinationFree,
            List<String> flags
    ) {}
}
