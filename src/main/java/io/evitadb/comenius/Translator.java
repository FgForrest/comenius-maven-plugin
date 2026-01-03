package io.evitadb.comenius;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translator uses a LangChain4J ChatLanguageModel to translate text according to provided instructions
 * and target locale. Supports both simple text translation and structured TranslationJob-based translation.
 */
public class Translator {

	private final ChatModel model;
	private final PromptLoader promptLoader;
	@Nullable
	private final Executor executor;
	private final AtomicLong inputTokenCount = new AtomicLong(0);
	private final AtomicLong outputTokenCount = new AtomicLong(0);

	/**
	 * Create a Translator using provided ChatModel, PromptLoader, and Executor.
	 * The model is expected to perform the actual LLM call; in tests it can be mocked.
	 * The executor is used for async operations; if null, ForkJoinPool.commonPool() is used.
	 *
	 * @param model        non-null chat model to use
	 * @param promptLoader non-null prompt loader for loading templates
	 * @param executor     executor for async operations; may be null to use common pool
	 */
	public Translator(
		@Nonnull ChatModel model,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor
	) {
		this.model = Objects.requireNonNull(model, "model must not be null");
		this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader must not be null");
		this.executor = executor;
	}

	/**
	 * Create a Translator using provided ChatLanguageModel and PromptLoader.
	 * Uses ForkJoinPool.commonPool() for async operations.
	 *
	 * @param model        non-null chat model to use
	 * @param promptLoader non-null prompt loader for loading templates
	 */
	public Translator(@Nonnull ChatModel model, @Nonnull PromptLoader promptLoader) {
		this(model, promptLoader, null);
	}

	/**
	 * Create a Translator using provided ChatModel with a default PromptLoader.
	 * Uses ForkJoinPool.commonPool() for async operations.
	 *
	 * @param model non-null chat model to use
	 */
	public Translator(@Nonnull ChatModel model) {
		this(model, new PromptLoader(), null);
	}

	/**
	 * Translates using a TranslationJob that provides all context and builds prompts polymorphically.
	 * The job type (new or incremental) determines which prompt templates are used.
	 *
	 * @param job the translation job containing source content and metadata
	 * @return CompletionStage with TranslationResult containing the translated content or error
	 */
	@Nonnull
	public CompletionStage<TranslationResult> translate(@Nonnull TranslationJob job) {
		Objects.requireNonNull(job, "job must not be null");

		// Build prompts using polymorphism - job knows which templates to use
		final String systemPrompt = job.buildSystemPrompt(this.promptLoader);
		final String userPrompt = job.buildUserPrompt(this.promptLoader);

		final Executor effectiveExecutor = this.executor != null ? this.executor : ForkJoinPool.commonPool();
		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final List<ChatMessage> messages = List.of(
					SystemMessage.from(systemPrompt),
					UserMessage.from(userPrompt)
				);

				final ChatResponse response = this.model.chat(messages);
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				final TokenUsage tokenUsage = response.tokenUsage();

				final long inputTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
				final long outputTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

				this.inputTokenCount.addAndGet(inputTokens);
				this.outputTokenCount.addAndGet(outputTokens);

				final String translatedText = response.aiMessage().text();
				return TranslationResult.success(job, translatedText, inputTokens, outputTokens, elapsedMillis);

			} catch (Exception e) {
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return TranslationResult.failure(job, e.getMessage(), elapsedMillis);
			}
		}, effectiveExecutor);
	}

	/**
	 * Translates the given text according to the provided instructions into the target locale.
	 * This is the legacy method for simple text translation.
	 *
	 * Each call is stateless and does not reuse any memory; messages are created fresh per invocation.
	 *
	 * @param instructions system-level instructions for the LLM; may be null or blank
	 * @param text         text to translate; must not be null or blank
	 * @param locale       target locale; must not be null
	 * @return CompletionStage with translated text
	 * @throws IllegalArgumentException when text is null/blank or locale is null
	 */
	@Nonnull
	public CompletionStage<String> translate(
		@Nullable String instructions,
		@Nonnull String text,
		@Nonnull Locale locale
	) {
		Objects.requireNonNull(text, "text must not be null");
		Objects.requireNonNull(locale, "locale must not be null");

		// Build messages for a stateless call
		final List<ChatMessage> messages = new ArrayList<>(8);
		if (instructions != null && !instructions.isBlank()) {
			messages.add(SystemMessage.from(instructions.trim()));
		}
		messages.add(
			SystemMessage.from(
				"Translate the following text to " + locale.toLanguageTag() + ":"
			)
		);
		messages.add(UserMessage.from(text));

		// Offload to a separate thread as a minimal async behavior; in real usage, model may block
		final Executor effectiveExecutor = this.executor != null ? this.executor : ForkJoinPool.commonPool();
		return CompletableFuture.supplyAsync(() -> {
			final ChatResponse response = this.model.chat(messages);
			final TokenUsage tokenUsage = response.tokenUsage();
			if (tokenUsage != null) {
				this.inputTokenCount.addAndGet(tokenUsage.inputTokenCount());
				this.outputTokenCount.addAndGet(tokenUsage.outputTokenCount());
			}
			return response.aiMessage().text();
		}, effectiveExecutor);
	}

	/**
	 * Returns the total input tokens used across all translations.
	 *
	 * @return total input token count
	 */
	public long getInputTokenCount() {
		return this.inputTokenCount.get();
	}

	/**
	 * Returns the total output tokens generated across all translations.
	 *
	 * @return total output token count
	 */
	public long getOutputTokenCount() {
		return this.outputTokenCount.get();
	}

	/**
	 * Resets the token counters to zero.
	 */
	public void resetTokenCounts() {
		this.inputTokenCount.set(0);
		this.outputTokenCount.set(0);
	}
}
