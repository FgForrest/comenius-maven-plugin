package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link ScopeTree} from the tokens produced by {@link MarkupScanner}.
 *
 * The scoping rule the builder implements is:
 *
 * > A heading's section extends until the next heading of the same-or-higher level **within the
 * > same tag scope**, or the end of the enclosing tag scope - whichever comes first.
 *
 * Cut-point precedence is therefore **tags, then headings, then blocks**. Tags win because a
 * component that is split in half is broken markup, while a heading section that stops early is
 * merely a smaller unit. Measurement on a real corpus found 329 headings whose section genuinely
 * outlives its enclosing tag; without this precedence every one of them is a chance to emit a
 * fragment with a dangling `&lt;/Note&gt;`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ScopeTreeBuilder {

	private final MarkupScanner scanner;
	private final TagVocabulary vocabulary;

	/**
	 * Creates a builder bound to the given vocabulary.
	 *
	 * @param vocabulary decides which names are markup and how strictly balance is enforced
	 */
	public ScopeTreeBuilder(@Nonnull TagVocabulary vocabulary) {
		this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
		this.scanner = new MarkupScanner(vocabulary);
	}

	/**
	 * Builds and validates a scope tree over the given source.
	 *
	 * @param source   the document text; the tree indexes exactly this string
	 * @param location human-readable description used in error messages, typically a file path
	 * @return a validated scope tree
	 * @throws UnbalancedMarkupException when a vocabulary tag is unbalanced and the vocabulary
	 *                                   is not lenient
	 */
	@Nonnull
	public ScopeTree build(@Nonnull String source, @Nonnull String location) {
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(location, "location must not be null");
		final List<MarkupToken> tokens = resolveBalance(
			this.scanner.scan(source), source, location, this.vocabulary.isLenient()
		);
		final List<ScopeNode> topLevel = buildContainers(source, tokens);
		final ScopeTree tree = new ScopeTree(source, ScopeNode.document(source.length(), groupHeadings(topLevel)));
		tree.validate();
		return tree;
	}

	// ---------------------------------------------------------------------------------------
	// balance resolution
	// ---------------------------------------------------------------------------------------

	/**
	 * Matches opening and closing tags and either reports or discards the ones that do not pair.
	 *
	 * Doing this as a separate pass rather than during construction keeps the builder itself
	 * total: once this returns, every remaining tag token is guaranteed to have a partner, so
	 * the tree walk has no error branch to get wrong.
	 *
	 * @param tokens   scanner output in ascending offset order
	 * @param source   the document text, used to render line numbers
	 * @param location human-readable description used in error messages
	 * @param lenient  when `true`, unbalanced tags are dropped instead of reported
	 * @return the tokens with unbalanced tags removed
	 * @throws UnbalancedMarkupException when defects exist and `lenient` is `false`
	 */
	@Nonnull
	private static List<MarkupToken> resolveBalance(
		@Nonnull List<MarkupToken> tokens,
		@Nonnull String source,
		@Nonnull String location,
		boolean lenient
	) {
		final Set<Integer> broken = TagBalance.match(tokens).brokenIndices();
		if (broken.isEmpty()) {
			return tokens;
		}
		if (!lenient) {
			throw new UnbalancedMarkupException(location, describeDefects(tokens, broken, source));
		}
		final List<MarkupToken> retained = new ArrayList<>(tokens.size() - broken.size());
		for (int i = 0; i < tokens.size(); i++) {
			if (!broken.contains(i)) {
				retained.add(tokens.get(i));
			}
		}
		return retained;
	}

	/**
	 * Turns broken token indices into reportable defects, ordered by position.
	 *
	 * @param tokens all tokens
	 * @param broken indices of unbalanced tag tokens
	 * @param source the document text, used to compute line numbers
	 * @return defects in document order
	 */
	@Nonnull
	private static List<UnbalancedMarkupException.Defect> describeDefects(
		@Nonnull List<MarkupToken> tokens,
		@Nonnull Set<Integer> broken,
		@Nonnull String source
	) {
		final List<Integer> ordered = new ArrayList<>(broken);
		ordered.sort(null);
		final List<UnbalancedMarkupException.Defect> defects = new ArrayList<>(ordered.size());
		for (final Integer index : ordered) {
			final MarkupToken token = tokens.get(index);
			defects.add(new UnbalancedMarkupException.Defect(
				Objects.requireNonNull(token.name()),
				token.start(),
				lineOf(source, token.start()),
				token.type() == MarkupToken.Type.TAG_OPEN
					? "opened but never closed"
					: "closed but never opened"
			));
		}
		return defects;
	}

	/**
	 * Computes the 1-based line number of an offset.
	 *
	 * @param source the document text
	 * @param offset the offset to locate
	 * @return the 1-based line number
	 */
	private static int lineOf(@Nonnull String source, int offset) {
		int line = 1;
		final int limit = Math.min(offset, source.length());
		for (int i = 0; i < limit; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	// ---------------------------------------------------------------------------------------
	// container tree
	// ---------------------------------------------------------------------------------------

	/**
	 * Builds the tag containment tree, filling every uncovered range with prose nodes.
	 *
	 * @param source the document text
	 * @param tokens balanced tokens in ascending offset order
	 * @return the top-level children, tiling the whole document
	 */
	@Nonnull
	private static List<ScopeNode> buildContainers(
		@Nonnull String source,
		@Nonnull List<MarkupToken> tokens
	) {
		final Deque<Frame> stack = new ArrayDeque<>();
		stack.push(new Frame(null, 0, 0, true));
		int position = 0;
		for (final MarkupToken token : tokens) {
			final Frame current = Objects.requireNonNull(stack.peek());
			if (token.start() > position) {
				emitText(source, position, token.start(), current.children);
			}
			switch (token.type()) {
				case CODE -> current.children.add(ScopeNode.code(token.start(), token.end()));
				case COMMENT -> current.children.add(ScopeNode.comment(token.start(), token.end()));
				case HEADING -> current.children.add(ScopeNode.heading(
					token.level(), token.start(), token.end(), token.contentStart(), token.contentEnd()
				));
				case TAG_SELF_CLOSING -> current.children.add(ScopeNode.tag(
					Objects.requireNonNull(token.name()), token.start(), token.end(),
					token.end(), token.end(), token.blockLevel(), List.of()
				));
				case TAG_OPEN -> stack.push(new Frame(
					Objects.requireNonNull(token.name()), token.start(), token.end(), token.blockLevel()
				));
				case TAG_CLOSE -> {
					final Frame frame = stack.pop();
					Objects.requireNonNull(stack.peek()).children.add(ScopeNode.tag(
						Objects.requireNonNull(frame.name), frame.start, token.end(),
						frame.contentStart, token.start(),
						// a tag is a cut point only when BOTH of its delimiters stand alone -
						// cutting before an opening tag whose closer sits mid-sentence would
						// still strand markup. The corpus has no such mixed pair today, so this
						// is insurance against a shape that is legal rather than a live fix.
						frame.blockLevel && token.blockLevel(), frame.children
					));
				}
			}
			position = token.end();
		}
		final Frame root = Objects.requireNonNull(stack.pop());
		if (position < source.length()) {
			emitText(source, position, source.length(), root.children);
		}
		if (!stack.isEmpty()) {
			throw new IllegalStateException(
				"tag stack not empty after building - balance resolution failed to normalise the token stream"
			);
		}
		return root.children;
	}

	/**
	 * An open container while the token stream is being walked.
	 */
	private static final class Frame {

		private final String name;
		private final int start;
		private final int contentStart;
		private final boolean blockLevel;
		private final List<ScopeNode> children = new ArrayList<>();

		private Frame(String name, int start, int contentStart, boolean blockLevel) {
			this.name = name;
			this.start = start;
			this.contentStart = contentStart;
			this.blockLevel = blockLevel;
		}

	}

	// ---------------------------------------------------------------------------------------
	// prose
	// ---------------------------------------------------------------------------------------

	/**
	 * Splits an uncovered range into one prose node per Markdown block.
	 *
	 * Blank lines terminate a block and belong to the block they close, so the whitespace that
	 * separates paragraphs is owned by exactly one node and survives reconstruction untouched.
	 * This is also the third and last tier of cut-point precedence: it is what keeps a document
	 * with neither tags nor headings from becoming one indivisible lump.
	 *
	 * @param source the document text
	 * @param from   inclusive start of the uncovered range
	 * @param to     exclusive end of the uncovered range
	 * @param out    accumulator the produced nodes are appended to
	 */
	private static void emitText(
		@Nonnull String source,
		int from,
		int to,
		@Nonnull List<ScopeNode> out
	) {
		int blockStart = from;
		int position = from;
		boolean sawContent = false;
		while (position < to) {
			final int currentLineEnd = Math.min(lineEnd(source, position), to);
			final int nextLine = Math.min(nextLineStart(source, position), to);
			if (!isAllBlank(source, position, currentLineEnd)) {
				sawContent = true;
			} else if (sawContent) {
				// a run of blank lines closes the block it follows; blank lines that *precede*
				// any content instead belong to the block they introduce, which is what keeps
				// the `\n\n` padding inside `<Note>` from becoming an empty node of its own
				int cursor = nextLine;
				while (cursor < to && isAllBlank(source, cursor, Math.min(lineEnd(source, cursor), to))) {
					cursor = Math.min(nextLineStart(source, cursor), to);
				}
				addText(source, blockStart, cursor, out);
				blockStart = cursor;
				position = cursor;
				sawContent = false;
				continue;
			}
			if (nextLine <= position) {
				break;
			}
			position = nextLine;
		}
		if (blockStart < to) {
			addText(source, blockStart, to, out);
		}
	}

	/**
	 * Appends a prose node whose content range excludes surrounding whitespace.
	 *
	 * @param source the document text
	 * @param start  inclusive start of the node
	 * @param end    exclusive end of the node
	 * @param out    accumulator the node is appended to
	 */
	private static void addText(
		@Nonnull String source,
		int start,
		int end,
		@Nonnull List<ScopeNode> out
	) {
		if (end <= start) {
			return;
		}
		int contentStart = start;
		while (contentStart < end && Character.isWhitespace(source.charAt(contentStart))) {
			contentStart++;
		}
		int contentEnd = end;
		while (contentEnd > contentStart && Character.isWhitespace(source.charAt(contentEnd - 1))) {
			contentEnd--;
		}
		out.add(ScopeNode.text(start, end, contentStart, contentEnd));
	}

	// ---------------------------------------------------------------------------------------
	// heading grouping
	// ---------------------------------------------------------------------------------------

	/**
	 * Wraps headings and the content they govern into {@link ScopeNode.Kind#HEADING_SECTION}
	 * nodes, recursing into tag children first so that grouping never crosses a tag boundary.
	 *
	 * @param children a sibling list tiling some container's content range
	 * @return the same range, re-expressed with heading sections
	 */
	@Nonnull
	private static List<ScopeNode> groupHeadings(@Nonnull List<ScopeNode> children) {
		final List<ScopeNode> normalized = new ArrayList<>(children.size());
		for (final ScopeNode child : children) {
			if (child.kind() == ScopeNode.Kind.TAG && !child.isLeaf()) {
				normalized.add(new ScopeNode(
					child.kind(), child.name(), child.start(), child.end(),
					child.contentStart(), child.contentEnd(), child.headingLevel(), child.blockLevel(),
					groupHeadings(child.children())
				));
			} else {
				normalized.add(child);
			}
		}
		return group(normalized);
	}

	/**
	 * Performs the heading grouping over one already-normalised sibling list.
	 *
	 * @param nodes siblings in document order
	 * @return the same siblings with heading sections folded in
	 */
	@Nonnull
	private static List<ScopeNode> group(@Nonnull List<ScopeNode> nodes) {
		final List<ScopeNode> result = new ArrayList<>();
		int index = 0;
		while (index < nodes.size()) {
			final ScopeNode node = nodes.get(index);
			if (node.kind() != ScopeNode.Kind.HEADING) {
				result.add(node);
				index++;
				continue;
			}
			final int level = node.headingLevel();
			int end = index + 1;
			while (end < nodes.size() && !isHeadingOfLevelAtMost(nodes.get(end), level)) {
				end++;
			}
			final List<ScopeNode> sectionChildren = new ArrayList<>();
			sectionChildren.add(node);
			sectionChildren.addAll(group(new ArrayList<>(nodes.subList(index + 1, end))));
			result.add(ScopeNode.headingSection(
				level, node.start(), nodes.get(end - 1).end(), sectionChildren
			));
			index = end;
		}
		return result;
	}

	/**
	 * Returns `true` when the node is a heading whose level is the same as, or higher in the
	 * document outline than, the given level - i.e. a node that terminates the current section.
	 *
	 * @param node  the node to test
	 * @param level the level of the section currently being accumulated
	 * @return `true` when the node ends the section
	 */
	private static boolean isHeadingOfLevelAtMost(@Nonnull ScopeNode node, int level) {
		return node.kind() == ScopeNode.Kind.HEADING && node.headingLevel() <= level;
	}

	// ---------------------------------------------------------------------------------------
	// line helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Returns `true` when every character in the given range is intra-line whitespace.
	 *
	 * @param source the document text
	 * @param from   inclusive start
	 * @param to     exclusive end
	 * @return `true` when the range holds no visible characters
	 */
	private static boolean isAllBlank(@Nonnull String source, int from, int to) {
		for (int i = from; i < to; i++) {
			final char character = source.charAt(i);
			if (character != ' ' && character != '\t' && character != '\r') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the offset of the newline terminating the line that starts at the given offset.
	 *
	 * @param source the document text
	 * @param from   offset of the first byte of the line
	 * @return offset of the terminating newline, or the length of the source
	 */
	private static int lineEnd(@Nonnull String source, int from) {
		final int newline = source.indexOf('\n', from);
		return newline < 0 ? source.length() : newline;
	}

	/**
	 * Returns the offset of the line following the one that starts at the given offset.
	 *
	 * @param source the document text
	 * @param from   offset of the first byte of the line
	 * @return offset just past the terminating newline, or the length of the source
	 */
	private static int nextLineStart(@Nonnull String source, int from) {
		final int newline = source.indexOf('\n', from);
		return newline < 0 ? source.length() : newline + 1;
	}

}
