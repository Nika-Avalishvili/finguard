package dev.finguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the OpenAPI 3.0 specification for the FinGuard REST API.
 *
 * <p>This configuration feeds into SpringDoc, which auto-generates
 * the Swagger UI at {@code /swagger-ui.html} and the OpenAPI spec
 * at {@code /v3/api-docs}.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finguardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinGuard API")
                        .description("""
                                REST API for the FinGuard LLM-Augmented Transaction Anomaly
                                Detection and Explanation System.

                                **Core capabilities:**
                                - CSV dataset ingestion with automatic feature engineering
                                - Rule-based and ML-based anomaly detection pipeline
                                - LLM-powered fraud explanation generation (direct and RAG-augmented)
                                - Experiment evaluation framework with CAKR scoring
                                - CSV data export for external analysis
                                """)
                        .version("0.1.0")
                        .contact(new Contact()
                                .name("Nika Avalishvili")
                                .email("avalishvili.nick@gmail.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development server")
                ));
    }
}
