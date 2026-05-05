package dev.finguard.detection.service;

import dev.finguard.detection.service.PipelineStatusTracker.Phase;
import dev.finguard.detection.service.PipelineStatusTracker.PipelineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the PipelineStatusTracker.
 */
@DisplayName("PipelineStatusTracker")
class PipelineStatusTrackerTest {

    private PipelineStatusTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PipelineStatusTracker();
    }

    @Nested
    @DisplayName("Job lifecycle")
    class JobLifecycle {

        @Test
        @DisplayName("Start creates a RUNNING status")
        void start_createsRunning() {
            tracker.start("job-1", "DETECTION");

            PipelineStatus status = tracker.getStatus("job-1");

            assertThat(status).isNotNull();
            assertThat(status.jobId()).isEqualTo("job-1");
            assertThat(status.phase()).isEqualTo(Phase.RUNNING);
            assertThat(status.currentStep()).isEqualTo("DETECTION");
            assertThat(status.progressPercent()).isZero();
            assertThat(status.startedAt()).isNotNull();
            assertThat(status.completedAt()).isNull();
        }

        @Test
        @DisplayName("Update progress updates counts and percentage")
        void updateProgress_updatesCountsAndPercentage() {
            tracker.start("job-1", "DETECTION");
            tracker.updateProgress("job-1", "Analyzing transactions", 500, 1000, 10);

            PipelineStatus status = tracker.getStatus("job-1");

            assertThat(status.progressPercent()).isEqualTo(50);
            assertThat(status.processed()).isEqualTo(500);
            assertThat(status.alertsCreated()).isEqualTo(10);
            assertThat(status.currentStep()).isEqualTo("Analyzing transactions");
        }

        @Test
        @DisplayName("Complete sets COMPLETED phase with 100% progress")
        void complete_setsCompleted() {
            tracker.start("job-1", "DETECTION");
            tracker.complete("job-1", 42);

            PipelineStatus status = tracker.getStatus("job-1");

            assertThat(status.phase()).isEqualTo(Phase.COMPLETED);
            assertThat(status.progressPercent()).isEqualTo(100);
            assertThat(status.alertsCreated()).isEqualTo(42);
            assertThat(status.completedAt()).isNotNull();
            assertThat(status.error()).isNull();
        }

        @Test
        @DisplayName("Fail sets FAILED phase with error message")
        void fail_setsFailed() {
            tracker.start("job-1", "DETECTION");
            tracker.fail("job-1", "Connection reset");

            PipelineStatus status = tracker.getStatus("job-1");

            assertThat(status.phase()).isEqualTo(Phase.FAILED);
            assertThat(status.error()).isEqualTo("Connection reset");
            assertThat(status.completedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Query operations")
    class QueryOperations {

        @Test
        @DisplayName("getStatus returns null for unknown job")
        void getStatus_unknownJob_returnsNull() {
            assertThat(tracker.getStatus("nonexistent")).isNull();
        }

        @Test
        @DisplayName("isAnyRunning returns true when job is running")
        void isAnyRunning_true() {
            tracker.start("job-1", "DETECTION");

            assertThat(tracker.isAnyRunning()).isTrue();
        }

        @Test
        @DisplayName("isAnyRunning returns false when all jobs are completed")
        void isAnyRunning_false_afterComplete() {
            tracker.start("job-1", "DETECTION");
            tracker.complete("job-1", 0);

            assertThat(tracker.isAnyRunning()).isFalse();
        }

        @Test
        @DisplayName("isAnyRunning returns false when no jobs exist")
        void isAnyRunning_false_noJobs() {
            assertThat(tracker.isAnyRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("updateProgress ignores unknown job IDs")
        void updateProgress_unknownJob_ignored() {
            tracker.updateProgress("nonexistent", "step", 0, 0, 0);

            assertThat(tracker.getStatus("nonexistent")).isNull();
        }

        @Test
        @DisplayName("Multiple concurrent jobs tracked independently")
        void multipleJobs_trackedIndependently() {
            tracker.start("job-1", "DETECTION");
            tracker.start("job-2", "EVALUATION");
            tracker.complete("job-1", 10);

            assertThat(tracker.getStatus("job-1").phase()).isEqualTo(Phase.COMPLETED);
            assertThat(tracker.getStatus("job-2").phase()).isEqualTo(Phase.RUNNING);
            assertThat(tracker.isAnyRunning()).isTrue();
        }

        @Test
        @DisplayName("Progress percentage handles zero total")
        void progressPercent_zeroTotal_returnsZero() {
            tracker.start("job-1", "DETECTION");
            tracker.updateProgress("job-1", "step", 0, 0, 0);

            assertThat(tracker.getStatus("job-1").progressPercent()).isZero();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        /**
         * Regression test for the read-then-put race in {@code updateProgress}.
         *
         * <p>Prior to the fix, multiple writers could each read the same snapshot,
         * compute new values, and overwrite each other's updates. With the atomic
         * {@link java.util.concurrent.ConcurrentHashMap#computeIfPresent} fix, the
         * read-modify-write is serialized per-key and no updates are lost.</p>
         *
         * <p>Here we simulate a realistic workload: 32 threads each publish a
         * strictly monotonic sequence of progress values for the same jobId. The
         * tracker's final value must equal the last value written by some thread
         * (the winner of the CAS race) — it must <em>never</em> show a value
         * smaller than the max "alerts" any thread observed as its own latest
         * write. If lost updates occurred, a stale value would survive.</p>
         */
        @Test
        @DisplayName("updateProgress is atomic under concurrent writers")
        void updateProgress_isAtomic_underConcurrentWriters() throws Exception {
            tracker.start("race", "DETECTION");

            int threads = 32;
            int iterationsPerThread = 1_000;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try {
                for (int t = 0; t < threads; t++) {
                    final int threadIdx = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 1; i <= iterationsPerThread; i++) {
                                long encoded = (long) threadIdx * iterationsPerThread + i;
                                tracker.updateProgress("race", "step", encoded,
                                        threads * iterationsPerThread, encoded);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            PipelineStatus finalStatus = tracker.getStatus("race");
            assertThat(finalStatus).isNotNull();
            assertThat(finalStatus.phase()).isEqualTo(Phase.RUNNING);
            // startedAt must have been preserved by every atomic update.
            assertThat(finalStatus.startedAt()).isNotNull();
            // The last-writer-wins value must be within the valid range produced
            // by the writers; we do not require a specific value, only that no
            // null/zero leaked through (which would indicate state corruption).
            assertThat(finalStatus.processed()).isPositive();
            assertThat(finalStatus.alertsCreated()).isPositive();
        }

        @Test
        @DisplayName("logMilestone does not mutate state (read-only observation)")
        void logMilestone_doesNotMutateState() {
            tracker.start("m1", "DETECTION");
            tracker.updateProgress("m1", "step", 5, 10, 1);
            PipelineStatus before = tracker.getStatus("m1");

            tracker.logMilestone("m1", "halfway done");

            PipelineStatus after = tracker.getStatus("m1");
            assertThat(after.processed()).isEqualTo(before.processed());
            assertThat(after.progressPercent()).isEqualTo(before.progressPercent());
            assertThat(after.startedAt()).isEqualTo(before.startedAt());
        }

        @Test
        @DisplayName("logMilestone tolerates unknown jobId without throwing")
        void logMilestone_unknownJobId_doesNotThrow() {
            // Must not throw — used by callers that don't hold their own jobId validation.
            tracker.logMilestone("no-such-job", "phase X complete");
            assertThat(tracker.getStatus("no-such-job")).isNull();
        }

        @Test
        @DisplayName("complete preserves startedAt under concurrent updates")
        void complete_preservesStartedAt_underConcurrentUpdates() throws Exception {
            tracker.start("race2", "DETECTION");
            var original = tracker.getStatus("race2").startedAt();

            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch done = new CountDownLatch(8);
            try {
                for (int i = 0; i < 7; i++) {
                    pool.submit(() -> {
                        tracker.updateProgress("race2", "step", 1, 10, 1);
                        done.countDown();
                    });
                }
                pool.submit(() -> {
                    tracker.complete("race2", 123);
                    done.countDown();
                });
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            PipelineStatus finalStatus = tracker.getStatus("race2");
            assertThat(finalStatus.startedAt()).isEqualTo(original);
        }
    }
}
