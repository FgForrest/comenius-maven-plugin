package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Represents a chunk of a split document for separate translation.
 * Large documents are split at heading boundaries to stay within LLM context limits.
 *
 * @param index        zero-based index of this chunk in the document
 * @param startOffset  byte offset where this chunk starts in the original content
 * @param endOffset    byte offset where this chunk ends in the original content (exclusive)
 * @param content      the actual content of this chunk
 * @param headingLevel the heading level (1-6) at which this chunk starts, or 0 for intro content
 * @param headingText  the heading text at which this chunk starts, or null for intro content
 */
public record DocumentChunk(
	int index,
	int startOffset,
	int endOffset,
	@Nonnull String content,
	int headingLevel,
	@Nullable String headingText
) {

	/**
	 * Creates a DocumentChunk with validation.
	 *
	 * @param index        zero-based index of this chunk
	 * @param startOffset  byte offset where this chunk starts
	 * @param endOffset    byte offset where this chunk ends
	 * @param content      the chunk content
	 * @param headingLevel the heading level (0-6)
	 * @param headingText  the heading text, or null for intro content
	 */
	public DocumentChunk {
		if (index < 0) {
			throw new IllegalArgumentException("index must be non-negative");
		}
		if (startOffset < 0) {
			throw new IllegalArgumentException("startOffset must be non-negative");
		}
		if (endOffset < startOffset) {
			throw new IllegalArgumentException("endOffset must be >= startOffset");
		}
		if (headingLevel < 0 || headingLevel > 6) {
			throw new IllegalArgumentException("headingLevel must be 0-6");
		}
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
	}

	/**
	 * Creates an intro chunk (content before the first heading).
	 *
	 * @param content     the intro content
	 * @param startOffset byte offset where the intro starts
	 * @param endOffset   byte offset where the intro ends
	 * @return a new DocumentChunk representing intro content
	 */
	@Nonnull
	public static DocumentChunk intro(@Nonnull String content, int startOffset, int endOffset) {
		return new DocumentChunk(0, startOffset, endOffset, content, 0, null);
	}

	/**
	 * Creates a chunk starting at a heading.
	 *
	 * @param index        zero-based chunk index
	 * @param content      the chunk content
	 * @param startOffset  byte offset where the chunk starts
	 * @param endOffset    byte offset where the chunk ends
	 * @param headingLevel the heading level (1-6)
	 * @param headingText  the heading text
	 * @return a new DocumentChunk starting at the specified heading
	 */
	@Nonnull
	public static DocumentChunk atHeading(
		int index,
		@Nonnull String content,
		int startOffset,
		int endOffset,
		int headingLevel,
		@Nonnull String headingText
	) {
		return new DocumentChunk(index, startOffset, endOffset, content, headingLevel, headingText);
	}

	/**
	 * Returns the size of this chunk in bytes using UTF-8 encoding.
	 *
	 * @return the byte size of the content
	 */
	public int sizeInBytes() {
		return this.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
	}

	/**
	 * Returns whether this is an intro chunk (content before the first heading).
	 *
	 * @return true if this is an intro chunk
	 */
	public boolean isIntro() {
		return this.headingLevel == 0;
	}
}
