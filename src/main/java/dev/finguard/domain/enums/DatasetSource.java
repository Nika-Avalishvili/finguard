package dev.finguard.domain.enums;

/**
 * Supported transaction datasets for ingestion + evaluation.
 *
 * <p><b>Thesis scope (post-audit):</b> the master's-thesis evaluation uses
 * <b>PaySim only</b> as its primary benchmark. The {@code IBM_AML} and
 * {@code CUSTOM} values are retained for future-work compatibility — there
 * is no loader implementation for them today. See research plan §3.3
 * "Datasets" and §7 "Future Work" for the deferred multi-dataset roadmap.</p>
 */
public enum DatasetSource {
    /** Synthetic mobile-money fraud dataset, ~6.3 M tx, 0.13 % fraud rate. Primary thesis benchmark. */
    PAYSIM("PaySim"),
    /**
     * IBM AMLSim multi-step laundering dataset.
     * <p><b>Not implemented</b> — no loader exists. Tagged here for forward
     * compatibility; see research-plan §7 future-work item.</p>
     */
    IBM_AML("IBM AML (future work)"),
    /**
     * Custom synthetic dataset with controlled anomaly injection.
     * <p><b>Not implemented</b> — no loader exists. Tagged here for forward
     * compatibility; see research-plan §7 future-work item.</p>
     */
    CUSTOM("Custom (future work)");

    private final String displayName;

    DatasetSource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return {@code true} only for datasets that have a working ingestion + evaluation
     *         pipeline today. The UI uses this to grey-out future-work options.
     */
    public boolean isImplemented() {
        return this == PAYSIM;
    }
}
