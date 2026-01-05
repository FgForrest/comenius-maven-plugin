package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Applies a parsed unified diff to existing content.
 * Validates that context lines match before applying changes.
 */
public final class UnifiedDiffApplicator {

	/**
	 * Applies the diff to the given content.
	 *
	 * @param originalContent the existing translation content
	 * @param diff            the parsed diff to apply
	 * @return the content with diff applied
	 * @throws DiffApplicationException if context lines don't match or application fails
	 */
	@Nonnull
	public String apply(
		@Nonnull String originalContent,
		@Nonnull DiffResult diff
	) throws DiffApplicationException {
		Objects.requireNonNull(originalContent, "originalContent must not be null");
		Objects.requireNonNull(diff, "diff must not be null");

		// Empty diff means no changes
		if (diff.isEmpty()) {
			return originalContent;
		}

		// Split content into lines, preserving empty strings for empty lines
		final List<String> lines = new ArrayList<>(splitLines(originalContent));
		final boolean endsWithNewline = originalContent.endsWith("\n");

		// Apply hunks in reverse order to preserve line numbers for earlier hunks
		final List<DiffHunk> hunksReversed = new ArrayList<>(diff.hunks());
		Collections.reverse(hunksReversed);

		for (int i = hunksReversed.size() - 1; i >= 0; i--) {
			final DiffHunk hunk = hunksReversed.get(i);
			final int hunkIndex = diff.hunks().size() - 1 - i;
			applyHunk(lines, hunk, hunkIndex);
		}

		// Reconstruct content
		return joinLines(lines, endsWithNewline);
	}

	/**
	 * Applies a single hunk to the lines list.
	 *
	 * @param lines     the content lines (mutable)
	 * @param hunk      the hunk to apply
	 * @param hunkIndex the index of this hunk (for error reporting)
	 * @throws DiffApplicationException if application fails
	 */
	private void applyHunk(
		@Nonnull List<String> lines,
		@Nonnull DiffHunk hunk,
		int hunkIndex
	) throws DiffApplicationException {
		// Convert to 0-based index
		final int startIndex = hunk.oldStart() - 1;

		// Special case: adding to empty file or at line 0
		if (hunk.oldStart() == 0 && hunk.oldCount() == 0) {
			// Pure addition at the beginning
			final List<String> newLines = new ArrayList<>();
			for (final DiffLine diffLine : hunk.lines()) {
				if (diffLine.isAdd()) {
					newLines.add(diffLine.content());
				}
			}
			lines.addAll(0, newLines);
			return;
		}

		// Validate that context and remove lines match
		int checkIndex = startIndex;
		for (final DiffLine diffLine : hunk.lines()) {
			if (diffLine.isContext() || diffLine.isRemove()) {
				if (checkIndex >= lines.size()) {
					throw new DiffApplicationException(
						"Hunk extends beyond file end",
						hunk,
						hunkIndex,
						diffLine.content(),
						"<EOF>"
					);
				}

				final String expected = diffLine.content();
				final String actual = lines.get(checkIndex);

				if (!expected.equals(actual)) {
					throw new DiffApplicationException(
						"Context mismatch at line " + (checkIndex + 1),
						hunk,
						hunkIndex,
						expected,
						actual
					);
				}
				checkIndex++;
			}
		}

		// Build the new lines for this section
		final List<String> newLines = new ArrayList<>();
		int lineIndex = startIndex;

		for (final DiffLine diffLine : hunk.lines()) {
			switch (diffLine.type()) {
				case CONTEXT -> {
					// Keep the original line (already validated)
					if (lineIndex < lines.size()) {
						newLines.add(lines.get(lineIndex));
					}
					lineIndex++;
				}
				case REMOVE -> {
					// Skip this line (don't add to newLines)
					lineIndex++;
				}
				case ADD -> {
					// Add the new line
					newLines.add(diffLine.content());
					// Don't increment lineIndex - we're inserting
				}
			}
		}

		// Replace the affected range
		final int endIndex = Math.min(startIndex + hunk.oldCount(), lines.size());

		// Remove old lines
		if (startIndex < lines.size()) {
			for (int i = endIndex - 1; i >= startIndex; i--) {
				if (i < lines.size()) {
					lines.remove(i);
				}
			}
		}

		// Insert new lines
		lines.addAll(startIndex, newLines);
	}

	/**
	 * Validates that all context lines in the diff match the original content.
	 * This is a pre-check before applying changes.
	 *
	 * @param originalContent the existing translation content
	 * @param diff            the parsed diff to validate
	 * @return validation result with details of any mismatches
	 */
	@Nonnull
	public DiffValidationResult validate(
		@Nonnull String originalContent,
		@Nonnull DiffResult diff
	) {
		Objects.requireNonNull(originalContent, "originalContent must not be null");
		Objects.requireNonNull(diff, "diff must not be null");

		if (diff.isEmpty()) {
			return DiffValidationResult.success();
		}

		final List<String> lines = splitLines(originalContent);
		final List<ContextMismatch> mismatches = new ArrayList<>();

		for (int hunkIndex = 0; hunkIndex < diff.hunks().size(); hunkIndex++) {
			final DiffHunk hunk = diff.hunks().get(hunkIndex);
			int lineIndex = hunk.oldStart() - 1;

			for (final DiffLine diffLine : hunk.lines()) {
				if (diffLine.isContext() || diffLine.isRemove()) {
					if (lineIndex >= lines.size()) {
						mismatches.add(new ContextMismatch(
							hunkIndex,
							lineIndex + 1,
							diffLine.content(),
							"<EOF>"
						));
					} else if (!diffLine.content().equals(lines.get(lineIndex))) {
						mismatches.add(new ContextMismatch(
							hunkIndex,
							lineIndex + 1,
							diffLine.content(),
							lines.get(lineIndex)
						));
					}
					lineIndex++;
				}
			}
		}

		return mismatches.isEmpty() ?
			DiffValidationResult.success() :
			DiffValidationResult.failure(mismatches);
	}

	/**
	 * Splits content into lines, handling various line endings.
	 *
	 * @param content the content to split
	 * @return list of lines (without line terminators)
	 */
	@Nonnull
	private static List<String> splitLines(@Nonnull String content) {
		if (content.isEmpty()) {
			return new ArrayList<>();
		}

		final List<String> result = new ArrayList<>();
		int start = 0;
		int i = 0;

		while (i < content.length()) {
			final char c = content.charAt(i);
			if (c == '\n') {
				result.add(content.substring(start, i));
				start = i + 1;
			} else if (c == '\r') {
				result.add(content.substring(start, i));
				// Handle \r\n
				if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
					i++;
				}
				start = i + 1;
			}
			i++;
		}

		// Add remaining content (last line without newline)
		if (start < content.length()) {
			result.add(content.substring(start));
		}

		return result;
	}

	/**
	 * Joins lines back into a string.
	 *
	 * @param lines            the lines to join
	 * @param endsWithNewline whether to add trailing newline
	 * @return joined content
	 */
	@Nonnull
	private static String joinLines(@Nonnull List<String> lines, boolean endsWithNewline) {
		if (lines.isEmpty()) {
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				sb.append("\n");
			}
			sb.append(lines.get(i));
		}
		if (endsWithNewline) {
			sb.append("\n");
		}
		return sb.toString();
	}

	/**
	 * Result of validating a diff against content.
	 *
	 * @param valid      true if all context lines match
	 * @param mismatches list of context line mismatches (empty if valid)
	 */
	public record DiffValidationResult(
		boolean valid,
		@Nonnull List<ContextMismatch> mismatches
	) {

		/**
		 * Creates a new validation result.
		 */
		public DiffValidationResult {
			Objects.requireNonNull(mismatches, "mismatches must not be null");
			mismatches = Collections.unmodifiableList(mismatches);
		}

		/**
		 * Creates a valid result with no mismatches.
		 *
		 * @return valid result
		 */
		@Nonnull
		public static DiffValidationResult success() {
			return new DiffValidationResult(true, List.of());
		}

		/**
		 * Creates an invalid result with mismatches.
		 *
		 * @param mismatches the context mismatches found
		 * @return invalid result
		 */
		@Nonnull
		public static DiffValidationResult failure(@Nonnull List<ContextMismatch> mismatches) {
			return new DiffValidationResult(false, mismatches);
		}
	}

	/**
	 * Describes a context line mismatch between diff and actual content.
	 *
	 * @param hunkIndex  index of the hunk with the mismatch
	 * @param lineNumber line number in the original file (1-based)
	 * @param expected   expected line content (from diff)
	 * @param actual     actual line content (from file)
	 */
	public record ContextMismatch(
		int hunkIndex,
		int lineNumber,
		@Nonnull String expected,
		@Nonnull String actual
	) {

		/**
		 * Creates a context mismatch record.
		 */
		public ContextMismatch {
			Objects.requireNonNull(expected, "expected must not be null");
			Objects.requireNonNull(actual, "actual must not be null");
		}
	}
}
