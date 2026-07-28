package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Single-pass lexer that finds the constructs the scope tree is built from.
 *
 * The scanner is deliberately **not** CommonMark. Measurement on a real corpus of 70 documents
 * showed 40 fence delimiters that CommonMark does not recognise as fences at all, because a
 * preceding custom tag opens an HTML block that swallows them; the same distortion made a
 * quarter of the container tags look unbalanced. A parser whose block structure is reshaped by
 * the very tags we are trying to track cannot serve as the code mask, so the mask is computed
 * here instead.
 *
 * Masking precedence, highest first:
 *
 * 1. fenced code blocks - whole lines, delimiters included
 * 2. HTML comments
 * 3. inline code spans
 * 4. ATX heading lines
 *
 * Anything masked is invisible to later steps, which is what keeps `List&lt;SealedEntity&gt;`
 * inside a code span from being mistaken for a tag, and `# comment` inside a shell fence from
 * being mistaken for a heading.
 *
 * Two constructs are masked but *not* tokenised - inline code spans and the marker portion of
 * heading lines. They stay part of the surrounding prose so that a sentence is never split
 * around a backticked identifier, which would wreck translation quality for no structural gain.
 *
 * Known limitations, all verified absent from the target corpus and documented rather than
 * silently handled: setext headings are not recognised (ATX only), and tags inside a heading
 * line are treated as part of the heading text rather than as nested nodes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MarkupScanner {

	private final TagVocabulary vocabulary;

	/**
	 * Creates a scanner bound to the given vocabulary.
	 *
	 * @param vocabulary decides which names are markup and which are literal text
	 */
	public MarkupScanner(@Nonnull TagVocabulary vocabulary) {
		this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
	}

	/**
	 * Scans the given source and returns every construct found, ordered by offset.
	 *
	 * @param source the document text; must not be null
	 * @return non-overlapping tokens in ascending offset order
	 */
	@Nonnull
	public List<MarkupToken> scan(@Nonnull String source) {
		Objects.requireNonNull(source, "source must not be null");
		final boolean[] masked = new boolean[source.length()];
		final List<MarkupToken> tokens = new ArrayList<>();
		scanFencedCode(source, masked, tokens);
		scanComments(source, masked, tokens);
		maskInlineCode(source, masked);
		scanHeadings(source, masked, tokens);
		scanTags(source, masked, tokens);
		tokens.sort(Comparator.comparingInt(MarkupToken::start));
		return tokens;
	}

	// ---------------------------------------------------------------------------------------
	// fenced code
	// ---------------------------------------------------------------------------------------

	/**
	 * Finds fenced code blocks and emits one {@link MarkupToken.Type#CODE} token per block.
	 *
	 * Follows the CommonMark fence rules that matter in practice: up to three leading spaces,
	 * a run of at least three backticks or tildes, a closing run of the same character that is
	 * at least as long and stands alone on its line. An unclosed fence extends to end of input,
	 * which is what CommonMark does and, more importantly, is the safe direction to fail - text
	 * wrongly treated as code is left untranslated rather than corrupted.
	 *
	 * @param source the document text
	 * @param masked mask accumulator, updated in place
	 * @param tokens token accumulator
	 */
	private static void scanFencedCode(
		@Nonnull String source,
		@Nonnull boolean[] masked,
		@Nonnull List<MarkupToken> tokens
	) {
		final int length = source.length();
		int position = 0;
		while (position < length) {
			final int lineEnd = lineEnd(source, position);
			final int nextLine = nextLineStart(source, position);
			final int fenceLength = fenceOpenLength(source, position, lineEnd);
			if (fenceLength <= 0) {
				position = nextLine;
				continue;
			}
			final char fenceChar = source.charAt(position + leadingSpaces(source, position, lineEnd));
			int blockEnd = -1;
			int cursor = nextLine;
			while (cursor < length) {
				final int innerLineEnd = lineEnd(source, cursor);
				final int innerNextLine = nextLineStart(source, cursor);
				if (isFenceClose(source, cursor, innerLineEnd, fenceChar, fenceLength)) {
					blockEnd = innerNextLine;
					break;
				}
				cursor = innerNextLine;
			}
			if (blockEnd < 0) {
				// an unterminated fence owns the rest of the document
				blockEnd = length;
			}
			mask(masked, position, blockEnd);
			tokens.add(new MarkupToken(
				MarkupToken.Type.CODE, null, position, blockEnd, position, blockEnd, 0, true
			));
			position = blockEnd;
		}
	}

	/**
	 * Returns the length of the fence delimiter opening on the given line, or `0` when the line
	 * is not a fence opener.
	 *
	 * @param source    the document text
	 * @param lineStart offset of the first byte of the line
	 * @param lineEnd   offset just past the last byte of the line, excluding the newline
	 * @return the number of fence characters, or `0`
	 */
	private static int fenceOpenLength(@Nonnull String source, int lineStart, int lineEnd) {
		final int indent = leadingSpaces(source, lineStart, lineEnd);
		if (indent > 3) {
			return 0;
		}
		int position = lineStart + indent;
		if (position >= lineEnd) {
			return 0;
		}
		final char fenceChar = source.charAt(position);
		if (fenceChar != '`' && fenceChar != '~') {
			return 0;
		}
		int count = 0;
		while (position < lineEnd && source.charAt(position) == fenceChar) {
			count++;
			position++;
		}
		if (count < 3) {
			return 0;
		}
		if (fenceChar == '`' && source.indexOf('`', position) >= 0
			&& source.indexOf('`', position) < lineEnd) {
			// a backtick fence may not carry a backtick in its info string
			return 0;
		}
		return count;
	}

	/**
	 * Returns `true` when the given line closes a fence opened with the given character and
	 * length.
	 *
	 * @param source     the document text
	 * @param lineStart  offset of the first byte of the line
	 * @param lineEnd    offset just past the last byte of the line, excluding the newline
	 * @param fenceChar  the character the fence was opened with
	 * @param minLength  the length the fence was opened with
	 * @return `true` when the line is a valid closing fence
	 */
	private static boolean isFenceClose(
		@Nonnull String source,
		int lineStart,
		int lineEnd,
		char fenceChar,
		int minLength
	) {
		final int indent = leadingSpaces(source, lineStart, lineEnd);
		if (indent > 3) {
			return false;
		}
		int position = lineStart + indent;
		int count = 0;
		while (position < lineEnd && source.charAt(position) == fenceChar) {
			count++;
			position++;
		}
		if (count < minLength) {
			return false;
		}
		while (position < lineEnd) {
			if (!isBlank(source.charAt(position))) {
				return false;
			}
			position++;
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------
	// comments
	// ---------------------------------------------------------------------------------------

	/**
	 * Finds HTML comments outside code and emits one token each.
	 *
	 * @param source the document text
	 * @param masked mask accumulator, updated in place
	 * @param tokens token accumulator
	 */
	private static void scanComments(
		@Nonnull String source,
		@Nonnull boolean[] masked,
		@Nonnull List<MarkupToken> tokens
	) {
		final int length = source.length();
		int position = 0;
		while (position < length) {
			final int open = source.indexOf("<!--", position);
			if (open < 0) {
				return;
			}
			if (masked[open]) {
				position = open + 1;
				continue;
			}
			final int close = source.indexOf("-->", open + 4);
			final int end = close < 0 ? length : close + 3;
			mask(masked, open, end);
			tokens.add(new MarkupToken(
				MarkupToken.Type.COMMENT, null, open, end, open, end, 0, true
			));
			position = end;
		}
	}

	// ---------------------------------------------------------------------------------------
	// inline code
	// ---------------------------------------------------------------------------------------

	/**
	 * Masks inline code spans without emitting tokens.
	 *
	 * A code span is a run of N backticks closed by the next run of exactly N backticks, and it
	 * may not contain a blank line. It is masked so that a tag or heading marker inside it is
	 * ignored, but it stays part of the surrounding prose node: splitting a sentence around a
	 * backticked identifier would produce fragments no translator can work with, and buys no
	 * structural information the fingerprint cannot recover from the text itself.
	 *
	 * @param source the document text
	 * @param masked mask accumulator, updated in place
	 */
	private static void maskInlineCode(@Nonnull String source, @Nonnull boolean[] masked) {
		final int length = source.length();
		int position = 0;
		while (position < length) {
			if (source.charAt(position) != '`' || masked[position]) {
				position++;
				continue;
			}
			final int openStart = position;
			int openLength = 0;
			while (position < length && source.charAt(position) == '`') {
				openLength++;
				position++;
			}
			final int closeEnd = findCodeSpanClose(source, position, openLength, masked);
			if (closeEnd > 0) {
				mask(masked, openStart, closeEnd);
				position = closeEnd;
			}
		}
	}

	/**
	 * Locates the end of a code span opened by a backtick run of the given length.
	 *
	 * @param source     the document text
	 * @param from       offset just past the opening backtick run
	 * @param openLength number of backticks in the opening run
	 * @param masked     current mask, used to ignore backticks already claimed by code
	 * @return offset just past the closing run, or `-1` when the span never closes
	 */
	private static int findCodeSpanClose(
		@Nonnull String source,
		int from,
		int openLength,
		@Nonnull boolean[] masked
	) {
		final int length = source.length();
		int position = from;
		while (position < length) {
			final char current = source.charAt(position);
			if (current == '`' && !masked[position]) {
				int runLength = 0;
				while (position < length && source.charAt(position) == '`') {
					runLength++;
					position++;
				}
				if (runLength == openLength) {
					return position;
				}
				continue;
			}
			if (current == '\n' && blockBoundaryFollows(source, position)) {
				// code spans are inline constructs and never leave their block
				return -1;
			}
			position++;
		}
		return -1;
	}

	/**
	 * Returns `true` when the line following the newline at the given offset starts a new block.
	 *
	 * This bound is what keeps a stray backtick from doing real damage. CommonMark parses inline
	 * constructs one block at a time, so an unmatched opening backtick can only ever consume the
	 * rest of its own paragraph. A scanner that hunts until the next blank line instead will
	 * happily pair a typo such as `` `validity' `` with a backtick several elements later and
	 * mask every tag in between - which then surfaces as a cascade of bogus unbalanced-tag
	 * errors far from the actual typo. Six such typos exist in the corpus this was measured on,
	 * and none of them is a structural problem in the document.
	 *
	 * Erring towards *stopping* is the safe direction: an unmasked code span leaves a tag-like
	 * token visible, which the balance check reports loudly, whereas an over-long mask silently
	 * swallows real markup.
	 *
	 * @param source        the document text
	 * @param newlineOffset offset of a newline character
	 * @return `true` when the next line is blank, an HTML block, a heading or a fence
	 */
	private static boolean blockBoundaryFollows(@Nonnull String source, int newlineOffset) {
		final int length = source.length();
		final int lineStart = newlineOffset + 1;
		int position = lineStart;
		while (position < length && isBlank(source.charAt(position))) {
			position++;
		}
		if (position >= length || source.charAt(position) == '\n' || source.charAt(position) == '\r') {
			return true;
		}
		// deliberately no indentation limit here: documents that nest components indent their
		// markup cosmetically, so `    </dd>` is a block boundary even though CommonMark's
		// four-space rule would call it a continuation line
		final char first = source.charAt(position);
		if (first == '<') {
			return true;
		}
		if (first == '#') {
			int hashes = 0;
			int cursor = position;
			while (cursor < length && source.charAt(cursor) == '#') {
				hashes++;
				cursor++;
			}
			return hashes <= 6 && (cursor >= length || isBlank(source.charAt(cursor))
				|| source.charAt(cursor) == '\n');
		}
		if (first == '`' || first == '~') {
			int run = 0;
			int cursor = position;
			while (cursor < length && source.charAt(cursor) == first) {
				run++;
				cursor++;
			}
			return run >= 3;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------
	// headings
	// ---------------------------------------------------------------------------------------

	/**
	 * Finds ATX headings outside code and emits one token per heading line, masking the whole
	 * line so that later steps cannot look inside it.
	 *
	 * @param source the document text
	 * @param masked mask accumulator, updated in place
	 * @param tokens token accumulator
	 */
	private static void scanHeadings(
		@Nonnull String source,
		@Nonnull boolean[] masked,
		@Nonnull List<MarkupToken> tokens
	) {
		final int length = source.length();
		int lineStart = 0;
		while (lineStart < length) {
			final int lineEnd = lineEnd(source, lineStart);
			final int nextLine = nextLineStart(source, lineStart);
			final MarkupToken heading = parseHeading(source, lineStart, lineEnd, nextLine, masked);
			if (heading != null) {
				tokens.add(heading);
				mask(masked, lineStart, nextLine);
			}
			lineStart = nextLine;
		}
	}

	/**
	 * Parses a single line as an ATX heading.
	 *
	 * @param source    the document text
	 * @param lineStart offset of the first byte of the line
	 * @param lineEnd   offset just past the last byte of the line, excluding the newline
	 * @param nextLine  offset of the next line, i.e. just past the newline
	 * @param masked    current mask
	 * @return the heading token, or `null` when the line is not a heading
	 */
	@Nullable
	private static MarkupToken parseHeading(
		@Nonnull String source,
		int lineStart,
		int lineEnd,
		int nextLine,
		@Nonnull boolean[] masked
	) {
		final int indent = leadingSpaces(source, lineStart, lineEnd);
		if (indent > 3) {
			// four or more spaces is indented code in CommonMark; in tag-heavy documents it is
			// far more often cosmetic nesting, so it is simply not a heading either way
			return null;
		}
		int position = lineStart + indent;
		if (position >= lineEnd || source.charAt(position) != '#' || masked[position]) {
			return null;
		}
		int level = 0;
		while (position < lineEnd && source.charAt(position) == '#') {
			level++;
			position++;
		}
		if (level > 6) {
			return null;
		}
		if (position < lineEnd && !isBlank(source.charAt(position))) {
			// `#hashtag` is prose, not a heading
			return null;
		}
		int contentStart = position;
		while (contentStart < lineEnd && isBlank(source.charAt(contentStart))) {
			contentStart++;
		}
		int contentEnd = lineEnd;
		while (contentEnd > contentStart && isBlank(source.charAt(contentEnd - 1))) {
			contentEnd--;
		}
		// strip an optional closing sequence of hashes
		int trimmed = contentEnd;
		while (trimmed > contentStart && source.charAt(trimmed - 1) == '#') {
			trimmed--;
		}
		if (trimmed < contentEnd && (trimmed == contentStart || isBlank(source.charAt(trimmed - 1)))) {
			contentEnd = trimmed;
			while (contentEnd > contentStart && isBlank(source.charAt(contentEnd - 1))) {
				contentEnd--;
			}
		}
		return new MarkupToken(
			MarkupToken.Type.HEADING, null, lineStart, nextLine, contentStart, contentEnd, level, true
		);
	}

	// ---------------------------------------------------------------------------------------
	// tags
	// ---------------------------------------------------------------------------------------

	/**
	 * Finds vocabulary tags outside every masked region and emits one token each, then computes
	 * which of them stand alone on their line.
	 *
	 * @param source the document text
	 * @param masked mask accumulator, updated in place
	 * @param tokens token accumulator
	 */
	private void scanTags(
		@Nonnull String source,
		@Nonnull boolean[] masked,
		@Nonnull List<MarkupToken> tokens
	) {
		final int length = source.length();
		final List<MarkupToken> tags = new ArrayList<>();
		int position = 0;
		while (position < length) {
			if (source.charAt(position) != '<' || masked[position]) {
				position++;
				continue;
			}
			final MarkupToken tag = parseTag(source, position);
			if (tag == null || !this.vocabulary.isStructural(Objects.requireNonNull(tag.name()))) {
				position++;
				continue;
			}
			tags.add(tag);
			mask(masked, tag.start(), tag.end());
			position = tag.end();
		}
		tokens.addAll(assignBlockLevel(source, tags));
	}

	/**
	 * Parses a tag starting at the given `&lt;`.
	 *
	 * Attribute values are parsed properly rather than by scanning to the next `&gt;`, so that a
	 * `&gt;` inside a quoted attribute value cannot truncate the tag.
	 *
	 * @param source the document text
	 * @param start  offset of `&lt;`
	 * @return the tag token with a provisional `blockLevel` of `false`, or `null` when the text
	 *         at this offset is not a well-formed tag
	 */
	@Nullable
	private static MarkupToken parseTag(@Nonnull String source, int start) {
		final int length = source.length();
		int position = start + 1;
		final boolean closing = position < length && source.charAt(position) == '/';
		if (closing) {
			position++;
		}
		if (position >= length || !isNameStart(source.charAt(position))) {
			return null;
		}
		final int nameStart = position;
		while (position < length && isNameChar(source.charAt(position))) {
			position++;
		}
		final String name = source.substring(nameStart, position);
		if (closing) {
			while (position < length && isBlank(source.charAt(position))) {
				position++;
			}
			if (position >= length || source.charAt(position) != '>') {
				return null;
			}
			position++;
			return new MarkupToken(
				MarkupToken.Type.TAG_CLOSE, name, start, position, position, position, 0, false
			);
		}
		boolean selfClosing = false;
		while (true) {
			final int beforeWhitespace = position;
			while (position < length && isWhitespace(source.charAt(position))) {
				position++;
			}
			if (position >= length) {
				return null;
			}
			final char current = source.charAt(position);
			if (current == '>') {
				position++;
				break;
			}
			if (current == '/') {
				position++;
				if (position < length && source.charAt(position) == '>') {
					selfClosing = true;
					position++;
					break;
				}
				return null;
			}
			if (position == beforeWhitespace) {
				// attributes must be separated by whitespace
				return null;
			}
			position = parseAttribute(source, position);
			if (position < 0) {
				return null;
			}
		}
		return new MarkupToken(
			selfClosing ? MarkupToken.Type.TAG_SELF_CLOSING : MarkupToken.Type.TAG_OPEN,
			name, start, position, position, position, 0, false
		);
	}

	/**
	 * Parses a single attribute, with or without a value.
	 *
	 * @param source the document text
	 * @param start  offset of the first byte of the attribute name
	 * @return offset just past the attribute, or `-1` when the attribute is malformed
	 */
	private static int parseAttribute(@Nonnull String source, int start) {
		final int length = source.length();
		int position = start;
		while (position < length) {
			final char current = source.charAt(position);
			if (isWhitespace(current) || current == '=' || current == '>' || current == '/') {
				break;
			}
			position++;
		}
		if (position == start) {
			return -1;
		}
		int afterName = position;
		while (afterName < length && isWhitespace(source.charAt(afterName))) {
			afterName++;
		}
		if (afterName >= length || source.charAt(afterName) != '=') {
			// valueless attribute; whitespace belongs to the outer loop
			return position;
		}
		position = afterName + 1;
		while (position < length && isWhitespace(source.charAt(position))) {
			position++;
		}
		if (position >= length) {
			return -1;
		}
		final char quote = source.charAt(position);
		if (quote == '"' || quote == '\'') {
			final int close = source.indexOf(quote, position + 1);
			return close < 0 ? -1 : close + 1;
		}
		final int valueStart = position;
		while (position < length) {
			final char current = source.charAt(position);
			if (isWhitespace(current) || current == '>') {
				break;
			}
			position++;
		}
		return position == valueStart ? -1 : position;
	}

	/**
	 * Recomputes the `blockLevel` flag of every tag token.
	 *
	 * A tag is block-level - and therefore eligible as a cut point - when the lines it occupies
	 * contain nothing but tags and whitespace. `&lt;Tr&gt;` alone on its line qualifies;
	 * `&lt;Td&gt;value&lt;/Td&gt;` in the middle of a sentence does not, and must never become a
	 * translation-unit boundary.
	 *
	 * @param source the document text
	 * @param tags   tag tokens in ascending offset order
	 * @return the same tokens with the flag resolved
	 */
	@Nonnull
	private static List<MarkupToken> assignBlockLevel(
		@Nonnull String source,
		@Nonnull List<MarkupToken> tags
	) {
		final boolean[] covered = new boolean[source.length()];
		for (final MarkupToken tag : tags) {
			mask(covered, tag.start(), tag.end());
		}
		final List<MarkupToken> result = new ArrayList<>(tags.size());
		for (final MarkupToken tag : tags) {
			final int from = lineStartOf(source, tag.start());
			final int to = nextLineStart(source, lineStartOf(source, Math.max(tag.start(), tag.end() - 1)));
			boolean alone = true;
			for (int i = from; i < to; i++) {
				if (!covered[i] && !isBlank(source.charAt(i)) && source.charAt(i) != '\n') {
					alone = false;
					break;
				}
			}
			result.add(new MarkupToken(
				tag.type(), tag.name(), tag.start(), tag.end(),
				tag.contentStart(), tag.contentEnd(), tag.level(), alone
			));
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------
	// character and line helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Marks the given half-open range in the mask array.
	 *
	 * @param mask the array to update
	 * @param from inclusive start
	 * @param to   exclusive end
	 */
	private static void mask(@Nonnull boolean[] mask, int from, int to) {
		final int limit = Math.min(to, mask.length);
		for (int i = Math.max(0, from); i < limit; i++) {
			mask[i] = true;
		}
	}

	/**
	 * Counts leading space characters on a line, stopping at four.
	 *
	 * @param source    the document text
	 * @param lineStart offset of the first byte of the line
	 * @param lineEnd   offset just past the last byte of the line
	 * @return the number of leading spaces, capped at four
	 */
	private static int leadingSpaces(@Nonnull String source, int lineStart, int lineEnd) {
		int count = 0;
		while (lineStart + count < lineEnd && count < 4 && source.charAt(lineStart + count) == ' ') {
			count++;
		}
		return count;
	}

	/**
	 * Returns the offset just past the last byte of the line containing the given offset,
	 * excluding the newline itself.
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

	/**
	 * Returns the offset of the first byte of the line containing the given offset.
	 *
	 * @param source the document text
	 * @param offset any offset into the source
	 * @return offset of the first byte of the enclosing line
	 */
	private static int lineStartOf(@Nonnull String source, int offset) {
		final int newline = source.lastIndexOf('\n', Math.max(0, offset - 1));
		return newline < 0 ? 0 : newline + 1;
	}

	/**
	 * Returns `true` for a space or a tab.
	 *
	 * @param character the character to test
	 * @return `true` for intra-line whitespace
	 */
	private static boolean isBlank(char character) {
		return character == ' ' || character == '\t';
	}

	/**
	 * Returns `true` for any whitespace, newlines included.
	 *
	 * @param character the character to test
	 * @return `true` for whitespace
	 */
	private static boolean isWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\n' || character == '\r';
	}

	/**
	 * Returns `true` when the character may start a tag name.
	 *
	 * @param character the character to test
	 * @return `true` for an ASCII letter
	 */
	private static boolean isNameStart(char character) {
		return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
	}

	/**
	 * Returns `true` when the character may appear inside a tag name.
	 *
	 * @param character the character to test
	 * @return `true` for an ASCII letter, digit, hyphen, underscore, dot or colon
	 */
	private static boolean isNameChar(char character) {
		return isNameStart(character) || (character >= '0' && character <= '9')
			|| character == '-' || character == '_' || character == '.' || character == ':';
	}

}
