package dev.finguard.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying Micrometer metrics and Prometheus endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Observability (Micrometer + Prometheus)")
class ObservabilityIT {

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MockMvc mockMvc;

    @Nested
    @DisplayName("Custom metrics registration")
    class CustomMetrics {

        @Test
        @DisplayName("Detection alerts counter is registered")
        void alertsCreatedCounter_isRegistered() {
            Counter counter = meterRegistry.find("finguard.detection.alerts.created").counter();
            assertThat(counter).isNotNull();
        }

        @Test
        @DisplayName("Detection pipeline timer is registered")
        void detectionPipelineTimer_isRegistered() {
            Timer timer = meterRegistry.find("finguard.detection.pipeline.duration").timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("LLM call counter is registered")
        void llmCallCounter_isRegistered() {
            Counter counter = meterRegistry.find("finguard.llm.calls.total").counter();
            assertThat(counter).isNotNull();
        }

        @Test
        @DisplayName("LLM failure counter is registered")
        void llmFailureCounter_isRegistered() {
            Counter counter = meterRegistry.find("finguard.llm.calls.failures").counter();
            assertThat(counter).isNotNull();
        }

        @Test
        @DisplayName("LLM call timer is registered")
        void llmCallTimer_isRegistered() {
            Timer timer = meterRegistry.find("finguard.llm.call.duration").timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("RAG search counter is registered")
        void ragSearchCounter_isRegistered() {
            Counter counter = meterRegistry.find("finguard.rag.searches.total").counter();
            assertThat(counter).isNotNull();
        }

        @Test
        @DisplayName("RAG search timer is registered")
        void ragSearchTimer_isRegistered() {
            Timer timer = meterRegistry.find("finguard.rag.search.duration").timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("Ingestion counter is registered")
        void transactionsImportedCounter_isRegistered() {
            Counter counter = meterRegistry.find("finguard.ingestion.transactions.imported").counter();
            assertThat(counter).isNotNull();
        }
    }

    @Nested
    @DisplayName("Actuator endpoints")
    class ActuatorEndpoints {

        @Test
        @DisplayName("Prometheus endpoint is accessible and returns metrics")
        void prometheusEndpoint_isAccessible() throws Exception {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("finguard_detection_alerts_total")));
        }

        @Test
        @DisplayName("Health endpoint is accessible")
        void healthEndpoint_isAccessible() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Metrics endpoint lists all available metrics")
        void metricsEndpoint_isAccessible() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("finguard.llm.calls.total")));
        }

        @Test
        @DisplayName("Caches endpoint is accessible")
        void cachesEndpoint_isAccessible() throws Exception {
            mockMvc.perform(get("/actuator/caches"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("rag-context")));
        }
    }
}
