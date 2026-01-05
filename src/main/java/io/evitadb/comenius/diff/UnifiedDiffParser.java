package io.evitadb.comenius.diff;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diff format from LLM responses.
 * Validates diff structure and extracts hunks for application.
 *
 * Unified diff format:
 * ```
 * --- a/original
 * +++ b/modified
 * @@ -startLine,count +startLine,count @@
 *  context line
 * -removed line
 * +added line
 * ```
 */
public final class UnifiedDiffParser {

	/**
	 * Pattern for parsing hunk headers.
	 * Format: @@ -oldStart,oldCount +newStart,newCount @@
	 * Count is optional and defaults to 1 if omitted.
	 * Uses MULTILINE flag so ^ matches start of each line.
	 */
	private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile(
		"^@@\\s*-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s*@@",
		Pattern.MULTILINE
	);

	private static final String FILE_OLD_PREFIX = "---";
	private static final String FILE_NEW_PREFIX = "+++";
	private static final String NO_NEWLINE_MARKER = "\\ No newline at end of file";

	/**
	 * Parses a unified diff string into a structured DiffResult.
	 *
	 * @param diffText the raw diff text from LLM
	 * @return parsed DiffResult containing hunks
	 * @throws DiffParseException if diff format is invalid
	 */
	@Nonnull
	public DiffResult parse(@Nonnull String diffText) throws DiffParseException {
		Objects.requireNonNull(diffText, "diffText must not be null");

		// Handle empty or whitespace-only input
		final String trimmed = diffText.trim();
		if (trimmed.isEmpty()) {
			return DiffResult.empty();
		}

		final List<String> lines = diffText.lines().toList();
		final List<DiffHunk> hunks = new ArrayList<>();

		int lineIndex = 0;

		// Skip optional file headers (--- and +++)
		while (lineIndex < lines.size()) {
			final String line = lines.get(lineIndex);
			if (line.startsWith(FILE_OLD_PREFIX) || line.startsWith(FILE_NEW_PREFIX)) {
				lineIndex++;
			} else if (line.isBlank()) {
				lineIndex++;
			} else {
				break;
			}
		}

		// Parse hunks
		while (lineIndex < lines.size()) {
			final String line = lines.get(lineIndex);

			// Skip blank lines between hunks
			if (line.isBlank()) {
				lineIndex++;
				continue;
			}

			// Skip "No newline at end of file" markers between hunks
			if (line.startsWith("\\")) {
				lineIndex++;
				continue;
			}

			final Matcher hunkMatcher = HUNK_HEADER_PATTERN.matcher(line);

			if (hunkMatcher.find()) {
				final ParsedHunk parsed = parseHunk(lines, lineIndex, hunkMatcher, diffText);
				hunks.add(parsed.hunk);
				lineIndex = parsed.nextLineIndex;
			} else {
				throw new DiffParseException(
					"Expected hunk header (@@ ... @@) but found: '" + truncate(line, 40) + "'",
					diffText,
					lineIndex + 1
				);
			}
		}

		return new DiffResult(hunks);
	}

	/**
	 * Parses a single hunk starting at the given line index.
	 *
	 * @param lines          all lines of the diff
	 * @param hunkStartIndex index of the hunk header line
	 * @param hunkMatcher    matcher that matched the hunk header
	 * @param rawDiff        original diff text for error reporting
	 * @return parsed hunk and next line index
	 * @throws DiffParseException if hunk is malformed
	 */
	@Nonnull
	private ParsedHunk parseHunk(
		@Nonnull List<String> lines,
		int hunkStartIndex,
		@Nonnull Matcher hunkMatcher,
		@Nonnull String rawDiff
	) throws DiffParseException {
		final int oldStart = Integer.parseInt(hunkMatcher.group(1));
		final int oldCount = hunkMatcher.group(2) != null ?
			Integer.parseInt(hunkMatcher.group(2)) : 1;
		final int newStart = Integer.parseInt(hunkMatcher.group(3));
		final int newCount = hunkMatcher.group(4) != null ?
			Integer.parseInt(hunkMatcher.group(4)) : 1;

		final List<DiffLine> diffLines = new ArrayList<>();
		int lineIndex = hunkStartIndex + 1;

		// Track line counts to validate hunk
		int oldLinesRead = 0;
		int newLinesRead = 0;

		while (lineIndex < lines.size()) {
			final String line = lines.get(lineIndex);

			// Stop at next hunk header, file header, or if we've read enough lines
			if (HUNK_HEADER_PATTERN.matcher(line).find() ||
				line.startsWith(FILE_OLD_PREFIX) ||
				line.startsWith(FILE_NEW_PREFIX)) {
				break;
			}

			// Skip "No newline at end of file" marker
			if (line.equals(NO_NEWLINE_MARKER) || line.startsWith("\\")) {
				lineIndex++;
				continue;
			}

			// Handle empty lines - treat as context with empty content
			if (line.isEmpty()) {
				diffLines.add(DiffLine.context(""));
				oldLinesRead++;
				newLinesRead++;
				lineIndex++;
				continue;
			}

			final char prefix = line.charAt(0);
			final String content = line.length() > 1 ? line.substring(1) : "";

			switch (prefix) {
				case ' ' -> {
					diffLines.add(DiffLine.context(content));
					oldLinesRead++;
					newLinesRead++;
				}
				case '+' -> {
					diffLines.add(DiffLine.add(content));
					newLinesRead++;
				}
				case '-' -> {
					diffLines.add(DiffLine.remove(content));
					oldLinesRead++;
				}
				default -> throw new DiffParseException(
					"Invalid line prefix '" + prefix + "' - expected ' ', '+', or '-'",
					rawDiff,
					lineIndex + 1
				);
			}

			lineIndex++;

			// Stop if we've read all expected lines
			if (oldLinesRead >= oldCount && newLinesRead >= newCount) {
				break;
			}
		}

		final DiffHunk hunk = new DiffHunk(oldStart, oldCount, newStart, newCount, diffLines);
		return new ParsedHunk(hunk, lineIndex);
	}

	/**
	 * Quick validation to check if text looks like a unified diff.
	 * This is a fast check before full parsing.
	 *
	 * @param diffText the text to validate
	 * @return true if text appears to be a unified diff format
	 */
	public boolean isValidDiffFormat(@Nonnull String diffText) {
		Objects.requireNonNull(diffText, "diffText must not be null");

		if (diffText.isBlank()) {
			return true; // Empty diff is valid (no changes)
		}

		// Look for at least one hunk header
		return HUNK_HEADER_PATTERN.matcher(diffText).find();
	}

	/**
	 * Truncates a string for error messages.
	 *
	 * @param s      string to truncate
	 * @param maxLen maximum length
	 * @return truncated string
	 */
	@Nonnull
	private static String truncate(@Nonnull String s, int maxLen) {
		if (s.length() <= maxLen) {
			return s;
		}
		return s.substring(0, maxLen - 3) + "...";
	}

	/**
	 * Internal record for returning parsed hunk with next line index.
	 */
	private record ParsedHunk(@Nonnull DiffHunk hunk, int nextLineIndex) {
	}
}
