package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a cross-language structural validation error: a translated document whose markup
 * shape no longer matches its English source in a way that plain heading-count or tag-balance
 * checking cannot see - a heading nested in the wrong language-switch scope, or a translation
 * that has quietly lost content while its `commit:` field still claims to be current.
 *
 * @param file    the translated file with the structural issue
 * @param type    the type of structural error encountered
 * @param message a human-readable description of the mismatch
 */
public record StructuralError(
	@Nonnull Path file,
	@Nonnull StructuralErrorType type,
	@Nonnull String message
) {

	/**
	 * Creates a new StructuralError with validation.
	 */
	public StructuralError {
		Objects.requireNonNull(file, "file must not be null");
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}

	/**
	 * Types of cross-language structural errors that can be detected.
	 */
	public enum StructuralErrorType {
		/**
		 * A heading sits inside a different chain of language-switch tags than its source
		 * counterpart at the same position - e.g. content routed to the wrong language audience.
		 */
		TAG_SCOPE_MISMATCH,

		/**
		 * The source and translation have a different number of headings, so positional
		 * comparison (tag-scope or otherwise) cannot be performed at all.
		 */
		HEADING_COUNT_MISMATCH,

		/**
		 * A closing tag has no matching open earlier in the same document - the translation's own
		 * tag structure is already broken, so any language-scope chain computed past that point
		 * would reflect the corruption rather than a real routing error. Reported instead of
		 * cascading into a run of confusing {@link #TAG_SCOPE_MISMATCH} findings.
		 */
		UNMATCHED_CLOSING_TAG,

		/**
		 * The translation's `commit:` field claims to match a source revision that is still
		 * current, yet the translation has fewer structural tokens (headings and tags) than that
		 * source - the signature of a translation run that silently dropped content.
		 */
		LIKELY_CONTENT_LOSS
	}
}
