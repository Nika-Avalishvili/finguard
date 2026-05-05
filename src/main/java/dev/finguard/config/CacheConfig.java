package dev.finguard.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configures Caffeine-backed caching for the application.
 *
 * <p>Caches are used to reduce redundant calls to expensive services:</p>
 * <ul>
 *   <li><b>rag-context</b> — caches vector store similarity search results
 *       for the same alert signals, avoiding repeated pgvector queries</li>
 *   <li><b>dashboard-stats</b> — caches dashboard aggregate queries
 *       (counts, charts) that don't change between page refreshes</li>
 * </ul>
 *
 * <p>Cache entries expire after a short TTL to prevent stale data.
 * Maximum cache size limits memory footprint.</p>
 */
@Configuration
@EnableCaching
@ConditionalOnProperty(name = "finguard.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    /**
     * Creates a Caffeine-backed cache manager with sensible defaults.
     *
     * <p>All caches share the same eviction policy:
     * <ul>
     *   <li>Max 500 entries per cache</li>
     *   <li>10-minute TTL after write</li>
     *   <li>5-minute TTL after last access</li>
     * </ul>
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "rag-context", "dashboard-stats");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .recordStats()
        );
        return cacheManager;
    }
}
