package dev.finguard.config.web;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

/**
 * Audit C-2: propagates the submitting thread's SLF4J {@link MDC} context into
 * the {@code @Async} worker thread so log lines from background jobs carry the
 * same {@code correlationId}/{@code tx} as the triggering HTTP request.
 *
 * <p>Without this decorator the MDC installed by
 * {@code CorrelationIdFilter} lives only on the servlet thread — as soon as an
 * {@code @Async} method hops to another thread the MDC is empty and every
 * long-running batch log line reads {@code correlationId=no-corr}.</p>
 *
 * <p>Registered on the bounded {@code ThreadPoolTaskExecutor} produced by
 * {@code AsyncConfig}. Every Runnable submitted through that executor is
 * wrapped: we snapshot the MDC at submission time, restore it before the
 * delegate runs, and {@link MDC#clear() clear} it afterwards so the worker
 * thread doesn't leak context into the next task it picks up from the queue.</p>
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
