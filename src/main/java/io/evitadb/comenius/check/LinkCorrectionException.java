package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Exception thrown when link correction fails due to a structural mismatch
 * between source and translated documents.
 *
 * The most common cause is when the translated document has a different number
 * of headings than the source document, which prevents index-based anchor mapping.
 */
public final class LinkCorrectionException extends RuntimeException {

	@Nonnull
	private final Path sourceFile;
	@Nonnull
	private final Path translatedFile;
	private final int sourceHeadingCount;
	private final int translatedHeadingCount;

	/**
	 * Creates a new LinkCorrectionException for a heading count mismatch.
	 *
	 * @param sourceFile              the source (original language) file
	 * @param translatedFile          the translated file
	 * @param sourceHeadingCount      number of headings in source
	 * @param translatedHeadingCount  number of headings in translated
	 */
	public LinkCorrectionException(
		@Nonnull Path sourceFile,
		@Nonnull Path translatedFile,
		int sourceHeadingCount,
		int translatedHeadingCount
	) {
		super(buildMessage(sourceFile, translatedFile, sourceHeadingCount, translatedHeadingCount));
		this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		this.translatedFile = Objects.requireNonNull(translatedFile, "translatedFile must not be null");
		this.sourceHeadingCount = sourceHeadingCount;
		this.translatedHeadingCount = translatedHeadingCount;
	}

	@Nonnull
	private static String buildMessage(
		@Nonnull Path sourceFile,
		@Nonnull Path translatedFile,
		int sourceHeadingCount,
		int translatedHeadingCount
	) {
		return String.format(
			"Heading count mismatch: source '%s' has %d headings, " +
				"translated '%s' has %d headings. Cannot map anchors by index.",
			sourceFile.getFileName(),
			sourceHeadingCount,
			translatedFile.getFileName(),
			translatedHeadingCount
		);
	}

	/**
	 * Returns the source (original language) file path.
	 *
	 * @return source file path
	 */
	@Nonnull
	public Path getSourceFile() {
		return this.sourceFile;
	}

	/**
	 * Returns the translated file path.
	 *
	 * @return translated file path
	 */
	@Nonnull
	public Path getTranslatedFile() {
		return this.translatedFile;
	}

	/**
	 * Returns the number of headings in the source document.
	 *
	 * @return source heading count
	 */
	public int getSourceHeadingCount() {
		return this.sourceHeadingCount;
	}

	/**
	 * Returns the number of headings in the translated document.
	 *
	 * @return translated heading count
	 */
	public int getTranslatedHeadingCount() {
		return this.translatedHeadingCount;
	}
}
