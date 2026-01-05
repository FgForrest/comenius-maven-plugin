package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single hunk in a unified diff.
 * A hunk contains a header specifying the line ranges and a list of diff lines.
 *
 * The header format is: @@ -oldStart,oldCount +newStart,newCount @@
 *
 * @param oldStart  starting line number in the original file (1-based)
 * @param oldCount  number of lines from the original file in this hunk
 * @param newStart  starting line number in the new file (1-based)
 * @param newCount  number of lines in the new file after applying changes
 * @param lines     the list of diff lines (context, add, remove)
 */
public record DiffHunk(
	int oldStart,
	int oldCount,
	int newStart,
	int newCount,
	@Nonnull List<DiffLine> lines
) {

	/**
	 * Creates a new DiffHunk with validation.
	 *
	 * @param oldStart  starting line in original file (1-based)
	 * @param oldCount  line count in original file
	 * @param newStart  starting line in new file (1-based)
	 * @param newCount  line count in new file
	 * @param lines     the diff lines
	 */
	public DiffHunk {
		if (oldStart < 0) {
			throw new IllegalArgumentException("oldStart must be non-negative: " + oldStart);
		}
		if (oldCount < 0) {
			throw new IllegalArgumentException("oldCount must be non-negative: " + oldCount);
		}
		if (newStart < 0) {
			throw new IllegalArgumentException("newStart must be non-negative: " + newStart);
		}
		if (newCount < 0) {
			throw new IllegalArgumentException("newCount must be non-negative: " + newCount);
		}
		Objects.requireNonNull(lines, "lines must not be null");
		lines = Collections.unmodifiableList(lines);
	}

	/**
	 * Returns the number of lines added by this hunk.
	 *
	 * @return count of ADD lines
	 */
	public int linesAdded() {
		return (int) this.lines.stream()
			.filter(DiffLine::isAdd)
			.count();
	}

	/**
	 * Returns the number of lines removed by this hunk.
	 *
	 * @return count of REMOVE lines
	 */
	public int linesRemoved() {
		return (int) this.lines.stream()
			.filter(DiffLine::isRemove)
			.count();
	}

	/**
	 * Returns the number of context lines in this hunk.
	 *
	 * @return count of CONTEXT lines
	 */
	public int contextLines() {
		return (int) this.lines.stream()
			.filter(DiffLine::isContext)
			.count();
	}

	/**
	 * Formats this hunk as a unified diff string.
	 *
	 * @return the hunk in unified diff format
	 */
	@Nonnull
	public String toUnifiedDiff() {
		final StringBuilder sb = new StringBuilder();
		sb.append("@@ -").append(this.oldStart).append(",").append(this.oldCount);
		sb.append(" +").append(this.newStart).append(",").append(this.newCount);
		sb.append(" @@\n");

		for (final DiffLine line : this.lines) {
			switch (line.type()) {
				case CONTEXT -> sb.append(" ");
				case ADD -> sb.append("+");
				case REMOVE -> sb.append("-");
			}
			sb.append(line.content()).append("\n");
		}

		return sb.toString();
	}
}
