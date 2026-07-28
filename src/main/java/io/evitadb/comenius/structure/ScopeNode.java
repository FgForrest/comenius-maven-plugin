package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A single node of the scope tree - a span tree laid over the *original* document string.
 *
 * A node never carries text of its own; it carries offsets into the source string that was
 * handed to {@link ScopeTreeBuilder}. This is what makes a byte-exact identity round-trip
 * possible: reconstructing a tree with no replacements can only ever copy original bytes.
 *
 * Each node distinguishes two ranges:
 *
 * - `start`..`end` - everything the node owns, including its delimiters (the open and close
 *   tag of a {@link Kind#TAG}, the `###` marker and trailing newline of a {@link Kind#HEADING}).
 * - `contentStart`..`contentEnd` - the part that may be replaced by a translation, or that is
 *   tiled by this node's children.
 *
 * The tree obeys a **tiling invariant**, verified by {@link ScopeTree#validate}: for any node
 * that has children, the children exactly cover `contentStart`..`contentEnd` with no gaps and
 * no overlaps. Consequently every byte of the document belongs to exactly one leaf, which is
 * what lets the content-addressed translation memory key on nodes without losing prose that
 * happens to sit "between" two structural elements.
 *
 * Note: this is a record whose components include a child list, so {@link #equals(Object)} and
 * {@link #hashCode()} are deep and O(subtree). Never use nodes as hash-map keys - use an
 * `IdentityHashMap` (see {@link ScopeTreeReconstructor}).
 *
 * @param kind         the structural role of this node
 * @param name         tag name for {@link Kind#TAG}, `null` otherwise
 * @param start        inclusive offset of the first byte the node owns
 * @param end          exclusive offset just past the last byte the node owns
 * @param contentStart inclusive offset of the first byte of replaceable content
 * @param contentEnd   exclusive offset just past the last byte of replaceable content
 * @param headingLevel 1-6 for {@link Kind#HEADING} and {@link Kind#HEADING_SECTION}, else 0
 * @param blockLevel   `true` when a {@link Kind#TAG} stands alone on its line, and is therefore
 *                     eligible as a cut point; inline tags are never cut points
 * @param children     child nodes tiling `contentStart`..`contentEnd`, possibly empty
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ScopeNode(
	@Nonnull Kind kind,
	@Nullable String name,
	int start,
	int end,
	int contentStart,
	int contentEnd,
	int headingLevel,
	boolean blockLevel,
	@Nonnull List<ScopeNode> children
) {

	/**
	 * Structural role of a {@link ScopeNode}.
	 */
	public enum Kind {

		/** Root node spanning the whole document. */
		DOCUMENT,
		/** A balanced markup tag together with everything it encloses. */
		TAG,
		/** An ATX heading plus the content it governs, bounded by the enclosing tag scope. */
		HEADING_SECTION,
		/** The heading line itself; its content range is the heading text without the markers. */
		HEADING,
		/** A run of prose, normally a single Markdown block. Translatable. */
		TEXT,
		/** Fenced code or an inline code span. Never translated. */
		CODE,
		/** An HTML comment. Never translated. */
		COMMENT

	}

	/**
	 * Validates the internal consistency of the offsets. Cross-node tiling is checked separately
	 * by {@link ScopeTree#validate}, because it needs to see whole sibling lists.
	 */
	public ScopeNode {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(children, "children must not be null");
		if (!(start <= contentStart && contentStart <= contentEnd && contentEnd <= end)) {
			throw new IllegalArgumentException(
				"malformed span on " + kind + ": start=" + start + " contentStart=" + contentStart +
					" contentEnd=" + contentEnd + " end=" + end
			);
		}
	}

	/**
	 * Creates the root node of a document of the given length.
	 *
	 * @param length   total length of the source string
	 * @param children children tiling the whole document
	 * @return the root node
	 */
	@Nonnull
	public static ScopeNode document(int length, @Nonnull List<ScopeNode> children) {
		return new ScopeNode(Kind.DOCUMENT, null, 0, length, 0, length, 0, true, children);
	}

	/**
	 * Creates a tag node. The bytes before `contentStart` are the opening tag, the bytes from
	 * `contentEnd` are the closing tag; both are preserved verbatim on reconstruction.
	 *
	 * @param name         tag name, matched case-sensitively
	 * @param start        offset of `&lt;`
	 * @param end          offset just past `&gt;` of the closing tag
	 * @param contentStart offset just past `&gt;` of the opening tag
	 * @param contentEnd   offset of `&lt;` of the closing tag
	 * @param blockLevel   whether the tag stands alone on its line
	 * @param children     children tiling the content range
	 * @return the tag node
	 */
	@Nonnull
	public static ScopeNode tag(
		@Nonnull String name,
		int start,
		int end,
		int contentStart,
		int contentEnd,
		boolean blockLevel,
		@Nonnull List<ScopeNode> children
	) {
		Objects.requireNonNull(name, "name must not be null");
		return new ScopeNode(Kind.TAG, name, start, end, contentStart, contentEnd, 0, blockLevel, children);
	}

	/**
	 * Creates a heading section - the heading line plus everything it governs.
	 *
	 * @param level    heading level 1-6
	 * @param start    offset of the heading line
	 * @param end      offset just past the last byte the section governs
	 * @param children the heading node followed by the section body
	 * @return the heading section node
	 */
	@Nonnull
	public static ScopeNode headingSection(int level, int start, int end, @Nonnull List<ScopeNode> children) {
		return new ScopeNode(Kind.HEADING_SECTION, null, start, end, start, end, level, true, children);
	}

	/**
	 * Creates a heading line node. Its content range covers the heading text only, so a
	 * translation replaces the words without disturbing the `#` markers or the line break.
	 *
	 * @param level        heading level 1-6
	 * @param start        offset of the first byte of the heading line
	 * @param end          offset just past the line's terminating newline
	 * @param contentStart offset of the first byte of the heading text
	 * @param contentEnd   offset just past the last byte of the heading text
	 * @return the heading node
	 */
	@Nonnull
	public static ScopeNode heading(int level, int start, int end, int contentStart, int contentEnd) {
		return new ScopeNode(Kind.HEADING, null, start, end, contentStart, contentEnd, level, true, List.of());
	}

	/**
	 * Creates a prose node. Surrounding whitespace is deliberately excluded from the content
	 * range so that blank-line separation survives translation untouched.
	 *
	 * @param start        offset of the first byte the node owns
	 * @param end          offset just past the last byte the node owns
	 * @param contentStart offset of the first non-whitespace byte
	 * @param contentEnd   offset just past the last non-whitespace byte
	 * @return the text node
	 */
	@Nonnull
	public static ScopeNode text(int start, int end, int contentStart, int contentEnd) {
		return new ScopeNode(Kind.TEXT, null, start, end, contentStart, contentEnd, 0, true, List.of());
	}

	/**
	 * Creates a code node covering a fenced block or an inline code span, delimiters included.
	 *
	 * @param start offset of the first byte
	 * @param end   offset just past the last byte
	 * @return the code node
	 */
	@Nonnull
	public static ScopeNode code(int start, int end) {
		return new ScopeNode(Kind.CODE, null, start, end, start, end, 0, true, List.of());
	}

	/**
	 * Creates an HTML comment node.
	 *
	 * @param start offset of `&lt;!--`
	 * @param end   offset just past `--&gt;`
	 * @return the comment node
	 */
	@Nonnull
	public static ScopeNode comment(int start, int end) {
		return new ScopeNode(Kind.COMMENT, null, start, end, start, end, 0, true, List.of());
	}

	/**
	 * Returns `true` when the node has no children.
	 *
	 * @return `true` for leaf nodes
	 */
	public boolean isLeaf() {
		return this.children.isEmpty();
	}

	/**
	 * Returns `true` when the node's content is prose that should be handed to the translator.
	 * Structural nodes are not translatable themselves - their translatable text lives in
	 * their descendants.
	 *
	 * @return `true` when the content range holds translatable prose
	 */
	public boolean isTranslatable() {
		return (this.kind == Kind.TEXT || this.kind == Kind.HEADING) && this.contentEnd > this.contentStart;
	}

	/**
	 * Extracts the replaceable content of this node from the source it was built over.
	 *
	 * @param source the exact string the tree was built from
	 * @return the content substring, possibly empty
	 */
	@Nonnull
	public String content(@Nonnull String source) {
		return source.substring(this.contentStart, this.contentEnd);
	}

	/**
	 * Extracts everything this node owns, delimiters included.
	 *
	 * @param source the exact string the tree was built from
	 * @return the full substring owned by this node
	 */
	@Nonnull
	public String fullText(@Nonnull String source) {
		return source.substring(this.start, this.end);
	}

	/**
	 * Returns the number of bytes this node owns.
	 *
	 * @return `end - start`
	 */
	public int length() {
		return this.end - this.start;
	}

}
