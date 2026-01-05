package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits large markdown documents into smaller chunks for separate translation.
 * Uses heading-based splitting with weighted preference for higher-level headings.
 *
 * The algorithm:
 * 1. Find all heading positions using regex
 * 2. If document is under threshold, return single chunk
 * 3. Otherwise, split at headings preferring H1 > H2 > H3 etc.
 * 4. Chunks respect min/max size tolerances where possible
 */
public class DocumentSplitter {

	/**
	 * Default target chunk size in bytes (32kB).
	 */
	public static final int DEFAULT_TARGET_SIZE = 32 * 1024;

	/**
	 * Size tolerance as a fraction (20%).
	 */
	public static final double SIZE_TOLERANCE = 0.2;

	/**
	 * Pattern to match ATX-style headings (# Heading, ## Heading, etc.)
	 * Must be at the start of a line.
	 */
	private static final Pattern HEADING_PATTERN = Pattern.compile(
		"^(#{1,6})\\s+(.+)$",
		Pattern.MULTILINE
	);

	private final int targetSize;
	private final int minSize;
	private final int maxSize;

	/**
	 * Creates a DocumentSplitter with the default target size (32kB).
	 */
	public DocumentSplitter() {
		this(DEFAULT_TARGET_SIZE);
	}

	/**
	 * Creates a DocumentSplitter with a custom target size.
	 *
	 * @param targetSize the target chunk size in bytes
	 */
	public DocumentSplitter(int targetSize) {
		if (targetSize <= 0) {
			throw new IllegalArgumentException("targetSize must be positive");
		}
		this.targetSize = targetSize;
		this.minSize = (int) (targetSize * (1 - SIZE_TOLERANCE));
		this.maxSize = (int) (targetSize * (1 + SIZE_TOLERANCE));
	}

	/**
	 * Splits the document body content into chunks if it exceeds the target size.
	 * Returns a single chunk if the document is small enough.
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @return list of document chunks
	 */
	@Nonnull
	public List<DocumentChunk> split(@Nonnull String bodyContent) {
		Objects.requireNonNull(bodyContent, "bodyContent must not be null");

		final int contentSize = bodyContent.getBytes(StandardCharsets.UTF_8).length;

		// If under threshold, return single chunk
		if (contentSize <= this.targetSize) {
			return List.of(new DocumentChunk(0, 0, bodyContent.length(), bodyContent, 0, null));
		}

		// Find all headings
		final List<HeadingInfo> headings = findHeadings(bodyContent);

		// If no headings, return single chunk (warn condition - caller should handle)
		if (headings.isEmpty()) {
			return List.of(new DocumentChunk(0, 0, bodyContent.length(), bodyContent, 0, null));
		}

		// Split at heading boundaries
		return splitAtHeadings(bodyContent, headings);
	}

	/**
	 * Finds all headings in the content using regex.
	 *
	 * @param content the markdown content
	 * @return list of heading info sorted by position
	 */
	@Nonnull
	private List<HeadingInfo> findHeadings(@Nonnull String content) {
		final List<HeadingInfo> headings = new ArrayList<>();
		final Matcher matcher = HEADING_PATTERN.matcher(content);

		while (matcher.find()) {
			final int level = matcher.group(1).length();
			final String text = matcher.group(2).trim();
			final int offset = matcher.start();
			headings.add(new HeadingInfo(level, offset, text));
		}

		return headings;
	}

	/**
	 * Splits content at heading boundaries respecting size constraints.
	 *
	 * @param content  the full content
	 * @param headings the heading positions
	 * @return list of chunks
	 */
	@Nonnull
	private List<DocumentChunk> splitAtHeadings(
		@Nonnull String content,
		@Nonnull List<HeadingInfo> headings
	) {
		final List<DocumentChunk> chunks = new ArrayList<>();
		int currentStart = 0;
		int chunkIndex = 0;

		// Handle intro content (before first heading)
		final HeadingInfo firstHeading = headings.get(0);
		if (firstHeading.offset() > 0) {
			final String introContent = content.substring(0, firstHeading.offset());
			final int introSize = introContent.getBytes(StandardCharsets.UTF_8).length;

			// If intro is large enough to be its own chunk
			if (introSize > this.minSize) {
				chunks.add(DocumentChunk.intro(introContent, 0, firstHeading.offset()));
				currentStart = firstHeading.offset();
				chunkIndex++;
			}
		}

		// Process headings to create chunks
		while (currentStart < content.length()) {
			final int currentSize = content.substring(currentStart).getBytes(StandardCharsets.UTF_8).length;

			// If remaining content fits in one chunk, add it and done
			if (currentSize <= this.maxSize) {
				final String chunkContent = content.substring(currentStart);
				final HeadingInfo chunkHeading = findHeadingAt(headings, currentStart);
				if (chunkHeading != null) {
					chunks.add(DocumentChunk.atHeading(
						chunkIndex, chunkContent, currentStart, content.length(),
						chunkHeading.level(), chunkHeading.text()
					));
				} else {
					chunks.add(new DocumentChunk(
						chunkIndex, currentStart, content.length(), chunkContent, 0, null
					));
				}
				break;
			}

			// Find best heading to split at
			final HeadingInfo splitHeading = findBestSplitHeading(content, headings, currentStart);

			if (splitHeading == null) {
				// No suitable heading found, take everything remaining
				final String chunkContent = content.substring(currentStart);
				final HeadingInfo chunkHeading = findHeadingAt(headings, currentStart);
				if (chunkHeading != null) {
					chunks.add(DocumentChunk.atHeading(
						chunkIndex, chunkContent, currentStart, content.length(),
						chunkHeading.level(), chunkHeading.text()
					));
				} else {
					chunks.add(new DocumentChunk(
						chunkIndex, currentStart, content.length(), chunkContent, 0, null
					));
				}
				break;
			}

			// Create chunk up to the split heading
			final String chunkContent = content.substring(currentStart, splitHeading.offset());
			final HeadingInfo chunkHeading = findHeadingAt(headings, currentStart);

			if (chunkHeading != null) {
				chunks.add(DocumentChunk.atHeading(
					chunkIndex, chunkContent, currentStart, splitHeading.offset(),
					chunkHeading.level(), chunkHeading.text()
				));
			} else {
				chunks.add(new DocumentChunk(
					chunkIndex, currentStart, splitHeading.offset(), chunkContent, 0, null
				));
			}

			currentStart = splitHeading.offset();
			chunkIndex++;
		}

		return chunks;
	}

	/**
	 * Finds the best heading to split at within the acceptable size range.
	 * Prefers higher-level headings (H1 > H2 > H3 etc.)
	 *
	 * @param content      the full content
	 * @param headings     all headings
	 * @param currentStart current chunk start position
	 * @return the best heading to split at, or null if none found
	 */
	@Nullable
	private HeadingInfo findBestSplitHeading(
		@Nonnull String content,
		@Nonnull List<HeadingInfo> headings,
		int currentStart
	) {
		final List<HeadingInfo> candidates = new ArrayList<>();

		for (final HeadingInfo heading : headings) {
			// Skip headings before current position
			if (heading.offset() <= currentStart) {
				continue;
			}

			// Calculate chunk size if we split at this heading
			final String candidateChunk = content.substring(currentStart, heading.offset());
			final int candidateSize = candidateChunk.getBytes(StandardCharsets.UTF_8).length;

			// Check if within acceptable range
			if (candidateSize >= this.minSize && candidateSize <= this.maxSize) {
				candidates.add(heading);
			}

			// If we've passed the max size, stop looking
			if (candidateSize > this.maxSize) {
				break;
			}
		}

		if (candidates.isEmpty()) {
			// No heading in acceptable range, find closest one
			return findClosestHeading(content, headings, currentStart);
		}

		// Prefer lowest level number (H1 has level 1, H6 has level 6)
		return candidates.stream()
			.min(Comparator.comparingInt(HeadingInfo::level))
			.orElse(null);
	}

	/**
	 * Finds the closest heading after currentStart for when no heading is in acceptable range.
	 *
	 * @param content      the full content
	 * @param headings     all headings
	 * @param currentStart current position
	 * @return the closest heading, or null if none
	 */
	@Nullable
	private HeadingInfo findClosestHeading(
		@Nonnull String content,
		@Nonnull List<HeadingInfo> headings,
		int currentStart
	) {
		HeadingInfo lastValidHeading = null;

		for (final HeadingInfo heading : headings) {
			if (heading.offset() <= currentStart) {
				continue;
			}

			final String candidateChunk = content.substring(currentStart, heading.offset());
			final int candidateSize = candidateChunk.getBytes(StandardCharsets.UTF_8).length;

			// If this heading would result in a chunk that's at least minimum size
			if (candidateSize >= this.minSize) {
				// If we've gone past max, return the last valid one
				if (candidateSize > this.maxSize && lastValidHeading != null) {
					return lastValidHeading;
				}
				lastValidHeading = heading;
			}

			// If the chunk is already too large, we need to split here
			if (candidateSize > this.maxSize) {
				return heading;
			}
		}

		return lastValidHeading;
	}

	/**
	 * Finds the heading at the specified offset.
	 *
	 * @param headings all headings
	 * @param offset   the offset to search for
	 * @return the heading at that offset, or null if none
	 */
	@Nullable
	private HeadingInfo findHeadingAt(@Nonnull List<HeadingInfo> headings, int offset) {
		for (final HeadingInfo heading : headings) {
			if (heading.offset() == offset) {
				return heading;
			}
		}
		return null;
	}

	/**
	 * Returns the target chunk size in bytes.
	 *
	 * @return the target size
	 */
	public int getTargetSize() {
		return this.targetSize;
	}

	/**
	 * Returns the minimum acceptable chunk size in bytes.
	 *
	 * @return the minimum size
	 */
	public int getMinSize() {
		return this.minSize;
	}

	/**
	 * Returns the maximum acceptable chunk size in bytes.
	 *
	 * @return the maximum size
	 */
	public int getMaxSize() {
		return this.maxSize;
	}

	/**
	 * Internal record holding heading information during parsing.
	 */
	private record HeadingInfo(int level, int offset, @Nonnull String text) {}
}
