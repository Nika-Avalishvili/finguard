package dev.finguard.config;

import dev.finguard.dashboard.service.DashboardService;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.MetricSnapshotRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying the caching layer.
 *
 * <p>Tests that:
 * <ul>
 *   <li>Cache manager is configured with the expected cache names</li>
 *   <li>Dashboard stats are cached on subsequent calls</li>
 *   <li>Cache uses Caffeine as the backing implementation</li>
 * </ul>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Caching (Caffeine integration)")
class CacheIT {

    @Autowired private CacheManager cacheManager;
    @Autowired private DashboardService dashboardService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MetricSnapshotRepository metricSnapshotRepository;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name ->
                cacheManager.getCache(name).clear());
        // Drop any metric snapshots left by the scheduled refresher — they'd
        // otherwise short-circuit DashboardService.getSystemOverview() past
        // the live transaction count these tests assert on.
        metricSnapshotRepository.deleteAll();
    }

    @Nested
    @DisplayName("Cache configuration")
    class CacheConfiguration {

        @Test
        @DisplayName("Cache manager has expected cache names")
        void hasCacheNames() {
            assertThat(cacheManager.getCacheNames())
                    .contains("rag-context", "dashboard-stats");
        }

        @Test
        @DisplayName("Cache manager uses Caffeine implementation")
        void usesCaffeine() {
            assertThat(cacheManager.getClass().getName())
                    .contains("CaffeineCacheManager");
        }
    }

    @Nested
    @DisplayName("Dashboard stats caching")
    class DashboardStatsCaching {

        @Test
        @DisplayName("getSystemOverview is cached on second call")
        void systemOverview_isCached() {
            DashboardService.SystemOverview first = dashboardService.getSystemOverview();
            DashboardService.SystemOverview second = dashboardService.getSystemOverview();

            // Both calls should return the same cached instance
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("getDetectionAnalytics is cached on second call")
        void detectionAnalytics_isCached() {
            DashboardService.DetectionAnalytics first = dashboardService.getDetectionAnalytics();
            DashboardService.DetectionAnalytics second = dashboardService.getDetectionAnalytics();

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("Cache reflects data changes after eviction")
        void cacheReflectsChangesAfterEviction() {
            DashboardService.SystemOverview before = dashboardService.getSystemOverview();
            long countBefore = before.totalTransactions();

            // Add a transaction
            Transaction tx = new Transaction();
            tx.setAmount(new BigDecimal("100.00"));
            tx.setTransactionType(TransactionType.PAYMENT);
            tx.setSenderAccount("CACHE-SENDER-" + System.nanoTime());
            tx.setReceiverAccount("CACHE-RECEIVER-" + System.nanoTime());
            tx.setTimestamp(LocalDateTime.now());
            tx.setDatasetSource(DatasetSource.PAYSIM);
            tx.setExternalId("CACHE-TEST-" + System.nanoTime());
            transactionRepository.save(tx);

            // Without eviction, cache still returns old count
            DashboardService.SystemOverview cached = dashboardService.getSystemOverview();
            assertThat(cached.totalTransactions()).isEqualTo(countBefore);

            // After eviction, new count is returned
            cacheManager.getCache("dashboard-stats").clear();
            DashboardService.SystemOverview fresh = dashboardService.getSystemOverview();
            assertThat(fresh.totalTransactions()).isGreaterThan(countBefore);
        }
    }
}
