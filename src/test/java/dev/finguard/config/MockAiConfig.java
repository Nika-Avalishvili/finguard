package dev.finguard.config;

import dev.finguard.llm.DynamicChatClientService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test configuration that provides mock Spring AI beans.
 *
 * <p>Integration tests don't need Ollama or any LLM running.
 * This configuration registers mock beans so Spring context boots
 * without failing on missing AI infrastructure.</p>
 *
 * <p>The corresponding auto-configurations are disabled via properties
 * in {@code src/test/resources/application.yml}:
 * <ul>
 *   <li>{@code spring.ai.ollama.chat.enabled=false}</li>
 *   <li>{@code spring.ai.ollama.embedding.enabled=false}</li>
 *   <li>{@code spring.ai.vectorstore.pgvector.enabled=false}</li>
 * </ul>
 *
 * <p>Import this alongside {@link TestContainersConfig} in integration tests:</p>
 * <pre>
 * {@literal @}Import({TestContainersConfig.class, MockAiConfig.class})
 * </pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockAiConfig {

    @Bean
    ChatModel chatModel() {
        return mock(ChatModel.class);
    }

    @Bean
    ChatClient.Builder chatClientBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    @Bean
    @Primary
    DynamicChatClientService dynamicChatClientService() {
        DynamicChatClientService service = mock(DynamicChatClientService.class);
        when(service.getCurrentClient()).thenReturn(mock(ChatClient.class));
        return service;
    }

    @Bean
    EmbeddingModel embeddingModel() {
        return mock(EmbeddingModel.class);
    }

    @Bean
    VectorStore vectorStore() {
        return mock(VectorStore.class);
    }
}
