package io.evitadb.comenius.check;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts all heading anchors from a CommonMark AST.
 * Generates slugified anchor names following GitHub/CommonMark conventions.
 * These anchors can be used to validate `#section` style links.
 */
public final class MarkdownHeadingExtractor extends AbstractVisitor {

	@Nonnull
	private final Set<String> anchors = new HashSet<>();

	/**
	 * Extracts all heading anchors from the given document node.
	 *
	 * @param document the root node of the parsed markdown document
	 * @return set of anchor names (lowercase, hyphenated slugs)
	 */
	@Nonnull
	public static Set<String> extractAnchors(@Nonnull Node document) {
		Objects.requireNonNull(document, "document must not be null");
		final MarkdownHeadingExtractor extractor = new MarkdownHeadingExtractor();
		document.accept(extractor);
		return Set.copyOf(extractor.anchors);
	}

	@Override
	public void visit(@Nonnull Heading heading) {
		final String text = extractText(heading);
		if (!text.isEmpty()) {
			this.anchors.add(slugify(text));
		}
		visitChildren(heading);
	}

	/**
	 * Extracts plain text from a node and its children.
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
	 * Converts heading text to a URL-safe anchor slug.
	 * Follows GitHub-style anchor generation:
	 * - Convert to lowercase (including Unicode characters)
	 * - Remove special characters (keep Unicode letters, digits, spaces, hyphens)
	 * - Replace spaces with hyphens (each space becomes one hyphen)
	 * - Trim leading/trailing hyphens
	 *
	 * Note: Multiple consecutive hyphens are preserved (e.g., "Open / remap" becomes "open--remap"
	 * because the slash is removed leaving two spaces which become two hyphens).
	 *
	 * GitHub preserves national/Unicode characters in anchors (e.g., "struktura-záznamu-v-úložišti").
	 *
	 * @param text the heading text to slugify
	 * @return URL-safe anchor slug
	 */
	@Nonnull
	static String slugify(@Nonnull String text) {
		return text.toLowerCase()
			.replaceAll("[^\\p{L}\\p{N}\\s-]", "")
			.replaceAll("\\s", "-")
			.replaceAll("^-+|-+$", "");
	}

	/**
	 * Inner visitor to extract text content from nodes.
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
