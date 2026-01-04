package io.evitadb.comenius.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a ChatModel with permanent failure detection for coordinated shutdown.
 *
 * LangChain4j already handles retry logic with exponential backoff internally.
 * This client adds:
 * - Detection of permanent failures (authentication, invalid request, etc.)
 * - Cross-thread shutdown coordination via atomic flags
 * - Fast-fail for subsequent calls after permanent failure
 *
 * Exception handling:
 * - {@link NonRetriableException}: Sets shutdown flag and propagates (triggers pool shutdown)
 * - Other exceptions: Propagated as-is (individual job failure, continue processing)
 */
public final class LlmClient {

	@Nonnull
	private final ChatModel model;
	@Nonnull
	private final AtomicBoolean permanentFailure = new AtomicBoolean(false);
	@Nonnull
	private final AtomicReference<NonRetriableException> failureCause = new AtomicReference<>();

	/**
	 * Creates an LLM client wrapping the given ChatModel.
	 *
	 * @param model the underlying chat model
	 */
	public LlmClient(@Nonnull ChatModel model) {
		this.model = Objects.requireNonNull(model, "model must not be null");
	}

	/**
	 * Sends messages to the LLM.
	 *
	 * - Fast-fails if shutdown flag is set (another thread hit permanent failure)
	 * - On {@link NonRetriableException}: sets shutdown flag and propagates
	 * - Other exceptions: propagated as-is (LangChain4j already handled retries)
	 *
	 * @param messages the messages to send
	 * @return the chat response
	 * @throws NonRetriableException if a permanent failure occurs
	 * @throws LangChain4jException for other LLM errors
	 */
	@Nonnull
	public ChatResponse chat(@Nonnull List<ChatMessage> messages) {
		Objects.requireNonNull(messages, "messages must not be null");

		// Fast-fail if permanent failure already occurred
		if (this.permanentFailure.get()) {
			final NonRetriableException cause = this.failureCause.get();
			throw new LangChain4jException(
				"LLM client shutdown due to previous permanent failure" +
					(cause != null ? ": " + cause.getMessage() : ""),
				cause
			);
		}

		try {
			return this.model.chat(messages);
		} catch (NonRetriableException e) {
			// Set shutdown flag and propagate
			this.failureCause.set(e);
			this.permanentFailure.set(true);
			throw e;
		}
		// Other exceptions (including RetriableException after retry exhaustion)
		// are propagated as-is - individual job failure, continue with others
	}

	/**
	 * Checks if a permanent failure has occurred.
	 * When true, all subsequent calls to {@link #chat(List)} will fail immediately.
	 *
	 * @return true if permanent failure, false otherwise
	 */
	public boolean hasPermanentFailure() {
		return this.permanentFailure.get();
	}

	/**
	 * Returns the cause of the permanent failure, if any.
	 *
	 * @return the permanent failure exception or null
	 */
	@Nullable
	public NonRetriableException getFailureCause() {
		return this.failureCause.get();
	}

	/**
	 * Signals shutdown to abort pending operations.
	 * Called by TranslationExecutor when it detects a permanent failure.
	 * Sets the permanent failure flag without storing a cause.
	 */
	public void signalShutdown() {
		this.permanentFailure.set(true);
	}

	/**
	 * Signals shutdown with a specific cause.
	 *
	 * @param cause the permanent failure that triggered shutdown
	 */
	public void signalShutdown(@Nonnull NonRetriableException cause) {
		this.failureCause.set(cause);
		this.permanentFailure.set(true);
	}
}
