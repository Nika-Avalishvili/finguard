package dev.finguard.detection.rule;

import dev.finguard.config.exception.BadRequestException;
import dev.finguard.domain.model.RuleThreshold;
import dev.finguard.domain.repository.RuleThresholdRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RuleThresholdService} — covers cache warming, the
 * validation guards against out-of-range values, and the reset/update paths.
 */
@DisplayName("RuleThresholdService")
class RuleThresholdServiceTest {

    private RuleThresholdRepository repo;
    private RuleThresholdService service;

    @BeforeEach
    void setUp() {
        repo = mock(RuleThresholdRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                new RuleThreshold("LARGE_TRANSACTION",       new BigDecimal("1000000")),
                new RuleThreshold("NEW_RECEIVER_HIGH_VALUE", new BigDecimal("500000")),
                new RuleThreshold("STRUCTURING",             new BigDecimal("10000")),
                new RuleThreshold("RAPID_VELOCITY",          new BigDecimal("3"))
        ));
        service = new RuleThresholdService(repo);
        service.warmCache();
    }

    @Nested
    @DisplayName("warmCache() + getCurrentValue()")
    class ReadPath {

        @Test
        @DisplayName("reads seeded values from the repo into the cache on startup")
        void seedsFromRepo() {
            assertThat(service.getCurrentValue(RuleMetadata.LARGE_TRANSACTION))
                    .isEqualByComparingTo("1000000");
            assertThat(service.getCurrentValue(RuleMetadata.STRUCTURING))
                    .isEqualByComparingTo("10000");
        }

        @Test
        @DisplayName("falls back to RuleMetadata thesis default when row is missing")
        void fallsBackToThesisDefault() {
            // Fresh service with an empty repo — cache stays empty for RAPID_VELOCITY.
            RuleThresholdRepository emptyRepo = mock(RuleThresholdRepository.class);
            when(emptyRepo.findAll()).thenReturn(List.of());
            RuleThresholdService coldService = new RuleThresholdService(emptyRepo);
            coldService.warmCache();

            assertThat(coldService.getCurrentValue(RuleMetadata.RAPID_VELOCITY))
                    .isEqualByComparingTo(RuleMetadata.RAPID_VELOCITY.getThesisDefault());
        }

        @Test
        @DisplayName("getCurrentIntValue returns integer view of the BigDecimal")
        void intAccessorTruncates() {
            assertThat(service.getCurrentIntValue(RuleMetadata.RAPID_VELOCITY)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("updateThreshold()")
    class WritePath {

        @Test
        @DisplayName("persists the new value and refreshes the cache")
        void updatesCacheAndDb() {
            when(repo.findById("LARGE_TRANSACTION"))
                    .thenReturn(Optional.of(new RuleThreshold("LARGE_TRANSACTION", new BigDecimal("1000000"))));
            when(repo.save(any(RuleThreshold.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.updateThreshold("LARGE_TRANSACTION", new BigDecimal("2000000"));

            assertThat(service.getCurrentValue(RuleMetadata.LARGE_TRANSACTION))
                    .isEqualByComparingTo("2000000");
            verify(repo, times(1)).save(any(RuleThreshold.class));
        }

        @Test
        @DisplayName("rejects an unknown rule name")
        void rejectsUnknownRule() {
            assertThatThrownBy(() -> service.updateThreshold("BOGUS", new BigDecimal("1")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unknown rule");
        }

        @Test
        @DisplayName("rejects a zero / negative value")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> service.updateThreshold("LARGE_TRANSACTION", BigDecimal.ZERO))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("accepts (with warn log) a value outside the industry range — the range is advisory, not enforced")
        void acceptsOutsideIndustryRange() {
            when(repo.findById("LARGE_TRANSACTION"))
                    .thenReturn(Optional.of(new RuleThreshold("LARGE_TRANSACTION", new BigDecimal("1000000"))));
            when(repo.save(any(RuleThreshold.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Well above LARGE_TRANSACTION's published industry max of 10 000 000
            // — should still be accepted; thesis research sometimes needs values
            // outside typical deployment ranges.
            service.updateThreshold("LARGE_TRANSACTION", new BigDecimal("50000000"));

            assertThat(service.getCurrentValue(RuleMetadata.LARGE_TRANSACTION))
                    .isEqualByComparingTo("50000000");
        }
    }

    @Nested
    @DisplayName("resetToThesisDefault()")
    class Reset {

        @Test
        @DisplayName("single-rule reset writes the thesis default")
        void resetsOne() {
            when(repo.findById("LARGE_TRANSACTION"))
                    .thenReturn(Optional.of(new RuleThreshold("LARGE_TRANSACTION", new BigDecimal("2000000"))));
            when(repo.save(any(RuleThreshold.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.resetToThesisDefault("LARGE_TRANSACTION");

            assertThat(service.getCurrentValue(RuleMetadata.LARGE_TRANSACTION))
                    .isEqualByComparingTo(RuleMetadata.LARGE_TRANSACTION.getThesisDefault());
        }

        @Test
        @DisplayName("null/blank scope resets every rule")
        void resetsAll() {
            when(repo.findById(any(String.class)))
                    .thenAnswer(inv -> Optional.of(
                            new RuleThreshold(inv.getArgument(0), BigDecimal.ONE)));
            when(repo.save(any(RuleThreshold.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            List<RuleThreshold> updated = service.resetToThesisDefault(null);

            assertThat(updated).hasSize(RuleMetadata.values().length);
        }
    }

    @Nested
    @DisplayName("listAll()")
    class ListAll {

        @Test
        @DisplayName("returns one ThresholdView per RuleMetadata entry")
        void returnsCompleteList() {
            List<RuleThresholdService.ThresholdView> views = service.listAll();
            assertThat(views).hasSize(RuleMetadata.values().length);
            assertThat(views.get(0).ruleName()).isEqualTo(RuleMetadata.values()[0].name());
        }

        @Test
        @DisplayName("ThresholdView flags isAtThesisDefault correctly")
        void atDefaultFlag() {
            // Service was seeded with values equal to thesis defaults in setUp.
            List<RuleThresholdService.ThresholdView> views = service.listAll();
            assertThat(views).allMatch(RuleThresholdService.ThresholdView::isAtThesisDefault);
        }
    }
}
