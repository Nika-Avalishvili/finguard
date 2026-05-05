package dev.finguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstrapping class.
 *
 * <p>{@link EnableScheduling} activates the {@code @Scheduled} annotations used
 * by {@code MetricSnapshotRefresher} (audit D-3). All current schedulers use
 * {@code fixedDelay} rather than {@code fixedRate}, so runs can never overlap
 * even if a single execution exceeds the interval.</p>
 */
@SpringBootApplication
@EnableScheduling
public class FinGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinGuardApplication.class, args);
    }
}
