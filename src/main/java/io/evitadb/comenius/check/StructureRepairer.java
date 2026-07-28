package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.structure.MarkdownHeadings;
import io.evitadb.comenius.structure.MarkdownHeadings.VisualHeading;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Restores headings that a markdown parser has stopped recognising because the blank line in
 * front of them went missing.
 *
 * <p>Under CommonMark a block of raw HTML - which is what a custom tag such as
 * {@code <Note>} or {@code </NoteTitle>} opens - runs until the next blank line. A heading that
 * follows such a tag without an intervening blank line is therefore not a heading at all: it is
 * absorbed into the HTML block and rendered as the literal text {@code ##### Title}. The damage
 * is invisible to tag-balance checking, because nothing about the tag pairing is wrong, and
 * invisible to heading-level comparison, because the swallowed heading simply is not in the
 * parsed sequence to be compared against.</p>
 *
 * <p>The repair is a single blank line, so no wording is ever touched: apart from inserted
 * newlines the output is byte-identical to the input.</p>
 *
 * <p>Detection is authoritative rather than heuristic. A candidate is <em>proposed</em> by
 * looking for a visible heading that a parser does not report, but the insertion is only kept
 * when re-parsing shows the parser now recognises one more heading. A guess that does not
 * actually help is rolled back, so this can never silently reshape a document on a hunch.</p>
 */
public final class StructureRepairer {

	/**
	 * Upper bound on repairs attempted for a single document, guarding against a pathological
	 * input that would otherwise loop forever.
	 */
	private static final int MAX_REPAIRS = 500;

	private StructureRepairer() {
		// utility class
	}

	/**
	 * A single restored heading.
	 *
	 * @param lineNumber  one-based line number of the heading in the repaired body
	 * @param headingText text of the heading that was restored
	 */
	public record Repair(int lineNumber, @Nonnull String headingText) {}

	/**
	 * Outcome of a repair attempt.
	 *
	 * @param content     the body content after repair, unchanged when nothing needed fixing
	 * @param repairs     the headings that were restored, in document order
	 * @param unrepaired  headings still not recognised by the parser after repair, if any
	 */
	public record Result(
		@Nonnull String content,
		@Nonnull List<Repair> repairs,
		@Nonnull List<String> unrepaired
	) {
		/**
		 * @return true when at least one heading was restored
		 */
		public boolean isModified() {
			return !this.repairs.isEmpty();
		}
	}

	/**
	 * Counts the headings a markdown parser recognises in the given body.
	 *
	 * @param bodyContent the markdown body content
	 * @return number of parsed headings
	 */
	private static int parsedHeadingCount(@Nonnull String bodyContent) {
		return HeadingAnchorIndex.fromDocument(new MarkdownDocument(bodyContent).getDocument()).size();
	}

	/**
	 * Restores headings swallowed by the block preceding them.
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @return the repair outcome; {@link Result#content()} equals the input when nothing was wrong
	 */
	@Nonnull
	public static Result repair(@Nonnull String bodyContent) {
		Objects.requireNonNull(bodyContent, "bodyContent must not be null");

		String current = bodyContent;
		final List<Repair> repairs = new ArrayList<>();
		// Inserting a blank line never adds or removes a visible heading, so the visible
		// sequence is stable across iterations and a heading can be identified by its index.
		final Set<Integer> rejectedIndices = new HashSet<>();
		final List<String> rejectedTexts = new ArrayList<>();

		for (int attempt = 0; attempt < MAX_REPAIRS; attempt++) {
			final List<VisualHeading> visible = MarkdownHeadings.scan(current);
			final int parsed = parsedHeadingCount(current);
			if (parsed >= visible.size()) {
				// every heading a reader sees is also a heading to the parser
				break;
			}

			final int candidateIndex = nextCandidateIndex(current, visible, rejectedIndices);
			if (candidateIndex < 0) {
				break;
			}
			final VisualHeading candidate = visible.get(candidateIndex);

			final String patched = insertBlankLineBefore(current, candidate.offset());
			if (parsedHeadingCount(patched) > parsed) {
				current = patched;
				repairs.add(new Repair(candidate.lineNumber(), candidate.text()));
			} else {
				// A blank line changed nothing here. That is the normal case for a heading
				// which merely follows a paragraph or a closed code fence - ATX headings are
				// allowed to interrupt those, so it was never broken to begin with. Skip it
				// and keep looking; whether anything is genuinely unrepairable is decided
				// once, below, by the final visible-versus-parsed gap.
				rejectedIndices.add(candidateIndex);
				rejectedTexts.add(candidate.text());
			}
		}

		final boolean gapRemains = parsedHeadingCount(current) < MarkdownHeadings.scan(current).size();
		return new Result(
			current,
			List.copyOf(repairs),
			gapRemains ? List.copyOf(rejectedTexts) : List.of()
		);
	}

	/**
	 * Proposes the next heading to try repairing: the first visible heading that is preceded
	 * directly by a non-blank line, which is the shape a swallowed heading always has.
	 *
	 * <p>This only narrows the search - it is not a verdict. Plenty of headings legitimately
	 * follow a non-blank line; the caller keeps a proposal only if re-parsing shows it helped.</p>
	 *
	 * @param content         the current body content
	 * @param visible         headings visible in {@code content}
	 * @param rejectedIndices indices already tried without success
	 * @return index into {@code visible}, or -1 when there is nothing left to try
	 */
	private static int nextCandidateIndex(
		@Nonnull String content,
		@Nonnull List<VisualHeading> visible,
		@Nonnull Set<Integer> rejectedIndices
	) {
		for (int i = 0; i < visible.size(); i++) {
			if (rejectedIndices.contains(i)) {
				continue;
			}
			final VisualHeading heading = visible.get(i);
			if (heading.offset() == 0) {
				continue;
			}
			final int previousLineEnd = heading.offset() - 1;
			final int previousLineStart = content.lastIndexOf('\n', previousLineEnd - 1) + 1;
			final String previousLine = content.substring(previousLineStart, previousLineEnd);
			if (!previousLine.isBlank()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Inserts a single blank line before the given offset.
	 *
	 * @param content the body content
	 * @param offset  offset of the first character of the heading line
	 * @return content with one extra newline inserted before the heading
	 */
	@Nonnull
	private static String insertBlankLineBefore(@Nonnull String content, int offset) {
		return content.substring(0, offset) + "\n" + content.substring(offset);
	}
}
