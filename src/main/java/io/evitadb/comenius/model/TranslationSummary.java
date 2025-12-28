package io.evitadb.comenius.model;

import javax.annotation.Nonnull;

/**
 * Immutable record containing summary statistics for a translation batch.
 * Used to report the overall results of a translation run.
 *
 * @param successCount  number of successfully translated files
 * @param failedCount   number of files that failed to translate
 * @param skippedCount  number of files skipped (up-to-date or uncommitted)
 * @param inputTokens   total number of input tokens used across all translations
 * @param outputTokens  total number of output tokens generated across all translations
 */
public record TranslationSummary(
	int successCount,
	int failedCount,
	int skippedCount,
	long inputTokens,
	long outputTokens
) {

	/**
	 * Creates an empty summary with all counts at zero.
	 *
	 * @return an empty TranslationSummary
	 */
	@Nonnull
	public static TranslationSummary empty() {
		return new TranslationSummary(0, 0, 0, 0, 0);
	}

	/**
	 * Returns the total number of files processed (success + failed + skipped).
	 *
	 * @return total file count
	 */
	public int getTotalCount() {
		return this.successCount + this.failedCount + this.skippedCount;
	}

	/**
	 * Returns true if all translations were successful (no failures).
	 *
	 * @return true if no failures occurred
	 */
	public boolean isAllSuccessful() {
		return this.failedCount == 0;
	}

	/**
	 * Returns true if any translations failed.
	 *
	 * @return true if at least one failure occurred
	 */
	public boolean hasFailures() {
		return this.failedCount > 0;
	}

	/**
	 * Creates a new summary by adding the counts from another summary.
	 *
	 * @param other the summary to add
	 * @return a new combined TranslationSummary
	 */
	@Nonnull
	public TranslationSummary add(@Nonnull TranslationSummary other) {
		return new TranslationSummary(
			this.successCount + other.successCount,
			this.failedCount + other.failedCount,
			this.skippedCount + other.skippedCount,
			this.inputTokens + other.inputTokens,
			this.outputTokens + other.outputTokens
		);
	}

	/**
	 * Creates a new summary with an incremented success count.
	 *
	 * @param inputTokens  input tokens for the successful translation
	 * @param outputTokens output tokens for the successful translation
	 * @return a new TranslationSummary with updated counts
	 */
	@Nonnull
	public TranslationSummary withSuccess(long inputTokens, long outputTokens) {
		return new TranslationSummary(
			this.successCount + 1,
			this.failedCount,
			this.skippedCount,
			this.inputTokens + inputTokens,
			this.outputTokens + outputTokens
		);
	}

	/**
	 * Creates a new summary with an incremented failure count.
	 *
	 * @return a new TranslationSummary with updated counts
	 */
	@Nonnull
	public TranslationSummary withFailure() {
		return new TranslationSummary(
			this.successCount,
			this.failedCount + 1,
			this.skippedCount,
			this.inputTokens,
			this.outputTokens
		);
	}

	/**
	 * Creates a new summary with an incremented skipped count.
	 *
	 * @return a new TranslationSummary with updated counts
	 */
	@Nonnull
	public TranslationSummary withSkipped() {
		return new TranslationSummary(
			this.successCount,
			this.failedCount,
			this.skippedCount + 1,
			this.inputTokens,
			this.outputTokens
		);
	}

	@Override
	public String toString() {
		return String.format(
			"TranslationSummary[success=%d, failed=%d, skipped=%d, tokens=%d/%d]",
			this.successCount, this.failedCount, this.skippedCount,
			this.inputTokens, this.outputTokens
		);
	}
}
