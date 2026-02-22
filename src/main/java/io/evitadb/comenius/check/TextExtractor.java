package io.evitadb.comenius.check;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

import javax.annotation.Nonnull;

/**
 * Extracts plain text content from CommonMark AST nodes.
 * Handles both regular {@link Text} nodes and inline {@link Code} nodes.
 *
 * Shared utility used by {@link MarkdownHeadingExtractor} and {@link HeadingAnchorIndex}
 * to extract heading text for anchor slug generation.
 */
final class TextExtractor extends AbstractVisitor {

	@Nonnull
	private final StringBuilder sb;

	TextExtractor(@Nonnull StringBuilder sb) {
		this.sb = sb;
	}

	/**
	 * Extracts plain text from a node and its children.
	 *
	 * @param node the node to extract text from
	 * @return concatenated text content
	 */
	@Nonnull
	static String extractText(@Nonnull Node node) {
		final StringBuilder sb = new StringBuilder();
		final TextExtractor extractor = new TextExtractor(sb);
		node.accept(extractor);
		return sb.toString();
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
