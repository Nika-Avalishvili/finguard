package dev.finguard.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures recent log entries from the {@code dev.finguard} package into an
 * in-memory ring buffer, making them available for the live activity log panel.
 *
 * <p>Audit B-12: lock-free implementation based on {@link ConcurrentLinkedDeque}.
 * The previous version serialised every appender call and every reader through
 * a single {@code synchronized (entries)} block — under a heavy batch job
 * (thousands of log events per second) this became a hot contention point that
 * also blocked async workers waiting to log. The only coarse-grained mutation is
 * the trim loop at the tail of {@link #append(ILoggingEvent)}, which may
 * briefly exceed {@link #MAX_ENTRIES} under concurrent writers but self-corrects.</p>
 */
@Component
public class RecentLogBuffer extends AppenderBase<ILoggingEvent> {

    private static final int MAX_ENTRIES = 200;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ConcurrentLinkedDeque<LogEntry> entries = new ConcurrentLinkedDeque<>();

    @PostConstruct
    public void registerAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.setContext(ctx);
        this.setName("recentLogBuffer");
        this.start();

        Logger finguardLogger = ctx.getLogger("dev.finguard");
        finguardLogger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
            LogEntry entry = new LogEntry(
                    TIME_FMT.format(Instant.ofEpochMilli(event.getTimeStamp())),
                    event.getLevel().toString(),
                    shortName(event.getLoggerName()),
                    event.getFormattedMessage()
            );
            entries.addLast(entry);

            // Best-effort trim. Under write bursts the size may briefly exceed
            // MAX_ENTRIES, but each subsequent writer also trims — the buffer
            // self-corrects within microseconds, and a few extra entries cause
            // no correctness issue (readers just see slightly more history).
            while (entries.size() > MAX_ENTRIES) {
                if (entries.pollFirst() == null) break;
            }
        }
    }

    public List<LogEntry> getRecent(int limit) {
        // ConcurrentLinkedDeque provides a weakly-consistent iterator — safe to
        // traverse without locking; may miss in-flight concurrent writes, which
        // is acceptable for an observability endpoint.
        List<LogEntry> all = new ArrayList<>(entries);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    public List<LogEntry> getSince(long sinceEpochMs) {
        return entries.stream()
                .filter(e -> e.epochMs() > sinceEpochMs)
                .toList();
    }

    private static String shortName(String loggerName) {
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 ? loggerName.substring(dot + 1) : loggerName;
    }

    public record LogEntry(String time, String level, String logger, String message, long epochMs) {
        public LogEntry(String time, String level, String logger, String message) {
            this(time, level, logger, message, System.currentTimeMillis());
        }
    }
}
