package dev.finguard.llm;

import dev.finguard.llm.LlmProviderConfigService.ActiveProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

/**
 * Builds and caches a {@link ChatClient} based on the currently active
 * LLM provider configuration stored in the database.
 *
 * <p>The cached client is invalidated whenever {@link #invalidate()} is called
 * (typically after the user saves a new provider configuration via the Settings UI).
 * On the next call to {@link #getCurrentClient()} a fresh client is built from the
 * updated database row.</p>
 *
 * <h3>Security</h3>
 * The plaintext API key is obtained from {@link LlmProviderConfigService#findActive()},
 * which decrypts it transparently. The key is used only to initialise the provider-specific
 * API client and is not stored on this class. API keys are never logged.
 *
 * <h3>Thread safety</h3>
 * Double-checked locking with {@code volatile} fields prevents two concurrent threads
 * from rebuilding the client simultaneously when the cache is empty.
 */
@Service
public class DynamicChatClientService {

    private static final Logger log = LoggerFactory.getLogger(DynamicChatClientService.class);

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    private final LlmProviderConfigService configService;

    private volatile ChatClient cachedClient;
    private volatile Long       cachedConfigId;
    /**
     * Audit B-9: when {@code true}, {@link #getCurrentClient()} can return
     * {@link #cachedClient} without consulting {@link LlmProviderConfigService}
     * — saving a DB round-trip and AES-GCM decryption per call. Set to
     * {@code false} by {@link #invalidate()}, which is invoked by
     * {@code LlmProviderConfigService} whenever the active config changes.
     */
    private volatile boolean cacheValid = false;

    public DynamicChatClientService(LlmProviderConfigService configService) {
        this.configService = configService;
    }

    /**
     * Returns the current {@link ChatClient}, rebuilding it if the active config has changed.
     *
     * @throws IllegalStateException if no active provider config exists in the database
     */
    public ChatClient getCurrentClient() {
        // Fast path — no DB hit, no decryption, no lock. Relies on
        // invalidate() flipping cacheValid=false on any provider change.
        if (cacheValid && cachedClient != null) {
            return cachedClient;
        }

        ActiveProviderConfig config = configService.findActive()
                .orElseThrow(() -> new IllegalStateException(
                        "No active LLM provider config found. Configure one via Settings."));

        // Slow path — double-checked locking prevents redundant rebuilds under concurrency
        synchronized (this) {
            if (!cacheValid || cachedClient == null || !config.id().equals(cachedConfigId)) {
                log.info("Building ChatClient for provider={}, model={}",
                        config.provider(), config.modelName());
                cachedClient   = buildClient(config);
                cachedConfigId = config.id();
                cacheValid     = true;
            }
        }
        return cachedClient;
    }

    /**
     * Forces the next {@link #getCurrentClient()} call to rebuild the client from the DB.
     * Call this after saving a new provider configuration.
     */
    public void invalidate() {
        synchronized (this) {
            cachedClient   = null;
            cachedConfigId = null;
            cacheValid     = false;
        }
        log.info("DynamicChatClientService cache invalidated — next call will rebuild from DB");
    }

    // ── Private builder methods ───────────────────────────────────────────

    private ChatClient buildClient(ActiveProviderConfig config) {
        return switch (config.provider()) {
            case OLLAMA    -> buildOllama(config);
            case ANTHROPIC -> buildAnthropic(config);
            case OPENAI    -> buildOpenAi(config);
            case DEEPSEEK  -> buildDeepSeek(config);
        };
    }

    private ChatClient buildOllama(ActiveProviderConfig config) {
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : "http://localhost:11434";
        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaOptions.builder()
                        .model(config.modelName())
                        .build())
                .build();
        return ChatClient.create(model);
    }

    private ChatClient buildAnthropic(ActiveProviderConfig config) {
        requireApiKey(config);
        AnthropicApi api = AnthropicApi.builder()
                .apiKey(config.apiKey())
                .build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(config.modelName())
                        .maxTokens(2048)
                        .build())
                .build();
        return ChatClient.create(model);
    }

    private ChatClient buildOpenAi(ActiveProviderConfig config) {
        requireApiKey(config);
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(config.apiKey())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.modelName())
                        .build())
                .build();
        return ChatClient.create(model);
    }

    private ChatClient buildDeepSeek(ActiveProviderConfig config) {
        requireApiKey(config);
        // DeepSeek exposes an OpenAI-compatible REST API — reuse the OpenAI client
        // with the DeepSeek base URL.
        //
        // maxTokens(1024): without an explicit cap the model can produce up to
        // ~8 192 tokens, and long generations are the #1 cause of CloudFront 504
        // Gateway Timeouts in front of DeepSeek's backend. Our explanations are
        // structured JSON of typically 300-600 tokens; 1 024 leaves comfortable
        // headroom while keeping per-request wall-clock predictable. If the LLM
        // ever truncates a response, raise to 1 536 — but never above 2 048
        // without lowering batch-concurrency proportionally.
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(config.apiKey())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.modelName())
                        .maxTokens(1024)
                        .build())
                .build();
        return ChatClient.create(model);
    }

    private void requireApiKey(ActiveProviderConfig config) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider " + config.provider() +
                    ". Enter your key in Settings → LLM Provider.");
        }
    }
}
