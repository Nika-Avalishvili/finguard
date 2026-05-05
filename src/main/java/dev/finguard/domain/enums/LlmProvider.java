package dev.finguard.domain.enums;

public enum LlmProvider {

    OLLAMA("Local Ollama", true),
    ANTHROPIC("Anthropic Claude", false),
    OPENAI("OpenAI", false),
    DEEPSEEK("DeepSeek", false);

    private final String displayName;
    /** Whether this provider uses a local base URL instead of an API key. */
    private final boolean local;

    LlmProvider(String displayName, boolean local) {
        this.displayName = displayName;
        this.local = local;
    }

    public String getDisplayName() { return displayName; }
    public boolean isLocal() { return local; }
}
