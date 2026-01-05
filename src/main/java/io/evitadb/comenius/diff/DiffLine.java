package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Represents a single line in a unified diff hunk.
 * Each line has a type (context, add, or remove) and its content.
 *
 * @param type    the type of diff line (CONTEXT, ADD, or REMOVE)
 * @param content the line content without the prefix character
 */
public record DiffLine(
	@Nonnull DiffLineType type,
	@Nonnull String content
) {

	/**
	 * Creates a new DiffLine with validation.
	 *
	 * @param type    the type of diff line
	 * @param content the line content
	 */
	public DiffLine {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(content, "content must not be null");
	}

	/**
	 * Creates a context line.
	 *
	 * @param content the line content
	 * @return a new context DiffLine
	 */
	@Nonnull
	public static DiffLine context(@Nonnull String content) {
		return new DiffLine(DiffLineType.CONTEXT, content);
	}

	/**
	 * Creates an add line.
	 *
	 * @param content the line content
	 * @return a new add DiffLine
	 */
	@Nonnull
	public static DiffLine add(@Nonnull String content) {
		return new DiffLine(DiffLineType.ADD, content);
	}

	/**
	 * Creates a remove line.
	 *
	 * @param content the line content
	 * @return a new remove DiffLine
	 */
	@Nonnull
	public static DiffLine remove(@Nonnull String content) {
		return new DiffLine(DiffLineType.REMOVE, content);
	}

	/**
	 * Returns true if this is a context line.
	 *
	 * @return true if context line
	 */
	public boolean isContext() {
		return this.type == DiffLineType.CONTEXT;
	}

	/**
	 * Returns true if this is an add line.
	 *
	 * @return true if add line
	 */
	public boolean isAdd() {
		return this.type == DiffLineType.ADD;
	}

	/**
	 * Returns true if this is a remove line.
	 *
	 * @return true if remove line
	 */
	public boolean isRemove() {
		return this.type == DiffLineType.REMOVE;
	}
}
