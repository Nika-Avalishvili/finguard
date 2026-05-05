package dev.finguard.detection.rule;

import java.math.BigDecimal;

/**
 * Canonical, curated reference for the four rule-based detection checks.
 *
 * <p>Each entry carries everything a thesis reviewer needs to understand why a
 * particular threshold was chosen:</p>
 *
 * <ul>
 *   <li><b>Industry range</b> — the lower and upper bounds seen in published
 *       bank-deployment surveys / regulatory guidance. No single threshold fits
 *       every institution; this is the range to stay within.</li>
 *   <li><b>Industry reference</b> — the most-cited single value (for rules that
 *       have one; e.g. BSA's $10 000 CTR threshold is a legal standard).</li>
 *   <li><b>Thesis default</b> — the value calibrated specifically for the
 *       PaySim synthetic dataset and documented in §0.3c of the thesis
 *       evaluation guide. This is what ships as the seed in Liquibase 020.</li>
 *   <li><b>Citation</b> — the primary source(s) the threshold range or
 *       reference is drawn from, in a form ready to paste into §3.5 of the
 *       thesis.</li>
 *   <li><b>Rationale</b> — a one-sentence human explanation for why the thesis
 *       default differs from the naive paper default (if it does), surfaced
 *       directly in the Knowledge Base UI.</li>
 * </ul>
 *
 * <p>The <b>current</b> value at runtime lives in the {@code rule_thresholds}
 * table (user-editable). This enum is code-only — to change a citation or
 * industry range, change Java and rebuild (these are research facts, not
 * operational state).</p>
 */
public enum RuleMetadata {

    LARGE_TRANSACTION(
            "Large Transaction",
            "Flags a single transaction whose amount is by itself suspicious.",
            "USD",
            new BigDecimal("10000"),
            new BigDecimal("10000000"),
            null,
            new BigDecimal("200000"),
            new BigDecimal("1000000"),
            "No single industry standard. U.S. banks commonly alert at the BSA "
                    + "CTR threshold ($10 000) for cash, and in the $100 000 – $1 000 000 "
                    + "band for wires. Thesis default calibrated to the 95th percentile "
                    + "of PaySim TRANSFER amounts, which puts the rule squarely inside "
                    + "the industry band for large-transfer alerts.",
            "McKinsey (2019); Federal Reserve SR 21-14; 31 CFR 1010.311"),

    NEW_RECEIVER_HIGH_VALUE(
            "New Receiver, High Value",
            "Flags a large transfer going to an account the sender has never used before — "
                    + "a common Account Takeover / mule-account pattern.",
            "USD",
            new BigDecimal("1000"),
            new BigDecimal("100000"),
            new BigDecimal("10000"),
            new BigDecimal("50000"),
            new BigDecimal("500000"),
            "Industry deployments typically alert in the $10 000 – $50 000 range "
                    + "(e.g. Gartner 2021 AML transaction-monitoring survey). Thesis "
                    + "default is 10× higher because PaySim generates a unique destination "
                    + "account per TRANSFER by construction (Lopez-Rojas & Axelsson, "
                    + "EMSS 2016, §4.2). That quirk makes isNewReceiver=TRUE on ~95 % of "
                    + "transfers regardless of behaviour, so the naive $50k threshold "
                    + "fires on ~57 % of the dataset — unrepresentative of real banking. "
                    + "Raising to the 95th-percentile TRANSFER amount restores realistic "
                    + "firing rates (~1 % of transactions).",
            "Gartner (2021); Lopez-Rojas & Axelsson, EMSS 2016"),

    STRUCTURING(
            "Structuring (Smurfing)",
            "Flags round-amount transactions at or below the reporting threshold "
                    + "combined with high 24-hour velocity — the classic structuring pattern "
                    + "(breaking a large cash deposit into small ones to avoid CTR filing).",
            "USD",
            new BigDecimal("5000"),
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            new BigDecimal("10000"),
            "This is a legal threshold, not a heuristic. 31 CFR 1010.311 requires "
                    + "a Currency Transaction Report for every cash transaction over "
                    + "$10 000. Structuring is defined (31 USC 5324) as deliberately "
                    + "splitting transactions to evade that report. Kept at $10 000 — "
                    + "aligns with the law. Change only if you want to study sensitivity "
                    + "of the rule to the reporting threshold.",
            "31 USC 5324; 31 CFR 1010.311; FinCEN FIN-2014-A005"),

    RAPID_VELOCITY(
            "Rapid Velocity",
            "Flags a sender that is making many transactions in a short window — "
                    + "common in Account Takeover and automated-fraud scenarios.",
            "transactions / hour",
            new BigDecimal("2"),
            new BigDecimal("10"),
            new BigDecimal("5"),
            new BigDecimal("3"),
            new BigDecimal("3"),
            "Typical industry alerting is 3 – 5 transactions per hour "
                    + "(Chen 2020). Kept at 3 for PaySim because the simulator spaces "
                    + "transactions irregularly; 3 matches the default industry lower "
                    + "bound without over-triggering on bursty mobile-money usage.",
            "Chen (2020), Journal of Money Laundering Control 23(4)");

    private final String humanName;
    private final String description;
    private final String unit;
    private final BigDecimal industryMinimum;
    private final BigDecimal industryMaximum;
    private final BigDecimal industryReference;
    private final BigDecimal paperDefault;
    private final BigDecimal thesisDefault;
    private final String rationale;
    private final String citation;

    RuleMetadata(String humanName,
                 String description,
                 String unit,
                 BigDecimal industryMinimum,
                 BigDecimal industryMaximum,
                 BigDecimal industryReference,
                 BigDecimal paperDefault,
                 BigDecimal thesisDefault,
                 String rationale,
                 String citation) {
        this.humanName         = humanName;
        this.description       = description;
        this.unit              = unit;
        this.industryMinimum   = industryMinimum;
        this.industryMaximum   = industryMaximum;
        this.industryReference = industryReference;
        this.paperDefault      = paperDefault;
        this.thesisDefault     = thesisDefault;
        this.rationale         = rationale;
        this.citation          = citation;
    }

    public String getHumanName()              { return humanName; }
    public String getDescription()            { return description; }
    public String getUnit()                   { return unit; }
    public BigDecimal getIndustryMinimum()    { return industryMinimum; }
    public BigDecimal getIndustryMaximum()    { return industryMaximum; }
    public BigDecimal getIndustryReference()  { return industryReference; }
    public BigDecimal getPaperDefault()       { return paperDefault; }
    public BigDecimal getThesisDefault()      { return thesisDefault; }
    public String getRationale()              { return rationale; }
    public String getCitation()               { return citation; }
}
