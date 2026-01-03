package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of link correction for a single translated file.
 * Contains the corrected content, statistics about corrections made,
 * and any errors encountered during the correction process.
 *
 * @param targetFile              the translated file that was processed
 * @param correctedContent        the content with corrected links
 * @param assetCorrections        number of asset link corrections made
 * @param anchorCorrections       number of anchor corrections made
 * @param frontMatterCorrections  number of front matter field corrections made
 * @param errors                  list of error messages encountered during correction
 */
public record LinkCorrectionResult(
	@Nonnull Path targetFile,
	@Nonnull String correctedContent,
	int assetCorrections,
	int anchorCorrections,
	int frontMatterCorrections,
	@Nonnull List<String> errors
) {

	/**
	 * Compact constructor with validation.
	 */
	public LinkCorrectionResult {
		Objects.requireNonNull(targetFile, "targetFile must not be null");
		Objects.requireNonNull(correctedContent, "correctedContent must not be null");
		Objects.requireNonNull(errors, "errors must not be null");
		errors = List.copyOf(errors);
	}

	/**
	 * Returns true if no errors occurred during correction.
	 *
	 * @return true if correction was successful
	 */
	public boolean isSuccess() {
		return this.errors.isEmpty();
	}

	/**
	 * Returns total number of corrections made (assets + anchors + front matter).
	 *
	 * @return total correction count
	 */
	public int totalCorrections() {
		return this.assetCorrections + this.anchorCorrections + this.frontMatterCorrections;
	}

	/**
	 * Creates a successful result with no corrections.
	 *
	 * @param targetFile the target file
	 * @param content    the unchanged content
	 * @return result with zero corrections
	 */
	@Nonnull
	public static LinkCorrectionResult unchanged(
		@Nonnull Path targetFile,
		@Nonnull String content
	) {
		return new LinkCorrectionResult(targetFile, content, 0, 0, 0, List.of());
	}

	/**
	 * Creates a failed result with the given error message.
	 *
	 * @param targetFile the target file
	 * @param content    the original content (unchanged due to error)
	 * @param error      the error message
	 * @return result with error
	 */
	@Nonnull
	public static LinkCorrectionResult failed(
		@Nonnull Path targetFile,
		@Nonnull String content,
		@Nonnull String error
	) {
		return new LinkCorrectionResult(targetFile, content, 0, 0, 0, List.of(error));
	}
}
