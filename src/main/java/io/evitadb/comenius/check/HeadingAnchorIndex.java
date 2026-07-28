package io.evitadb.comenius.check;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Maintains an ordered list of heading anchors extracted from a markdown document.
 * Unlike {@link MarkdownHeadingExtractor} which returns an unordered set, this class
 * preserves the document order of headings, enabling index-based anchor lookup.
 *
 * This is used for translating anchors between source and translated documents
 * by matching heading positions rather than heading text (which gets translated).
 */
public final class HeadingAnchorIndex {

	@Nonnull
	private final List<String> anchors;

	/**
	 * Creates a HeadingAnchorIndex with the given ordered list of anchors.
	 *
	 * @param anchors the ordered list of anchor slugs
	 */
	private HeadingAnchorIndex(@Nonnull List<String> anchors) {
		this.anchors = Objects.requireNonNull(anchors, "anchors must not be null");
	}

	/**
	 * Creates a HeadingAnchorIndex by extracting headings from a markdown document.
	 * Headings are extracted in document order and converted to anchor slugs.
	 *
	 * @param document the root node of the parsed markdown document
	 * @return HeadingAnchorIndex with ordered anchors
	 */
	@Nonnull
	public static HeadingAnchorIndex fromDocument(@Nonnull Node document) {
		Objects.requireNonNull(document, "document must not be null");
		final HeadingCollector collector = new HeadingCollector();
		document.accept(collector);
		return new HeadingAnchorIndex(collector.getAnchors());
	}

	/**
	 * Returns the number of headings in the document.
	 *
	 * @return heading count
	 */
	public int size() {
		return this.anchors.size();
	}

	/**
	 * Finds the index of the given anchor slug.
	 * Search is case-insensitive to handle variations in anchor references.
	 *
	 * @param anchor the anchor to find
	 * @return Optional with zero-based index, or empty if not found
	 */
	@Nonnull
	public Optional<Integer> indexOf(@Nonnull String anchor) {
		Objects.requireNonNull(anchor, "anchor must not be null");
		final String normalizedAnchor = anchor.toLowerCase();
		for (int i = 0; i < this.anchors.size(); i++) {
			if (this.anchors.get(i).equals(normalizedAnchor)) {
				return Optional.of(i);
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns the anchor at the given index.
	 *
	 * @param index zero-based index
	 * @return the anchor slug at that position
	 * @throws IndexOutOfBoundsException if index is out of range
	 */
	@Nonnull
	public String getAnchor(int index) {
		return this.anchors.get(index);
	}

	/**
	 * Finds the closest matching anchor using Levenshtein distance.
	 * Returns empty if no anchor is within the acceptable similarity threshold.
	 * The threshold is `max(2, anchor.length() / 3)` — allows small edits for short
	 * anchors, proportionally more for longer ones.
	 *
	 * @param anchor the anchor to find a close match for
	 * @return optional containing the index of the closest anchor
	 */
	@Nonnull
	public Optional<Integer> findClosest(@Nonnull String anchor) {
		Objects.requireNonNull(anchor, "anchor must not be null");
		final String normalizedAnchor = anchor.toLowerCase();
		final int threshold = Math.max(2, normalizedAnchor.length() / 3);
		final int anchorTokenCount = countTokens(normalizedAnchor);
		int bestDistance = Integer.MAX_VALUE;
		int bestIndex = -1;
		for (int i = 0; i < this.anchors.size(); i++) {
			final String candidate = this.anchors.get(i);
			// A candidate with a different number of tokens is usually a different heading
			// rather than a differently spelled one, and edit distance cannot tell those
			// apart: dropping the whole word "now" turns "attribute-in-range-now" into
			// "attribute-in-range" for four characters, comfortably inside any sane threshold
			// - and silently retargets the link from one constraint onto another.
			//
			// The one legitimate reason for the counts to differ is hyphenation, e.g.
			// "gettingstarted" against "getting-started". That case is recognisable on its
			// own terms: strip the hyphens and the letters are identical. A dropped word
			// leaves letters behind and is rejected.
			if (countTokens(candidate) != anchorTokenCount
				&& !withoutHyphens(normalizedAnchor).equals(withoutHyphens(candidate))) {
				continue;
			}
			final int distance = levenshteinDistance(normalizedAnchor, candidate);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestIndex = i;
			}
		}
		if (bestIndex >= 0 && bestDistance <= threshold) {
			return Optional.of(bestIndex);
		}
		return Optional.empty();
	}

	/**
	 * Strips every hyphen from an anchor slug, so that two anchors differing only in where
	 * their word boundaries fall compare equal.
	 *
	 * @param anchor the anchor slug
	 * @return the slug with all hyphens removed
	 */
	@Nonnull
	private static String withoutHyphens(@Nonnull String anchor) {
		return anchor.replace("-", "");
	}

	/**
	 * Counts hyphen-separated tokens in an anchor slug.
	 *
	 * @param anchor the anchor slug
	 * @return number of tokens, at least one
	 */
	private static int countTokens(@Nonnull String anchor) {
		int tokens = 1;
		for (int i = 0; i < anchor.length(); i++) {
			if (anchor.charAt(i) == '-') {
				tokens++;
			}
		}
		return tokens;
	}

	/**
	 * Finds the closest matching anchor using token overlap.
	 * Splits both the query and candidate anchors on hyphens and counts shared tokens.
	 * Requires a strict majority of query tokens to match: all tokens for 2-token queries,
	 * (n-1) for queries with 3+ tokens. Single-token queries are skipped.
	 *
	 * @param anchor the anchor to find a match for
	 * @return optional containing the index of the best matching anchor
	 */
	@Nonnull
	public Optional<Integer> findClosestByTokenOverlap(@Nonnull String anchor) {
		Objects.requireNonNull(anchor, "anchor must not be null");
		final String[] queryTokens = anchor.toLowerCase().split("-");
		if (queryTokens.length < 2) {
			return Optional.empty();
		}
		int bestIndex = -1;
		int bestMatchCount = 0;
		for (int i = 0; i < this.anchors.size(); i++) {
			// Set.copyOf (unlike Set.of) tolerates duplicate tokens in the candidate
			// anchor (e.g. "entity-entity-schema"), deduplicating instead of throwing
			final Set<String> candidateTokens = Set.copyOf(Arrays.asList(this.anchors.get(i).split("-")));
			int matchCount = 0;
			for (final String qt : queryTokens) {
				if (candidateTokens.contains(qt)) {
					matchCount++;
				}
			}
			// The matched tokens must also carry their weight on the candidate's side. A long
			// heading contains many words, so a short query can hit "enough" of its own tokens
			// while describing only a sliver of what the heading is about: '#price-histogram'
			// matched both its words inside "price histogram granularity and inner-record
			// handling" - two tokens out of ten - and silently retargeted the link from the
			// section onto one of its subsections.
			if (matchCount * 2 < candidateTokens.size()) {
				continue;
			}
			if (matchCount > bestMatchCount) {
				bestMatchCount = matchCount;
				bestIndex = i;
			}
		}
		// Require strict majority: all for 2-token, (n-1) for 3+
		final int required = Math.max(2, queryTokens.length - 1);
		if (bestIndex >= 0 && bestMatchCount >= required) {
			return Optional.of(bestIndex);
		}
		return Optional.empty();
	}

	/**
	 * Returns an immutable list of all anchors in document order.
	 *
	 * @return ordered list of anchor slugs
	 */
	@Nonnull
	public List<String> getAnchors() {
		return Collections.unmodifiableList(this.anchors);
	}

	/**
	 * Computes the Levenshtein distance between two strings using the
	 * Wagner-Fischer algorithm with a single-row optimization.
	 *
	 * @param a the first string
	 * @param b the second string
	 * @return the edit distance
	 */
	private static int levenshteinDistance(@Nonnull String a, @Nonnull String b) {
		final int aLen = a.length();
		final int bLen = b.length();
		if (aLen == 0) {
			return bLen;
		}
		if (bLen == 0) {
			return aLen;
		}
		final int[] prev = new int[bLen + 1];
		for (int j = 0; j <= bLen; j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= aLen; i++) {
			int prevDiag = prev[0];
			prev[0] = i;
			for (int j = 1; j <= bLen; j++) {
				final int temp = prev[j];
				if (a.charAt(i - 1) == b.charAt(j - 1)) {
					prev[j] = prevDiag;
				} else {
					prev[j] = 1 + Math.min(prevDiag, Math.min(prev[j], prev[j - 1]));
				}
				prevDiag = temp;
			}
		}
		return prev[bLen];
	}

	/**
	 * Visitor that collects headings in document order and converts them to anchor slugs.
	 */
	private static final class HeadingCollector extends AbstractVisitor {

		@Nonnull
		private final List<String> anchors = new ArrayList<>();

		@Override
		public void visit(@Nonnull Heading heading) {
			final String text = TextExtractor.extractText(heading);
			if (!text.isEmpty()) {
				this.anchors.add(MarkdownHeadingExtractor.slugify(text));
			}
			visitChildren(heading);
		}

		/**
		 * Returns the collected anchors in document order.
		 *
		 * @return list of anchor slugs
		 */
		@Nonnull
		List<String> getAnchors() {
			return this.anchors;
		}
	}
}
