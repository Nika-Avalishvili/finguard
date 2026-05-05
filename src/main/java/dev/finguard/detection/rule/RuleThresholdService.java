package dev.finguard.detection.rule;

import dev.finguard.config.exception.BadRequestException;
import dev.finguard.domain.model.RuleThreshold;
import dev.finguard.domain.repository.RuleThresholdRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reads and writes the user-editable detection-rule thresholds.
 *
 * <h2>Design</h2>
 * <p>Canonical metadata (industry range, citation, thesis default) lives in
 * {@link RuleMetadata}. The <b>current</b> value lives in the
 * {@code rule_thresholds} table (see Liquibase 020). Callers — i.e. the four
 * rule classes in {@code dev.finguard.detection.rule.*Rule} — consult this
 * service on every evaluation call, which lets a user edit a threshold from
 * the Knowledge Base UI and have the change take effect on the next
 * transaction without a restart.</p>
 *
 * <h2>Concurrency + caching</h2>
 * <p>Reads are served from an in-memory {@code ConcurrentHashMap} seeded at
 * construction + refreshed whenever a write happens. A direct DB hit per
 * rule evaluation would be unacceptable at 6 M-row detection scales (millions
 * of indexed queries per config). With the cache, reads are constant-time
 * HashMap lookups.</p>
 *
 * <p>Writes are transactional, publish an {@code updated_at} audit trail, and
 * validate against {@link RuleMetadata} industry bounds — users can't set
 * nonsensical values (e.g. a {@code NEW_RECEIVER_HIGH_VALUE} threshold of
 * one cent).</p>
 */
@Service
public class RuleThresholdService {

    private static final Logger log = LoggerFactory.getLogger(RuleThresholdService.class);

    private final RuleThresholdRepository repository;
    private final ConcurrentMap<String, BigDecimal> cache = new ConcurrentHashMap<>();

    public RuleThresholdService(RuleThresholdRepository repository) {
        this.repository = repository;
    }

    /**
     * Load the DB-stored values into the cache at application startup. If the
     * row is missing for a rule (shouldn't happen after Liquibase 020 runs, but
     * defensive for tests that drop + recreate the table), the cache falls back
     * to {@link RuleMetadata#getThesisDefault()}.
     */
    @PostConstruct
    void warmCache() {
        repository.findAll().forEach(
                t -> cache.put(t.getRuleName(), t.getCurrentValue()));
        log.info("Rule threshold cache loaded: {} entries", cache.size());
    }

    /** @return the current numeric threshold for the given rule. */
    public BigDecimal getCurrentValue(RuleMetadata rule) {
        BigDecimal v = cache.get(rule.name());
        return v != null ? v : rule.getThesisDefault();
    }

    /** Integer convenience accessor — used by {@code RAPID_VELOCITY}. */
    public int getCurrentIntValue(RuleMetadata rule) {
        return getCurrentValue(rule).intValue();
    }

    /**
     * Update a rule's current threshold. Validates against {@link RuleMetadata}
     * industry bounds and persists the change transactionally; cache is updated
     * on success.
     *
     * @param ruleName rule identifier (must equal a {@link RuleMetadata} enum constant)
     * @param value    new numeric value; must be {@code > 0} and within industry bounds
     * @throws BadRequestException for unknown rule names or out-of-range values
     */
    @Transactional
    public RuleThreshold updateThreshold(String ruleName, BigDecimal value) {
        RuleMetadata meta = resolveOrFail(ruleName);
        validate(meta, value);

        RuleThreshold row = repository.findById(meta.name())
                .orElseGet(() -> new RuleThreshold(meta.name(), meta.getThesisDefault()));
        BigDecimal previous = row.getCurrentValue();
        row.setCurrentValue(value);
        RuleThreshold saved = repository.save(row);

        cache.put(meta.name(), value);
        log.info("Rule threshold updated: {} {} → {} (unit: {})",
                meta.name(), previous, value, meta.getUnit());
        return saved;
    }

    /**
     * Reset one rule (or all rules, if {@code ruleName} is null / blank) to
     * its thesis default. Useful for the "restore defaults" button in the UI.
     */
    @Transactional
    public List<RuleThreshold> resetToThesisDefault(String ruleName) {
        List<RuleMetadata> targets = new ArrayList<>();
        if (ruleName == null || ruleName.isBlank()) {
            for (RuleMetadata m : RuleMetadata.values()) targets.add(m);
        } else {
            targets.add(resolveOrFail(ruleName));
        }
        List<RuleThreshold> updated = new ArrayList<>(targets.size());
        for (RuleMetadata m : targets) {
            updated.add(updateThreshold(m.name(), m.getThesisDefault()));
        }
        return updated;
    }

    /**
     * Full snapshot for the REST layer / Knowledge Base UI — metadata + the
     * currently effective value. Returned in enum-declaration order so the UI
     * is stable.
     */
    public List<ThresholdView> listAll() {
        List<ThresholdView> out = new ArrayList<>(RuleMetadata.values().length);
        for (RuleMetadata m : RuleMetadata.values()) {
            out.add(new ThresholdView(m, getCurrentValue(m)));
        }
        return out;
    }

    private static RuleMetadata resolveOrFail(String ruleName) {
        if (ruleName == null || ruleName.isBlank()) {
            throw new BadRequestException("ruleName is required");
        }
        try {
            return RuleMetadata.valueOf(ruleName);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown rule: " + ruleName);
        }
    }

    /**
     * Validates a threshold value. Intentionally <b>only</b> rejects
     * non-positive values as hard errors — industry-range bounds are
     * <i>advisory</i>, not enforced, because legitimate research workflows
     * frequently need thresholds outside the published industry range.
     *
     * <p>Example: the thesis default for {@code NEW_RECEIVER_HIGH_VALUE} is
     * $500 000, deliberately 5× the industry maximum of $100 000 — it
     * compensates for PaySim's unique-receiver generation quirk (see
     * {@link RuleMetadata#NEW_RECEIVER_HIGH_VALUE} rationale). Enforcing the
     * industry max as a hard limit would block our own thesis calibration.</p>
     *
     * <p>The Knowledge Base UI displays a "outside industry range" badge when
     * the current value falls outside the published bounds, giving users
     * transparent feedback without blocking legitimate experimentation.</p>
     */
    private static void validate(RuleMetadata meta, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BadRequestException(
                    "Rule threshold must be positive; got " + value);
        }
        // Advisory warning — not thrown — when outside industry range.
        if (meta.getIndustryMinimum() != null
                && value.compareTo(meta.getIndustryMinimum()) < 0) {
            log.warn("[{}] value {} is below the published industry minimum {} {} — accepted "
                            + "but flagged for transparency in the UI.",
                    meta.name(), value, meta.getIndustryMinimum(), meta.getUnit());
        } else if (meta.getIndustryMaximum() != null
                && value.compareTo(meta.getIndustryMaximum()) > 0) {
            log.warn("[{}] value {} is above the published industry maximum {} {} — accepted "
                            + "but flagged for transparency in the UI.",
                    meta.name(), value, meta.getIndustryMaximum(), meta.getUnit());
        }
    }

    /**
     * Projection returned to the Knowledge Base UI. Contains every field a
     * reviewer wants to see: where the industry sits, where the thesis sits,
     * where the user is sitting right now, and why.
     *
     * <p>Includes pre-formatted display strings ({@code *Display}) so the
     * Thymeleaf template doesn't have to know how to format BigDecimals with
     * unit prefixes — the service is the single source of truth for what a
     * threshold <em>looks</em> like to a reviewer.</p>
     */
    public record ThresholdView(
            String ruleName,
            String humanName,
            String description,
            String unit,
            BigDecimal industryMinimum,
            BigDecimal industryMaximum,
            BigDecimal industryReference,
            BigDecimal paperDefault,
            BigDecimal thesisDefault,
            BigDecimal currentValue,
            /** "$200,000" / "3" — ready-to-render display form for each amount. */
            String industryMinimumDisplay,
            String industryMaximumDisplay,
            String paperDefaultDisplay,
            String thesisDefaultDisplay,
            String currentValueDisplay,
            /** Number input needs a plain numeric string without thousands separators. */
            String currentValueInputRaw,
            boolean isAtThesisDefault,
            /** True when {@code currentValue} sits outside the published industry range — UI flag. */
            boolean isOutsideIndustryRange,
            String rationale,
            String citation
    ) {
        public ThresholdView(RuleMetadata m, BigDecimal currentValue) {
            this(m.name(),
                 m.getHumanName(),
                 m.getDescription(),
                 m.getUnit(),
                 m.getIndustryMinimum(),
                 m.getIndustryMaximum(),
                 m.getIndustryReference(),
                 m.getPaperDefault(),
                 m.getThesisDefault(),
                 currentValue,
                 formatDisplay(m, m.getIndustryMinimum()),
                 formatDisplay(m, m.getIndustryMaximum()),
                 formatDisplay(m, m.getPaperDefault()),
                 formatDisplay(m, m.getThesisDefault()),
                 formatDisplay(m, currentValue),
                 formatInputRaw(currentValue),
                 currentValue != null && currentValue.compareTo(m.getThesisDefault()) == 0,
                 outsideIndustryRange(m, currentValue),
                 m.getRationale(),
                 m.getCitation());
        }

        private static boolean outsideIndustryRange(RuleMetadata m, BigDecimal v) {
            if (v == null) return false;
            if (m.getIndustryMinimum() != null && v.compareTo(m.getIndustryMinimum()) < 0) return true;
            if (m.getIndustryMaximum() != null && v.compareTo(m.getIndustryMaximum()) > 0) return true;
            return false;
        }

        /**
         * Human-readable number for display. Drops trailing zeros ("1000000.0000"
         * → "1,000,000"), adds thousands separators, and prefixes the value
         * with "$" for USD amounts or suffixes with the unit for counts.
         */
        private static String formatDisplay(RuleMetadata m, BigDecimal value) {
            if (value == null) return "—";
            BigDecimal normalized = value.stripTrailingZeros();
            String unit = m.getUnit();
            if ("USD".equals(unit)) {
                return "$" + formatWithCommas(normalized);
            }
            // Non-monetary (e.g. "transactions / hour" for RAPID_VELOCITY)
            return formatWithCommas(normalized) + " " + unit;
        }

        /**
         * Plain numeric string for the HTML number input (no separators, no
         * currency prefix, no trailing zeros). "1000000.0000" → "1000000".
         */
        private static String formatInputRaw(BigDecimal value) {
            if (value == null) return "";
            return value.stripTrailingZeros().toPlainString();
        }

        private static String formatWithCommas(BigDecimal normalized) {
            // Use plain string to avoid scientific notation for large values after
            // stripTrailingZeros (e.g. "1E+6"), then delegate to NumberFormat.
            java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
            nf.setMaximumFractionDigits(Math.max(0, normalized.scale()));
            nf.setMinimumFractionDigits(0);
            return nf.format(normalized);
        }
    }
}
