package io.evitadb.comenius.model;

import javax.annotation.Nonnull;

/**
 * Thrown when a translated document's heading structure does not match the source document.
 * This indicates the LLM added, removed, or changed heading levels during translation,
 * which would break section-based incremental translation.
 */
public class HeadingStructureMismatchException extends Exception {

	@Nonnull
	private final String expectedStructure;
	@Nonnull
	private final String actualStructure;

	/**
	 * Creates a new exception with details about the mismatch.
	 *
	 * @param message           description of the mismatch
	 * @param expectedStructure the heading structure from the source document
	 * @param actualStructure   the heading structure from the translated document
	 */
	public HeadingStructureMismatchException(
		@Nonnull String message,
		@Nonnull String expectedStructure,
		@Nonnull String actualStructure
	) {
		super(message + " — expected: " + expectedStructure + ", actual: " + actualStructure);
		this.expectedStructure = expectedStructure;
		this.actualStructure = actualStructure;
	}

	/**
	 * Returns the expected heading structure from the source document.
	 *
	 * @return expected structure description
	 */
	@Nonnull
	public String getExpectedStructure() {
		return this.expectedStructure;
	}

	/**
	 * Returns the actual heading structure from the translated document.
	 *
	 * @return actual structure description
	 */
	@Nonnull
	public String getActualStructure() {
		return this.actualStructure;
	}
}
