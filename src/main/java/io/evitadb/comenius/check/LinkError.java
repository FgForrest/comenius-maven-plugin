package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a broken link validation error in a Markdown document.
 * Used to report links that point to non-existent files or invalid anchors.
 *
 * @param sourceFile      the file containing the broken link
 * @param linkDestination the original link destination string from the markdown
 * @param targetPath      the resolved target file path (null if could not be resolved)
 * @param anchor          the anchor portion of the link without `#` (null if no anchor)
 * @param type            the type of link error encountered
 */
public record LinkError(
	@Nonnull Path sourceFile,
	@Nonnull String linkDestination,
	@Nullable Path targetPath,
	@Nullable String anchor,
	@Nonnull LinkErrorType type
) {

	/**
	 * Creates a new LinkError with validation.
	 */
	public LinkError {
		Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		Objects.requireNonNull(linkDestination, "linkDestination must not be null");
		Objects.requireNonNull(type, "type must not be null");
	}

	/**
	 * Types of link validation errors that can be detected.
	 */
	public enum LinkErrorType {
		/**
		 * The target file referenced by the link does not exist.
		 */
		FILE_NOT_FOUND,

		/**
		 * The anchor (heading reference) does not exist in the target document.
		 */
		ANCHOR_NOT_FOUND
	}
}
