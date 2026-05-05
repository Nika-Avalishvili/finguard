package dev.finguard.evaluation.runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link HeapGuardrail}.
 *
 * <p>We can't artificially fill the heap without making the test flaky, so the
 * thresholds are verified by <em>lowering</em> them below realistic live
 * readings. With warn=0.0001 / critical=0.0002 the real JVM memory easily
 * crosses both thresholds, letting us exercise every branch deterministically.</p>
 */
@DisplayName("HeapGuardrail")
class HeapGuardrailTest {

    private HeapGuardrail guardrail;

    @BeforeEach
    void setUp() {
        guardrail = new HeapGuardrail();
        // Defaults used where a test doesn't override.
        ReflectionTestUtils.setField(guardrail, "warnThreshold", 0.75);
        ReflectionTestUtils.setField(guardrail, "criticalThreshold", 0.90);
    }

    @Nested
    @DisplayName("peek() — non-throwing heap snapshot")
    class Peek {
        @Test
        @DisplayName("returns a sensible snapshot with used > 0 and max > 0")
        void returnsPopulatedStatus() {
            HeapGuardrail.HeapStatus s = guardrail.peek();
            assertThat(s.usedBytes()).isPositive();
            assertThat(s.maxBytes()).isPositive();
            assertThat(s.fraction()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("toString renders MB units + percentage")
        void toStringHumanReadable() {
            HeapGuardrail.HeapStatus s = guardrail.peek();
            assertThat(s.toString()).contains("MB").contains("%");
        }
    }

    @Nested
    @DisplayName("check() — branching on heap fraction")
    class Check {
        @Test
        @DisplayName("OK branch logs debug and returns when thresholds are far above actual usage")
        void okBranchDoesNotThrow() {
            // Default thresholds (0.75 / 0.90) — the test JVM's heap usage is
            // virtually guaranteed to be below 75%.
            assertThat(guardrail.check("test-ok")).isNotNull();
        }

        @Test
        @DisplayName("WARN branch logs warn and returns (does not throw) when only warn threshold exceeded")
        void warnBranchDoesNotThrow() {
            // Lower the warn threshold beneath typical JVM usage but keep
            // critical very high so only the warn branch fires.
            ReflectionTestUtils.setField(guardrail, "warnThreshold", 0.0001);
            ReflectionTestUtils.setField(guardrail, "criticalThreshold", 0.9999);
            HeapGuardrail.HeapStatus s = guardrail.check("test-warn");
            assertThat(s.fraction()).isGreaterThan(0.0001);
        }

        @Test
        @DisplayName("CRITICAL branch throws HeapCriticalException when GC can't bring heap below threshold")
        void criticalBranchThrows() {
            // Both thresholds absurdly low → even minimal JVM usage crosses
            // them. GC can't clean up below a threshold of 0.01% because the
            // JVM itself occupies more than that. So we expect the throw.
            ReflectionTestUtils.setField(guardrail, "warnThreshold", 0.0001);
            ReflectionTestUtils.setField(guardrail, "criticalThreshold", 0.0002);

            assertThatThrownBy(() -> guardrail.check("test-critical"))
                    .isInstanceOf(HeapGuardrail.HeapCriticalException.class)
                    .hasMessageContaining("Heap critical")
                    .hasMessageContaining("GC could not recover");
        }
    }
}
