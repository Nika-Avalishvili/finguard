package dev.finguard.llm;

import dev.finguard.domain.enums.LlmProvider;
import dev.finguard.domain.model.LlmProviderConfig;
import dev.finguard.domain.repository.LlmProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Application service for managing {@link LlmProviderConfig} records.
 *
 * <p>This is the single entry point for all LLM provider configuration operations.
 * Controllers never access the repository directly — they go through this service.</p>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>API keys are <strong>encrypted</strong> via {@link ApiKeyEncryptor} before being
 *       written to the database and <strong>decrypted</strong> only when building the live
 *       {@link org.springframework.ai.chat.client.ChatClient} in {@link DynamicChatClientService}.</li>
 *   <li>The plaintext key is never returned to the presentation layer.</li>
 *   <li>API keys are never written to log output.</li>
 * </ul>
 *
 * <h3>Transaction safety</h3>
 * The {@link #activate} method runs the full deactivate-all + upsert sequence inside a
 * single database transaction, eliminating the window where no active config exists.
 */
@Service
@Transactional(readOnly = true)
public class LlmProviderConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfigService.class);

    private final LlmProviderConfigRepository repository;
    private final ApiKeyEncryptor encryptor;

    public LlmProviderConfigService(LlmProviderConfigRepository repository,
                                    ApiKeyEncryptor encryptor) {
        this.repository = repository;
        this.encryptor  = encryptor;
    }

    // ── Read operations ────────────────────────────────────────────────────

    /**
     * Returns the active config with its API key decrypted.
     * Used by {@link DynamicChatClientService} only — never send this to HTTP responses.
     */
    public Optional<ActiveProviderConfig> findActive() {
        return repository.findByActiveTrue().map(this::toActiveConfig);
    }

    /** All stored configs ordered by ID. API key field contains the encrypted value. */
    public List<LlmProviderConfig> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    /**
     * Provider name → entity map for the Settings page Thymeleaf model.
     * Template can check {@code configByProvider['ANTHROPIC'].apiKey != null} for the
     * "Key stored" badge without the key value ever reaching the browser.
     */
    public Map<String, LlmProviderConfig> findAllByProviderName() {
        return repository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(
                        c -> c.getProvider().name(),
                        c -> c,
                        (a, b) -> a));
    }

    /**
     * Returns model names, base URLs, and API key presence flags for all stored providers.
     * Serialised to JSON and injected into the Settings page so the JS can pre-populate
     * every provider tab on load — not just the active one.
     */
    public Map<String, StoredProviderInfo> storedInfoByProviderName() {
        return repository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(
                        c -> c.getProvider().name(),
                        c -> new StoredProviderInfo(
                                c.getModelName(),
                                c.getBaseUrl() != null ? c.getBaseUrl() : "",
                                StringUtils.hasText(c.getApiKey())),
                        (a, b) -> a));
    }

    // ── Write operation ────────────────────────────────────────────────────

    /**
     * Atomically deactivates every config and activates (or creates) the one for
     * the requested provider. Runs in a single transaction.
     *
     * <p>If {@code rawApiKey} is blank the key already stored for this provider
     * (if any) is preserved, so the user can change only the model without re-entering.</p>
     *
     * @return the saved entity (API key field contains the encrypted value)
     */
    @Transactional
    public LlmProviderConfig activate(LlmProvider provider, String rawApiKey,
                                      String baseUrl, String modelName) {
        // deactivateAll() has @Transactional(REQUIRED) and joins this transaction,
        // so the bulk UPDATE and the subsequent save commit atomically.
        repository.deactivateAll();

        LlmProviderConfig config = repository.findByProvider(provider)
                .orElseGet(LlmProviderConfig::new);

        config.setProvider(provider);
        config.setDisplayName(provider.getDisplayName());
        config.setModelName(modelName);
        config.setBaseUrl(baseUrl);
        config.setActive(true);

        if (StringUtils.hasText(rawApiKey)) {
            config.setApiKey(encryptor.encrypt(rawApiKey));
            log.info("API key updated for provider={}", provider);
        } else if (!StringUtils.hasText(config.getApiKey())) {
            log.debug("No API key provided for provider={} and none was previously stored", provider);
        } else {
            log.debug("API key preserved (unchanged) for provider={}", provider);
        }

        LlmProviderConfig saved = repository.save(config);
        log.info("LLM provider activated: provider={}, model={}", provider, modelName);
        return saved;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ActiveProviderConfig toActiveConfig(LlmProviderConfig c) {
        return new ActiveProviderConfig(
                c.getId(),
                c.getProvider(),
                c.getApiKey() != null ? encryptor.decrypt(c.getApiKey()) : null,
                c.getBaseUrl(),
                c.getModelName());
    }

    // ── Value objects ──────────────────────────────────────────────────────

    /**
     * Decrypted view of the active config — used internally by {@link DynamicChatClientService}.
     * <strong>Never serialize this record into an HTTP response.</strong>
     */
    public record ActiveProviderConfig(
            Long id,
            LlmProvider provider,
            String apiKey,       // decrypted plaintext — internal use only
            String baseUrl,
            String modelName
    ) {}

    /**
     * Minimal provider info for the Settings UI — no API key value, only a presence flag.
     */
    public record StoredProviderInfo(
            String modelName,
            String baseUrl,
            boolean hasApiKey
    ) {}
}
