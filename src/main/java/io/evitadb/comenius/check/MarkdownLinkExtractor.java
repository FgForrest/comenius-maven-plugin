package io.evitadb.comenius.check;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Extracts all links and images from a CommonMark AST using the Visitor pattern.
 * Parses link destinations into structured LinkInfo objects for validation.
 * Skips links inside code blocks since they are examples, not real references.
 */
public final class MarkdownLinkExtractor extends AbstractVisitor {

	@Nonnull
	private final List<LinkInfo> links = new ArrayList<>();
	private boolean insideCodeBlock = false;

	/**
	 * Extracts all links from the given document node.
	 * This includes both hyperlinks (`[text](url)`) and images (`![alt](url)`).
	 * Links inside code blocks are skipped.
	 *
	 * @param document the root node of the parsed markdown document
	 * @return list of LinkInfo objects for each link found
	 */
	@Nonnull
	public static List<LinkInfo> extractLinks(@Nonnull Node document) {
		Objects.requireNonNull(document, "document must not be null");
		final MarkdownLinkExtractor extractor = new MarkdownLinkExtractor();
		document.accept(extractor);
		return List.copyOf(extractor.links);
	}

	@Override
	public void visit(@Nonnull Link link) {
		if (!this.insideCodeBlock) {
			final String destination = link.getDestination();
			if (destination != null && !destination.isEmpty()) {
				this.links.add(LinkInfo.parse(destination));
			}
		}
		visitChildren(link);
	}

	@Override
	public void visit(@Nonnull Image image) {
		if (!this.insideCodeBlock) {
			final String destination = image.getDestination();
			if (destination != null && !destination.isEmpty()) {
				this.links.add(LinkInfo.parse(destination));
			}
		}
		visitChildren(image);
	}

	@Override
	public void visit(@Nonnull FencedCodeBlock fencedCodeBlock) {
		// Don't descend into code blocks - links there are examples, not real references
		// No need to set flag since we don't call visitChildren
	}

	@Override
	public void visit(@Nonnull IndentedCodeBlock indentedCodeBlock) {
		// Don't descend into code blocks - links there are examples, not real references
		// No need to set flag since we don't call visitChildren
	}
}
