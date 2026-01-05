package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a parsed unified diff containing one or more hunks.
 * This is the result of parsing a unified diff string.
 *
 * @param hunks the list of diff hunks in order
 */
public record DiffResult(
	@Nonnull List<DiffHunk> hunks
) {

	/**
	 * Creates a new DiffResult with validation.
	 *
	 * @param hunks the list of diff hunks
	 */
	public DiffResult {
		Objects.requireNonNull(hunks, "hunks must not be null");
		hunks = Collections.unmodifiableList(hunks);
	}

	/**
	 * Returns an empty diff result with no hunks.
	 *
	 * @return empty DiffResult
	 */
	@Nonnull
	public static DiffResult empty() {
		return new DiffResult(List.of());
	}

	/**
	 * Returns true if this diff contains no hunks.
	 *
	 * @return true if empty
	 */
	public boolean isEmpty() {
		return this.hunks.isEmpty();
	}

	/**
	 * Returns the total number of lines added across all hunks.
	 *
	 * @return total lines added
	 */
	public int linesAdded() {
		return this.hunks.stream()
			.mapToInt(DiffHunk::linesAdded)
			.sum();
	}

	/**
	 * Returns the total number of lines removed across all hunks.
	 *
	 * @return total lines removed
	 */
	public int linesRemoved() {
		return this.hunks.stream()
			.mapToInt(DiffHunk::linesRemoved)
			.sum();
	}

	/**
	 * Returns the total number of hunks in this diff.
	 *
	 * @return hunk count
	 */
	public int hunkCount() {
		return this.hunks.size();
	}

	/**
	 * Formats this diff result as a unified diff string.
	 *
	 * @return the complete diff in unified format
	 */
	@Nonnull
	public String toUnifiedDiff() {
		final StringBuilder sb = new StringBuilder();
		for (final DiffHunk hunk : this.hunks) {
			sb.append(hunk.toUnifiedDiff());
		}
		return sb.toString();
	}

	/**
	 * Formats this diff result as a unified diff string with file headers.
	 *
	 * @param oldFile the original file name
	 * @param newFile the new file name
	 * @return the complete diff with headers in unified format
	 */
	@Nonnull
	public String toUnifiedDiff(@Nonnull String oldFile, @Nonnull String newFile) {
		final StringBuilder sb = new StringBuilder();
		sb.append("--- ").append(oldFile).append("\n");
		sb.append("+++ ").append(newFile).append("\n");
		sb.append(toUnifiedDiff());
		return sb.toString();
	}
}
