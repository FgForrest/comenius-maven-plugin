package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a section of a markdown document split at heading boundaries.
 * Each section includes its content, heading metadata, and a content hash
 * for change detection in incremental translation.
 *
 * @param index       zero-based position in the document
 * @param headingLevel 0 for intro (before first heading), 1-6 for heading level
 * @param headingText  the heading text, or null for intro section
 * @param content      full text of the section including the heading line
 * @param contentHash  SHA-256 hash of normalized content for change detection
 */
public record DocumentSection(
	int index,
	int headingLevel,
	@Nullable String headingText,
	@Nonnull String content,
	@Nonnull String contentHash
) {

	/**
	 * Creates a DocumentSection with validation.
	 *
	 * @param index        zero-based position in the document
	 * @param headingLevel 0 for intro, 1-6 for heading level
	 * @param headingText  the heading text, or null for intro
	 * @param content      full text including heading line
	 * @param contentHash  SHA-256 hash of normalized content
	 */
	public DocumentSection {
		if (index < 0) {
			throw new IllegalArgumentException("index must be non-negative");
		}
		if (headingLevel < 0 || headingLevel > 6) {
			throw new IllegalArgumentException("headingLevel must be 0-6");
		}
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
		if (contentHash == null) {
			throw new IllegalArgumentException("contentHash must not be null");
		}
	}

	/**
	 * Returns whether this is an intro section (content before the first heading).
	 *
	 * @return true if this is an intro section
	 */
	public boolean isIntro() {
		return this.headingLevel == 0;
	}
}
