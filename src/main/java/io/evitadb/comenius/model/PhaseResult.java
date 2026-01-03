package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal record for carrying intermediate translation phase results.
 * Used to chain front matter and body translation phases together.
 *
 * The translation process is split into two phases:
 * - Phase 1: Translate front matter fields (simple text values)
 * - Phase 2: Translate article body (markdown content)
 *
 * This record accumulates results from both phases and can convert
 * to a final {@link TranslationResult} that is compatible with
 * existing parsing logic in {@link FrontMatterTranslationHelper}.
 *
 * @param job              the original translation job
 * @param translatedFields map of translated front matter field values (from Phase 1)
 * @param translatedBody   the translated body content (from Phase 2, null if not yet executed)
 * @param inputTokens      accumulated input tokens from completed phases
 * @param outputTokens     accumulated output tokens from completed phases
 * @param elapsedMillis    accumulated elapsed time from completed phases
 * @param success          whether all completed phases succeeded
 * @param errorMessage     error message if any phase failed
 */
public record PhaseResult(
	@Nonnull TranslationJob job,
	@Nonnull Map<String, String> translatedFields,
	@Nullable String translatedBody,
	long inputTokens,
	long outputTokens,
	long elapsedMillis,
	boolean success,
	@Nullable String errorMessage
) {

	/**
	 * Creates an initial result for starting the phase chain.
	 *
	 * @param job the translation job to process
	 * @return initial PhaseResult with empty fields and no body
	 */
	@Nonnull
	public static PhaseResult initial(@Nonnull TranslationJob job) {
		Objects.requireNonNull(job, "job must not be null");
		return new PhaseResult(job, new LinkedHashMap<>(), null, 0, 0, 0, true, null);
	}

	/**
	 * Creates a result after successful front matter translation.
	 *
	 * @param translatedFields the translated front matter field values
	 * @param inputTokens      input tokens used for this phase
	 * @param outputTokens     output tokens generated for this phase
	 * @param elapsedMillis    time elapsed for this phase
	 * @return new PhaseResult with front matter results accumulated
	 */
	@Nonnull
	public PhaseResult withFrontMatter(
		@Nonnull Map<String, String> translatedFields,
		long inputTokens,
		long outputTokens,
		long elapsedMillis
	) {
		Objects.requireNonNull(translatedFields, "translatedFields must not be null");
		return new PhaseResult(
			this.job,
			translatedFields,
			null,
			this.inputTokens + inputTokens,
			this.outputTokens + outputTokens,
			this.elapsedMillis + elapsedMillis,
			true,
			null
		);
	}

	/**
	 * Creates a result after successful body translation.
	 *
	 * @param translatedBody the translated body content
	 * @param inputTokens    input tokens used for this phase
	 * @param outputTokens   output tokens generated for this phase
	 * @param elapsedMillis  time elapsed for this phase
	 * @return new PhaseResult with body result accumulated
	 */
	@Nonnull
	public PhaseResult withBody(
		@Nonnull String translatedBody,
		long inputTokens,
		long outputTokens,
		long elapsedMillis
	) {
		Objects.requireNonNull(translatedBody, "translatedBody must not be null");
		return new PhaseResult(
			this.job,
			this.translatedFields,
			translatedBody,
			this.inputTokens + inputTokens,
			this.outputTokens + outputTokens,
			this.elapsedMillis + elapsedMillis,
			true,
			null
		);
	}

	/**
	 * Creates a failure result with phase identification in error message.
	 *
	 * @param phase         the phase that failed ("FRONT_MATTER" or "BODY")
	 * @param errorMessage  the error message describing the failure
	 * @param elapsedMillis time elapsed before the failure
	 * @return new PhaseResult marked as failed
	 */
	@Nonnull
	public PhaseResult withFailure(
		@Nonnull String phase,
		@Nonnull String errorMessage,
		long elapsedMillis
	) {
		Objects.requireNonNull(phase, "phase must not be null");
		Objects.requireNonNull(errorMessage, "errorMessage must not be null");
		return new PhaseResult(
			this.job,
			this.translatedFields,
			this.translatedBody,
			this.inputTokens,
			this.outputTokens,
			this.elapsedMillis + elapsedMillis,
			false,
			"[" + phase + "] " + errorMessage
		);
	}

	/**
	 * Converts this phase result to a final TranslationResult.
	 * Combines translated fields and body into the expected format
	 * that is compatible with {@link FrontMatterTranslationHelper#parseTranslatedFields}
	 * and {@link FrontMatterTranslationHelper#extractBodyFromResponse}.
	 *
	 * @return TranslationResult for use by TranslationExecutor
	 */
	@Nonnull
	public TranslationResult toTranslationResult() {
		if (!this.success) {
			return TranslationResult.failure(this.job, this.errorMessage, this.elapsedMillis);
		}

		final String combinedContent = formatCombinedContent();
		return TranslationResult.success(
			this.job,
			combinedContent,
			this.inputTokens,
			this.outputTokens,
			this.elapsedMillis
		);
	}

	/**
	 * Formats the combined content with front matter field tags and body.
	 * Uses the same format as {@link FrontMatterTranslationHelper#formatFieldsForPrompt}
	 * to ensure compatibility with existing parsing logic.
	 *
	 * @return combined content string with field tags and body
	 */
	@Nonnull
	private String formatCombinedContent() {
		final StringBuilder sb = new StringBuilder();

		// Add front matter fields in tagged format
		if (!this.translatedFields.isEmpty()) {
			for (final Map.Entry<String, String> entry : this.translatedFields.entrySet()) {
				sb.append("[[").append(entry.getKey()).append("]]\n");
				sb.append(entry.getValue()).append("\n");
				sb.append("[[/").append(entry.getKey()).append("]]\n\n");
			}
		}

		// Add body content
		if (this.translatedBody != null) {
			sb.append(this.translatedBody);
		}

		return sb.toString().trim();
	}
}
