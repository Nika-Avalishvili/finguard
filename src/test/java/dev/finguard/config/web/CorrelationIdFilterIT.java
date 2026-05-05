package dev.finguard.config.web;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the CorrelationIdFilter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("CorrelationIdFilter (request tracing)")
class CorrelationIdFilterIT {

    @Autowired private MockMvc mockMvc;

    @Nested
    @DisplayName("Correlation ID behavior")
    class CorrelationIdBehavior {

        @Test
        @DisplayName("Generates correlation ID when not provided by client")
        void generatesCorrelationId() throws Exception {
            MvcResult result = mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andReturn();

            String correlationId = result.getResponse().getHeader("X-Correlation-Id");
            assertThat(correlationId).isNotNull().isNotBlank().hasSize(8);
        }

        @Test
        @DisplayName("Uses client-provided correlation ID")
        void usesClientProvidedId() throws Exception {
            mockMvc.perform(get("/")
                            .header("X-Correlation-Id", "my-trace-123"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Correlation-Id", "my-trace-123"));
        }

        @Test
        @DisplayName("Different requests get different correlation IDs")
        void differentRequestsGetDifferentIds() throws Exception {
            MvcResult first = mockMvc.perform(get("/")).andReturn();
            MvcResult second = mockMvc.perform(get("/")).andReturn();

            String id1 = first.getResponse().getHeader("X-Correlation-Id");
            String id2 = second.getResponse().getHeader("X-Correlation-Id");

            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("Correlation ID is present on API endpoints too")
        void presentOnApiEndpoints() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Correlation-Id"));
        }
    }
}
