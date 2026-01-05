package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Exception thrown when applying a unified diff to content fails.
 * Typically occurs when context lines don't match the expected content
 * or when line ranges are out of bounds.
 */
public final class DiffApplicationException extends Exception {

	@Nonnull
	private final DiffHunk failedHunk;
	private final int hunkIndex;
	@Nullable
	private final String expectedContext;
	@Nullable
	private final String actualContext;

	/**
	 * Creates a new DiffApplicationException.
	 *
	 * @param message         the error message describing the failure
	 * @param failedHunk      the hunk that failed to apply
	 * @param hunkIndex       the index of the failed hunk (0-based)
	 * @param expectedContext the expected context line (from diff), may be null
	 * @param actualContext   the actual content found at that position, may be null
	 */
	public DiffApplicationException(
		@Nonnull String message,
		@Nonnull DiffHunk failedHunk,
		int hunkIndex,
		@Nullable String expectedContext,
		@Nullable String actualContext
	) {
		super(formatMessage(message, hunkIndex, expectedContext, actualContext));
		this.failedHunk = Objects.requireNonNull(failedHunk, "failedHunk must not be null");
		this.hunkIndex = hunkIndex;
		this.expectedContext = expectedContext;
		this.actualContext = actualContext;
	}

	/**
	 * Formats the exception message with context details.
	 *
	 * @param message         the base error message
	 * @param hunkIndex       the hunk index
	 * @param expectedContext expected context
	 * @param actualContext   actual context
	 * @return formatted message
	 */
	@Nonnull
	private static String formatMessage(
		@Nonnull String message,
		int hunkIndex,
		@Nullable String expectedContext,
		@Nullable String actualContext
	) {
		final StringBuilder sb = new StringBuilder(message);
		sb.append(" (hunk ").append(hunkIndex + 1).append(")");

		if (expectedContext != null && actualContext != null) {
			sb.append("\nExpected: '").append(truncate(expectedContext, 50)).append("'");
			sb.append("\nActual:   '").append(truncate(actualContext, 50)).append("'");
		}

		return sb.toString();
	}

	/**
	 * Truncates a string if it exceeds the maximum length.
	 *
	 * @param s      the string to truncate
	 * @param maxLen maximum length
	 * @return truncated string with ellipsis if needed
	 */
	@Nonnull
	private static String truncate(@Nonnull String s, int maxLen) {
		if (s.length() <= maxLen) {
			return s;
		}
		return s.substring(0, maxLen - 3) + "...";
	}

	/**
	 * Returns the hunk that failed to apply.
	 *
	 * @return the failed DiffHunk
	 */
	@Nonnull
	public DiffHunk getFailedHunk() {
		return this.failedHunk;
	}

	/**
	 * Returns the index of the failed hunk (0-based).
	 *
	 * @return hunk index
	 */
	public int getHunkIndex() {
		return this.hunkIndex;
	}

	/**
	 * Returns the expected context line from the diff.
	 *
	 * @return expected context, or null if not a context mismatch
	 */
	@Nullable
	public String getExpectedContext() {
		return this.expectedContext;
	}

	/**
	 * Returns the actual content found at the expected position.
	 *
	 * @return actual context, or null if not a context mismatch
	 */
	@Nullable
	public String getActualContext() {
		return this.actualContext;
	}
}
