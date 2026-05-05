package dev.finguard.config;

import dev.finguard.config.web.MdcTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configures async execution for long-running pipeline operations.
 *
 * <p><b>Audit C-1 &amp; C-2:</b> replaces the previously unbounded
 * {@code Executors.newVirtualThreadPerTaskExecutor()} with a bounded
 * {@link ThreadPoolTaskExecutor}. Virtual threads are still used as the
 * worker threads — we just cap how many may be queued at once so a burst
 * of 100k+ async tasks cannot exhaust the JDBC connection pool or blow
 * the heap. {@code CallerRunsPolicy} provides natural back-pressure:
 * when the queue saturates, the submitting thread runs the task itself,
 * slowing producers instead of OOM-ing.</p>
 *
 * <p>The {@link MdcTaskDecorator} propagates SLF4J MDC (correlation IDs,
 * tx tags) from the submitting thread into the async worker thread so
 * long-running batch jobs have traceable log lines.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** Core pool size — always-alive workers. */
    private static final int CORE_POOL_SIZE = 8;
    /** Max pool size — additional workers spun up if queue fills. */
    private static final int MAX_POOL_SIZE = 64;
    /** Bounded queue capacity before CallerRunsPolicy kicks in as back-pressure. */
    private static final int QUEUE_CAPACITY = 10_000;

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("finguard-async-");
        executor.setThreadFactory(virtualThreadFactory());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.initialize();
        log.info("Async executor initialised: core={}, max={}, queue={}, virtualThreads=yes, mdc=propagated",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
        return executor;
    }

    /**
     * Virtual-thread factory compatible with {@link ThreadPoolTaskExecutor}.
     * Keeps Loom's lightweight concurrency for I/O-bound async work while
     * respecting the thread pool's bounds.
     */
    private ThreadFactory virtualThreadFactory() {
        AtomicLong seq = new AtomicLong();
        return r -> Thread.ofVirtual()
                .name("finguard-async-", seq.incrementAndGet())
                .unstarted(r);
    }

    /** Expose as a bean via the decorator helper so tests can swap it out if needed. */
    private TaskDecorator mdcPropagatingDecorator() {
        return new MdcTaskDecorator();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Async method {} failed: {}", method.getName(), throwable.getMessage(), throwable);
    }

    // Kept for backwards compatibility — some callers may still want a plain
    // virtual-thread-per-task executor (e.g., fan-out inside a single request).
    // NOT the @Async executor.
    static Executor unboundedVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Bounded virtual-thread-backed executor for intra-request fan-out (e.g. running the
     * rule engine and the ML model concurrently inside one transaction's analysis).
     *
     * <p>Distinct from {@link #getAsyncExecutor()} (which serves {@code @Async} methods).
     * This executor is intended for short, high-throughput parallel sub-tasks; the bound
     * stops a hot loop from spawning unbounded virtual threads when many transactions
     * are processed in parallel.</p>
     *
     * <p>Sized at {@code 32 core / 128 max / 2 048 queue} with {@code CallerRunsPolicy}
     * for back-pressure — the calling thread executes the task itself when saturated,
     * which is exactly what you want during a long detection sweep.</p>
     */
    @Bean(name = "intraRequestExecutor")
    public Executor intraRequestExecutor() {
        AtomicLong seq = new AtomicLong();
        ThreadFactory factory = r -> Thread.ofVirtual()
                .name("finguard-intra-", seq.incrementAndGet())
                .unstarted(r);
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                32, 128,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2048),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("Intra-request executor initialised: core=32, max=128, queue=2048, virtualThreads=yes");
        return exec;
    }
}
