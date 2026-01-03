package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable record representing the result of a translation operation.
 * Contains the original job, the translated content (if successful), and status information.
 *
 * @param job               the original translation job
 * @param translatedContent the translated content (null if failed)
 * @param success           whether the translation succeeded
 * @param errorMessage      error message if translation failed (null if successful)
 * @param inputTokens       number of input tokens used for this translation
 * @param outputTokens      number of output tokens generated for this translation
 * @param elapsedMillis     time elapsed for the API call in milliseconds
 */
public record TranslationResult(
	@Nonnull TranslationJob job,
	@Nullable String translatedContent,
	boolean success,
	@Nullable String errorMessage,
	long inputTokens,
	long outputTokens,
	long elapsedMillis
) {

	/**
	 * Creates a successful translation result.
	 *
	 * @param job               the original translation job
	 * @param translatedContent the translated content
	 * @param inputTokens       number of input tokens used
	 * @param outputTokens      number of output tokens generated
	 * @param elapsedMillis     time elapsed for the API call in milliseconds
	 * @return a successful TranslationResult
	 */
	@Nonnull
	public static TranslationResult success(
		@Nonnull TranslationJob job,
		@Nonnull String translatedContent,
		long inputTokens,
		long outputTokens,
		long elapsedMillis
	) {
		return new TranslationResult(job, translatedContent, true, null, inputTokens, outputTokens, elapsedMillis);
	}

	/**
	 * Creates a failed translation result.
	 *
	 * @param job           the original translation job
	 * @param errorMessage  the error message describing the failure
	 * @param elapsedMillis time elapsed before the failure in milliseconds
	 * @return a failed TranslationResult
	 */
	@Nonnull
	public static TranslationResult failure(
		@Nonnull TranslationJob job,
		@Nonnull String errorMessage,
		long elapsedMillis
	) {
		return new TranslationResult(job, null, false, errorMessage, 0, 0, elapsedMillis);
	}

	/**
	 * Returns the type of translation (NEW or UPDATE).
	 *
	 * @return the translation type from the job
	 */
	@Nonnull
	public String getType() {
		return this.job.getType();
	}
}
