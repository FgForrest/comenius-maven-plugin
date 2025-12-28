package io.evitadb.comenius.check;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
	 * Returns an immutable list of all anchors in document order.
	 *
	 * @return ordered list of anchor slugs
	 */
	@Nonnull
	public List<String> getAnchors() {
		return Collections.unmodifiableList(this.anchors);
	}

	/**
	 * Visitor that collects headings in document order and converts them to anchor slugs.
	 */
	private static final class HeadingCollector extends AbstractVisitor {

		@Nonnull
		private final List<String> anchors = new ArrayList<>();

		@Override
		public void visit(@Nonnull Heading heading) {
			final String text = extractText(heading);
			if (!text.isEmpty()) {
				this.anchors.add(MarkdownHeadingExtractor.slugify(text));
			}
			visitChildren(heading);
		}

		/**
		 * Extracts plain text from a heading node and its children.
		 *
		 * @param node the node to extract text from
		 * @return concatenated text content
		 */
		@Nonnull
		private String extractText(@Nonnull Node node) {
			final StringBuilder sb = new StringBuilder();
			final TextExtractor textExtractor = new TextExtractor(sb);
			node.accept(textExtractor);
			return sb.toString();
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

	/**
	 * Visitor to extract text content from nodes.
	 * Handles both regular Text nodes and Code nodes (inline code).
	 */
	private static final class TextExtractor extends AbstractVisitor {

		@Nonnull
		private final StringBuilder sb;

		TextExtractor(@Nonnull StringBuilder sb) {
			this.sb = sb;
		}

		@Override
		public void visit(@Nonnull Text text) {
			this.sb.append(text.getLiteral());
		}

		@Override
		public void visit(@Nonnull Code code) {
			this.sb.append(code.getLiteral());
		}
	}
}
