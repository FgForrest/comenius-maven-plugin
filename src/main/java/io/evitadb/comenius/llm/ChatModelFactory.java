package io.evitadb.comenius.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

/**
 * Factory for creating LangChain4j ChatModel instances from URL and token.
 * Supports OpenAI-compatible endpoints (OpenAI, Groq, Ollama, DeepSeek, etc.) and Anthropic.
 */
public final class ChatModelFactory {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
	private static final double DEFAULT_TEMPERATURE = 0.3;
	private static final String DEFAULT_OPENAI_MODEL = "gpt-5.2-2025-12-11";
	private static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5-20250929";

	/**
	 * Supported LLM providers.
	 */
	public static final String PROVIDER_OPENAI = "openai";
	public static final String PROVIDER_ANTHROPIC = "anthropic";

	private ChatModelFactory() {
		// Utility class - prevent instantiation
	}

	/**
	 * Creates a ChatModel for the given provider, endpoint URL, and API token.
	 *
	 * @param provider  the provider name ("openai" or "anthropic")
	 * @param llmUrl    the base URL of the LLM endpoint (e.g., "https://api.openai.com/v1")
	 * @param llmToken  the API token/key for authentication (can be null for local endpoints)
	 * @param modelName the model name to use (can be null for defaults)
	 * @return configured ChatModel instance
	 * @throws IllegalArgumentException if provider is unknown or llmUrl is null/blank
	 */
	@Nonnull
	public static ChatModel create(
		@Nonnull String provider,
		@Nonnull String llmUrl,
		@Nullable String llmToken,
		@Nullable String modelName
	) {
		Objects.requireNonNull(provider, "provider must not be null");
		Objects.requireNonNull(llmUrl, "llmUrl must not be null");
		if (llmUrl.isBlank()) {
			throw new IllegalArgumentException("llmUrl must not be blank");
		}

		final String normalizedUrl = normalizeUrl(llmUrl);
		final String normalizedProvider = provider.toLowerCase().trim();

		return switch (normalizedProvider) {
			case PROVIDER_OPENAI -> createOpenAiModel(normalizedUrl, llmToken, modelName);
			case PROVIDER_ANTHROPIC -> createAnthropicModel(normalizedUrl, llmToken, modelName);
			default -> throw new IllegalArgumentException(
				"Unknown provider: " + provider + ". Supported providers: " + PROVIDER_OPENAI + ", " + PROVIDER_ANTHROPIC
			);
		};
	}

	/**
	 * Creates an OpenAI-compatible ChatModel.
	 *
	 * @param baseUrl   the base URL for the API
	 * @param apiKey    the API key (can be null for local endpoints)
	 * @param modelName the model name (null uses default)
	 * @return configured OpenAiChatModel instance
	 */
	@Nonnull
	private static ChatModel createOpenAiModel(
		@Nonnull String baseUrl,
		@Nullable String apiKey,
		@Nullable String modelName
	) {
		final OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
			.baseUrl(baseUrl)
			.modelName(modelName != null && !modelName.isBlank() ? modelName : DEFAULT_OPENAI_MODEL)
			.timeout(DEFAULT_TIMEOUT)
			.temperature(DEFAULT_TEMPERATURE)
			.logRequests(false)
			.logResponses(false);

		// Token is optional for local endpoints like Ollama
		if (apiKey != null && !apiKey.isBlank()) {
			builder.apiKey(apiKey);
		} else {
			// Some OpenAI-compatible providers require a placeholder
			builder.apiKey("none");
		}

		return builder.build();
	}

	/**
	 * Creates an Anthropic ChatModel.
	 *
	 * @param baseUrl   the base URL for the API
	 * @param apiKey    the API key
	 * @param modelName the model name (null uses default)
	 * @return configured AnthropicChatModel instance
	 */
	@Nonnull
	private static ChatModel createAnthropicModel(
		@Nonnull String baseUrl,
		@Nullable String apiKey,
		@Nullable String modelName
	) {
		final AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
			.baseUrl(baseUrl)
			.modelName(modelName != null && !modelName.isBlank() ? modelName : DEFAULT_ANTHROPIC_MODEL)
			.timeout(DEFAULT_TIMEOUT)
			.temperature(DEFAULT_TEMPERATURE)
			.logRequests(false)
			.logResponses(false);

		if (apiKey != null && !apiKey.isBlank()) {
			builder.apiKey(apiKey);
		}

		return builder.build();
	}

	/**
	 * Normalizes the URL by removing trailing slashes.
	 *
	 * @param url the URL to normalize
	 * @return normalized URL
	 */
	@Nonnull
	private static String normalizeUrl(@Nonnull String url) {
		String normalized = url.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}
}
