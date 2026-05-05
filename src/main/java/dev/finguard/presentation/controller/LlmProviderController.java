package dev.finguard.presentation.controller;

import dev.finguard.domain.enums.LlmProvider;
import dev.finguard.domain.model.LlmProviderConfig;
import dev.finguard.llm.DynamicChatClientService;
import dev.finguard.llm.LlmProviderConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing the active LLM provider configuration.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>API keys are <strong>never</strong> returned in any response body —
 *       only a {@code hasApiKey} boolean flag is exposed to the frontend.</li>
 *   <li>All business logic (encryption, transaction management) lives in
 *       {@link LlmProviderConfigService}; this controller handles only HTTP concerns.</li>
 *   <li>{@link ActivateRequest} is validated with Jakarta Bean Validation.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/llm-provider")
@Tag(name = "LLM Provider", description = "Runtime LLM provider configuration")
public class LlmProviderController {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderController.class);

    private final LlmProviderConfigService configService;
    private final DynamicChatClientService dynamicChatClientService;

    public LlmProviderController(LlmProviderConfigService configService,
                                  DynamicChatClientService dynamicChatClientService) {
        this.configService            = configService;
        this.dynamicChatClientService = dynamicChatClientService;
    }

    /** List all stored provider configs. API key is never included. */
    @GetMapping
    @Operation(summary = "List all LLM provider configs")
    public ResponseEntity<List<ProviderConfigDto>> listAll() {
        return ResponseEntity.ok(
                configService.findAll().stream().map(ProviderConfigDto::from).toList());
    }

    /** Get the currently active provider config. API key is never included. */
    @GetMapping("/active")
    @Operation(summary = "Get active LLM provider config")
    public ResponseEntity<ProviderConfigDto> getActive() {
        return configService.findAll().stream()
                .filter(LlmProviderConfig::isActive)
                .map(ProviderConfigDto::from)
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Save (upsert) a provider config and activate it immediately.
     *
     * <p>Deactivates all other configs and activates the specified one atomically.
     * If {@code apiKey} is blank, the key stored for this provider (if any) is preserved.</p>
     */
    @PutMapping("/activate")
    @Operation(summary = "Save and activate a provider config",
               description = "Creates or updates the config for the specified provider and makes " +
                             "it active atomically. The LLM client cache is invalidated immediately.")
    public ResponseEntity<ProviderConfigDto> activate(@Valid @RequestBody ActivateRequest req) {
        LlmProviderConfig saved = configService.activate(
                req.provider(), req.apiKey(), req.baseUrl(), req.modelName());

        dynamicChatClientService.invalidate();

        log.info("LLM provider switched via REST: provider={}, model={}", req.provider(), req.modelName());
        return ResponseEntity.ok(ProviderConfigDto.from(saved));
    }

    /** Returns available providers with their metadata. */
    @GetMapping("/providers")
    @Operation(summary = "List available LLM providers")
    public ResponseEntity<List<Map<String, Object>>> providers() {
        List<Map<String, Object>> list = List.of(
            providerMeta(LlmProvider.OLLAMA,    "llama3.2",               true,  "http://localhost:11434"),
            providerMeta(LlmProvider.ANTHROPIC, "claude-sonnet-4-20250514", false, null),
            providerMeta(LlmProvider.OPENAI,    "gpt-4o",                 false, null),
            providerMeta(LlmProvider.DEEPSEEK,  "deepseek-chat",          false, null)
        );
        return ResponseEntity.ok(list);
    }

    private Map<String, Object> providerMeta(LlmProvider p, String defaultModel,
                                              boolean local, String defaultBaseUrl) {
        return Map.of(
            "provider",       p.name(),
            "displayName",    p.getDisplayName(),
            "local",          local,
            "defaultModel",   defaultModel,
            "defaultBaseUrl", defaultBaseUrl != null ? defaultBaseUrl : ""
        );
    }

    // ── Request / Response records ───────────────────────────────────────

    /**
     * Activate request. Validated by Jakarta Bean Validation.
     * {@code apiKey} is intentionally nullable — blank means "keep stored key unchanged".
     */
    public record ActivateRequest(
            @NotNull(message = "provider is required")
            LlmProvider provider,

            @Size(max = 500, message = "apiKey must not exceed 500 characters")
            String apiKey,

            @Size(max = 200, message = "baseUrl must not exceed 200 characters")
            String baseUrl,

            @NotBlank(message = "modelName is required")
            @Size(max = 100, message = "modelName must not exceed 100 characters")
            String modelName
    ) {}

    /**
     * Safe read-only view of a provider config.
     * API key is <strong>never</strong> included — only a boolean presence flag.
     */
    public record ProviderConfigDto(
            Long    id,
            String  provider,
            String  displayName,
            String  baseUrl,
            String  modelName,
            boolean active,
            boolean hasApiKey
    ) {
        static ProviderConfigDto from(LlmProviderConfig c) {
            return new ProviderConfigDto(
                    c.getId(),
                    c.getProvider().name(),
                    c.getProvider().getDisplayName(),
                    c.getBaseUrl(),
                    c.getModelName(),
                    c.isActive(),
                    c.getApiKey() != null && !c.getApiKey().isBlank());
        }
    }
}
