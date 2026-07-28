package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A single lexical finding produced by {@link MarkupScanner}.
 *
 * Tokens never overlap and are always returned in ascending offset order. Whatever the scanner
 * does *not* emit a token for is prose, and becomes {@link ScopeNode.Kind#TEXT} in the tree.
 *
 * @param type         what was found
 * @param name         tag name for the tag token types, `null` otherwise
 * @param start        inclusive offset of the first byte of the token
 * @param end          exclusive offset just past the last byte of the token
 * @param contentStart for {@link Type#HEADING} the offset of the heading text, otherwise `start`
 * @param contentEnd   for {@link Type#HEADING} the offset just past the heading text, else `end`
 * @param level        heading level 1-6 for {@link Type#HEADING}, otherwise 0
 * @param blockLevel   `true` when the line holding this token contains nothing but tags and
 *                     whitespace; only such tags are eligible as cut points
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record MarkupToken(
	@Nonnull Type type,
	@Nullable String name,
	int start,
	int end,
	int contentStart,
	int contentEnd,
	int level,
	boolean blockLevel
) {

	/**
	 * The kind of lexical finding.
	 */
	public enum Type {

		/** A fenced code block or an inline code span, delimiters included. */
		CODE,
		/** An HTML comment. */
		COMMENT,
		/** An ATX heading line. */
		HEADING,
		/** An opening tag such as `&lt;Note type="info"&gt;`. */
		TAG_OPEN,
		/** A closing tag such as `&lt;/Note&gt;`. */
		TAG_CLOSE,
		/** A self-closing tag such as `&lt;br/&gt;`. */
		TAG_SELF_CLOSING

	}

	/**
	 * Validates offsets and the presence of a name where one is required.
	 */
	public MarkupToken {
		Objects.requireNonNull(type, "type must not be null");
		if (!(start <= contentStart && contentStart <= contentEnd && contentEnd <= end)) {
			throw new IllegalArgumentException(
				"malformed token span on " + type + ": [" + start + "," + contentStart + "," +
					contentEnd + "," + end + ")"
			);
		}
	}

	/**
	 * Returns `true` when this token denotes a tag of any form.
	 *
	 * @return `true` for the three tag token types
	 */
	public boolean isTag() {
		return this.type == Type.TAG_OPEN || this.type == Type.TAG_CLOSE
			|| this.type == Type.TAG_SELF_CLOSING;
	}

}
