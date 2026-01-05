package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Exception thrown when parsing a unified diff fails.
 * Contains details about the parsing error and the problematic input.
 */
public final class DiffParseException extends Exception {

	@Nonnull
	private final String rawDiff;
	private final int lineNumber;

	/**
	 * Creates a new DiffParseException.
	 *
	 * @param message    the error message describing the parsing failure
	 * @param rawDiff    the raw diff text that failed to parse
	 * @param lineNumber the line number where the error occurred (1-based)
	 */
	public DiffParseException(
		@Nonnull String message,
		@Nonnull String rawDiff,
		int lineNumber
	) {
		super(formatMessage(message, lineNumber));
		this.rawDiff = Objects.requireNonNull(rawDiff, "rawDiff must not be null");
		this.lineNumber = lineNumber;
	}

	/**
	 * Creates a new DiffParseException with a cause.
	 *
	 * @param message    the error message describing the parsing failure
	 * @param rawDiff    the raw diff text that failed to parse
	 * @param lineNumber the line number where the error occurred (1-based)
	 * @param cause      the underlying cause of the parsing failure
	 */
	public DiffParseException(
		@Nonnull String message,
		@Nonnull String rawDiff,
		int lineNumber,
		@Nonnull Throwable cause
	) {
		super(formatMessage(message, lineNumber), cause);
		this.rawDiff = Objects.requireNonNull(rawDiff, "rawDiff must not be null");
		this.lineNumber = lineNumber;
	}

	/**
	 * Formats the exception message with line number context.
	 *
	 * @param message    the base error message
	 * @param lineNumber the line number
	 * @return formatted message
	 */
	@Nonnull
	private static String formatMessage(@Nonnull String message, int lineNumber) {
		if (lineNumber > 0) {
			return message + " at line " + lineNumber;
		}
		return message;
	}

	/**
	 * Returns the raw diff text that failed to parse.
	 *
	 * @return the raw diff string
	 */
	@Nonnull
	public String getRawDiff() {
		return this.rawDiff;
	}

	/**
	 * Returns the line number where the parsing error occurred.
	 *
	 * @return line number (1-based), or 0 if unknown
	 */
	public int getLineNumber() {
		return this.lineNumber;
	}
}
