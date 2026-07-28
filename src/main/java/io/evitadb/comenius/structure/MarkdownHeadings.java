package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the ATX headings a reader sees in a markdown body, skipping lines inside fenced
 * code blocks.
 *
 * <p>This is deliberately <em>not</em> a markdown parser. It answers "which lines look like a
 * heading to a human", which is the question you need when deciding where to cut a document
 * into sections, or when hunting for a heading that a parser has stopped recognising. To learn
 * which headings a parser actually sees, parse the document and use
 * {@code HeadingAnchorIndex} instead - comparing the two is what exposes a heading that has
 * been swallowed by the block in front of it.</p>
 *
 * <p>Skipping fenced blocks matters in practice: shell snippets routinely contain comments
 * such as {@code # run in foreground}, which are indistinguishable from headings by a plain
 * regex scan.</p>
 */
public final class MarkdownHeadings {

	/**
	 * Matches an ATX heading line: one to six leading hashes followed by whitespace and text.
	 */
	private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

	/**
	 * Matches the opening or closing line of a fenced code block: three or more backticks or
	 * tildes, indented by at most three spaces, optionally followed by an info string.
	 */
	private static final Pattern FENCE_PATTERN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

	private MarkdownHeadings() {
		// utility class
	}

	/**
	 * A heading as it appears in the raw text.
	 *
	 * @param level      heading level, 1 to 6, i.e. the number of leading hashes
	 * @param text       heading text with the hashes and surrounding whitespace stripped
	 * @param offset     character offset of the first hash within the scanned body
	 * @param lineNumber one-based line number within the scanned body
	 */
	public record VisualHeading(int level, @Nonnull String text, int offset, int lineNumber) {}

	/**
	 * Scans a markdown body for headings visible to a reader, ignoring fenced code blocks.
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @return headings in document order
	 */
	@Nonnull
	public static List<VisualHeading> scan(@Nonnull String bodyContent) {
		Objects.requireNonNull(bodyContent, "bodyContent must not be null");
		final List<VisualHeading> headings = new ArrayList<>();
		final int length = bodyContent.length();
		char fenceChar = 0;
		int fenceLength = 0;
		int lineStart = 0;
		int lineNumber = 1;
		while (lineStart <= length) {
			int lineEnd = bodyContent.indexOf('\n', lineStart);
			if (lineEnd < 0) {
				lineEnd = length;
			}
			final String line = bodyContent.substring(lineStart, lineEnd);
			final Matcher fence = FENCE_PATTERN.matcher(line);
			if (fence.matches()) {
				final String marker = fence.group(1);
				if (fenceChar == 0) {
					fenceChar = marker.charAt(0);
					fenceLength = marker.length();
				} else if (marker.charAt(0) == fenceChar
					&& marker.length() >= fenceLength
					&& fence.group(2).isBlank()) {
					// a closing fence uses the same character, is at least as long as the
					// opening one, and carries no info string
					fenceChar = 0;
					fenceLength = 0;
				}
			} else if (fenceChar == 0) {
				final Matcher heading = HEADING_PATTERN.matcher(line);
				if (heading.matches()) {
					headings.add(new VisualHeading(
						heading.group(1).length(), heading.group(2).trim(), lineStart, lineNumber
					));
				}
			}
			if (lineEnd == length) {
				break;
			}
			lineStart = lineEnd + 1;
			lineNumber++;
		}
		return headings;
	}
}
