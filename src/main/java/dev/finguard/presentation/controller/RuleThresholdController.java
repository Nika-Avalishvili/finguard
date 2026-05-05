package dev.finguard.presentation.controller;

import dev.finguard.detection.rule.RuleThresholdService;
import dev.finguard.detection.rule.RuleThresholdService.ThresholdView;
import dev.finguard.domain.model.RuleThreshold;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST API for inspecting and editing detection-rule thresholds.
 *
 * <p>The Knowledge Base page consumes {@code GET /} to render the
 * "Detection Rules" section (industry range · thesis default · current value
 * · citation · [edit] per rule) and {@code PUT /{ruleName}} when a user
 * commits an edit.</p>
 *
 * <p>{@code POST /reset} provides a one-click "restore thesis defaults" — useful
 * after a user has been exploring different thresholds and wants to return to
 * a reproducible baseline.</p>
 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Rule Thresholds",
        description = "Read + update the editable thresholds used by the RULES_ONLY detection config")
public class RuleThresholdController {

    private static final Logger log = LoggerFactory.getLogger(RuleThresholdController.class);

    private final RuleThresholdService thresholdService;

    public RuleThresholdController(RuleThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    @GetMapping
    @Operation(summary = "List every rule's metadata + current threshold value",
               description = "Returns industry range, thesis default, current value, " +
                             "and citation per rule. Feeds the Knowledge Base UI.")
    public ResponseEntity<List<ThresholdView>> list() {
        return ResponseEntity.ok(thresholdService.listAll());
    }

    @PutMapping("/{ruleName}")
    @Operation(summary = "Update one rule's threshold value",
               description = "The new value is validated against the rule's industry range; " +
                             "out-of-range edits return 400. Takes effect immediately on the next " +
                             "rule evaluation — no restart required.")
    public ResponseEntity<RuleThreshold> update(@PathVariable String ruleName,
                                                 @RequestBody UpdateRequest body) {
        log.info("API request: update rule={} to value={}", ruleName, body.value());
        RuleThreshold updated = thresholdService.updateThreshold(ruleName, body.value());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset one rule or every rule to its thesis default",
               description = "Pass ?ruleName=LARGE_TRANSACTION to reset a single rule, " +
                             "or call without params to reset all four to their thesis defaults.")
    public ResponseEntity<Map<String, Object>> reset(@RequestParam(required = false) String ruleName) {
        List<RuleThreshold> result = thresholdService.resetToThesisDefault(ruleName);
        log.info("API request: reset rules (scope: {}), {} row(s) affected",
                ruleName == null ? "ALL" : ruleName, result.size());
        return ResponseEntity.ok(Map.of(
                "reset", result.size(),
                "rules", result.stream().map(RuleThreshold::getRuleName).toList()
        ));
    }

    /**
     * Request body for {@link #update(String, UpdateRequest)}.
     *
     * @param value numeric threshold; must be positive. Range bounds are enforced
     *              server-side by {@link RuleThresholdService}.
     */
    public record UpdateRequest(@NotNull @Positive BigDecimal value) { }
}
