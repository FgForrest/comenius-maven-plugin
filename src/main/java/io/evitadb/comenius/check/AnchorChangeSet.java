package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * Tracks the relationship between old and new heading anchors for a translated file.
 * Used by {@link ExternalLinkCorrector} to fix cross-document anchor references
 * after a file has been re-translated and its headings may have changed.
 *
 * For incremental translations, both old and new indices are available, enabling
 * position-based mapping when sizes match. For full translations (file was deleted
 * and re-created), only the new index is available, so Levenshtein fuzzy matching
 * is used as fallback.
 *
 * Resolution strategy in {@link #correctAnchor(String)}:
 * 1. If anchor exists in new index → return as-is (no correction needed)
 * 2. If old index available and sizes match → position-based mapping
 * 3. Fallback → Levenshtein fuzzy match against new index
 * 4. Return null if no reasonable match found
 */
public final class AnchorChangeSet {

	@Nullable
	private final HeadingAnchorIndex oldIndex;
	@Nonnull
	private final HeadingAnchorIndex newIndex;

	/**
	 * Creates an AnchorChangeSet for an incremental translation where the
	 * old translation is available for comparison.
	 *
	 * @param oldIndex the heading anchor index from the previous translation
	 * @param newIndex the heading anchor index from the new translation
	 */
	public AnchorChangeSet(
		@Nonnull HeadingAnchorIndex oldIndex,
		@Nonnull HeadingAnchorIndex newIndex
	) {
		this.oldIndex = Objects.requireNonNull(oldIndex, "oldIndex must not be null");
		this.newIndex = Objects.requireNonNull(newIndex, "newIndex must not be null");
	}

	/**
	 * Creates an AnchorChangeSet for a full (new) translation where no
	 * previous translation exists. Only Levenshtein fuzzy matching is
	 * available for anchor correction.
	 *
	 * @param newIndex the heading anchor index from the new translation
	 */
	public AnchorChangeSet(@Nonnull HeadingAnchorIndex newIndex) {
		this.oldIndex = null;
		this.newIndex = Objects.requireNonNull(newIndex, "newIndex must not be null");
	}

	/**
	 * Corrects an anchor reference to match the new translation.
	 *
	 * Resolution strategy:
	 * 1. If anchor exists in new index, return as-is (no change needed)
	 * 2. If old index available and sizes match, use position-based mapping
	 * 3. Fallback: Levenshtein fuzzy match against new index
	 * 4. Return null if no reasonable match found
	 *
	 * @param anchor the anchor to correct (without '#' prefix)
	 * @return the corrected anchor, or null if no match found
	 */
	@Nullable
	public String correctAnchor(@Nonnull String anchor) {
		Objects.requireNonNull(anchor, "anchor must not be null");

		// 1. Check if anchor already exists in new index — no correction needed
		final Optional<Integer> newIndexOpt = this.newIndex.indexOf(anchor);
		if (newIndexOpt.isPresent()) {
			return anchor;
		}

		// 2. If old index available, try position-based mapping
		if (this.oldIndex != null) {
			final Optional<Integer> oldIndexOpt = this.oldIndex.indexOf(anchor);
			if (oldIndexOpt.isPresent()) {
				final int position = oldIndexOpt.get();
				// Only use position mapping when sizes match (structure preserved)
				if (this.oldIndex.size() == this.newIndex.size()
					&& position < this.newIndex.size()) {
					return this.newIndex.getAnchor(position);
				}
			}
		}

		// 3. Fallback: Levenshtein fuzzy match against new index
		final Optional<Integer> closestOpt = this.newIndex.findClosest(anchor);
		if (closestOpt.isPresent()) {
			return this.newIndex.getAnchor(closestOpt.get());
		}

		// 4. No match found
		return null;
	}

	/**
	 * Returns true if this change set has any actual heading changes.
	 * For incremental translations, compares old and new anchor lists.
	 * For full translations (no old index), always returns true since
	 * we cannot determine if anchors changed.
	 *
	 * @return true if anchors have changed or change status is unknown
	 */
	public boolean hasChanges() {
		if (this.oldIndex == null) {
			return true;
		}
		return !this.oldIndex.getAnchors().equals(this.newIndex.getAnchors());
	}

	/**
	 * Returns the new heading anchor index.
	 *
	 * @return the new index
	 */
	@Nonnull
	public HeadingAnchorIndex getNewIndex() {
		return this.newIndex;
	}

	/**
	 * Returns the old heading anchor index, if available.
	 * Null for full translations where no previous translation existed.
	 *
	 * @return the old index, or null
	 */
	@Nullable
	public HeadingAnchorIndex getOldIndex() {
		return this.oldIndex;
	}
}
