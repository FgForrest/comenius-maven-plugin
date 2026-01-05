package io.evitadb.comenius.diff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UnifiedDiffParser should parse unified diff format")
public class UnifiedDiffParserTest {

	private UnifiedDiffParser parser;

	@BeforeEach
	void setUp() {
		this.parser = new UnifiedDiffParser();
	}

	@Test
	@DisplayName("parses simple single-hunk diff")
	void shouldParseSingleHunkDiff() throws Exception {
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-line2
			+modified line2
			 line3
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		final DiffHunk hunk = result.hunks().get(0);
		assertEquals(1, hunk.oldStart());
		assertEquals(3, hunk.oldCount());
		assertEquals(1, hunk.newStart());
		assertEquals(3, hunk.newCount());
		assertEquals(4, hunk.lines().size());
	}

	@Test
	@DisplayName("parses multi-hunk diff correctly")
	void shouldParseMultiHunkDiff() throws Exception {
		final String diff = """
			@@ -1,2 +1,2 @@
			-old first
			+new first
			 unchanged
			@@ -10,2 +10,2 @@
			 context
			-old second
			+new second
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(2, result.hunkCount());

		final DiffHunk hunk1 = result.hunks().get(0);
		assertEquals(1, hunk1.oldStart());
		assertEquals(2, hunk1.oldCount());

		final DiffHunk hunk2 = result.hunks().get(1);
		assertEquals(10, hunk2.oldStart());
		assertEquals(2, hunk2.oldCount());
	}

	@Test
	@DisplayName("handles diff with file headers")
	void shouldHandleDiffWithFileHeaders() throws Exception {
		final String diff = """
			--- a/original.txt
			+++ b/modified.txt
			@@ -1,1 +1,1 @@
			-original
			+modified
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		final DiffHunk hunk = result.hunks().get(0);
		assertEquals(1, hunk.linesRemoved());
		assertEquals(1, hunk.linesAdded());
	}

	@Test
	@DisplayName("extracts correct line numbers from hunk header")
	void shouldExtractCorrectLineNumbers() throws Exception {
		final String diff = "@@ -15,7 +20,9 @@\n context\n";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		final DiffHunk hunk = result.hunks().get(0);
		assertEquals(15, hunk.oldStart());
		assertEquals(7, hunk.oldCount());
		assertEquals(20, hunk.newStart());
		assertEquals(9, hunk.newCount());
	}

	@Test
	@DisplayName("handles hunk header without count (defaults to 1)")
	void shouldHandleHunkHeaderWithoutCount() throws Exception {
		final String diff = "@@ -5 +5 @@\n-old\n+new\n";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		final DiffHunk hunk = result.hunks().get(0);
		assertEquals(5, hunk.oldStart());
		assertEquals(1, hunk.oldCount());
		assertEquals(5, hunk.newStart());
		assertEquals(1, hunk.newCount());
	}

	@Test
	@DisplayName("identifies context, add, and remove lines correctly")
	void shouldIdentifyLineTypesCorrectly() throws Exception {
		final String diff = """
			@@ -1,3 +1,3 @@
			 context line
			-removed line
			+added line
			""";

		final DiffResult result = this.parser.parse(diff);

		final List<DiffLine> lines = result.hunks().get(0).lines();
		assertEquals(3, lines.size());

		assertTrue(lines.get(0).isContext());
		assertEquals("context line", lines.get(0).content());

		assertTrue(lines.get(1).isRemove());
		assertEquals("removed line", lines.get(1).content());

		assertTrue(lines.get(2).isAdd());
		assertEquals("added line", lines.get(2).content());
	}

	@Test
	@DisplayName("throws DiffParseException on invalid hunk header")
	void shouldThrowOnInvalidHunkHeader() {
		final String invalidDiff = "This is not a valid diff\n";

		final DiffParseException exception = assertThrows(
			DiffParseException.class,
			() -> this.parser.parse(invalidDiff)
		);

		assertTrue(exception.getMessage().contains("Expected hunk header"));
		assertEquals(1, exception.getLineNumber());
	}

	@Test
	@DisplayName("throws DiffParseException on invalid line prefix")
	void shouldThrowOnInvalidLinePrefix() {
		final String invalidDiff = """
			@@ -1,2 +1,2 @@
			 context
			xbad prefix
			""";

		final DiffParseException exception = assertThrows(
			DiffParseException.class,
			() -> this.parser.parse(invalidDiff)
		);

		assertTrue(exception.getMessage().contains("Invalid line prefix"));
		assertEquals(3, exception.getLineNumber());
	}

	@Test
	@DisplayName("handles empty diff gracefully")
	void shouldHandleEmptyDiff() throws Exception {
		final DiffResult result1 = this.parser.parse("");
		assertTrue(result1.isEmpty());

		final DiffResult result2 = this.parser.parse("   \n\n  ");
		assertTrue(result2.isEmpty());
	}

	@Test
	@DisplayName("validates diff format correctly")
	void shouldValidateDiffFormat() {
		assertTrue(this.parser.isValidDiffFormat("@@ -1,1 +1,1 @@\n-old\n+new\n"));
		assertTrue(this.parser.isValidDiffFormat("--- a/file\n+++ b/file\n@@ -1 +1 @@\n"));
		assertTrue(this.parser.isValidDiffFormat("")); // Empty is valid
		assertFalse(this.parser.isValidDiffFormat("This is not a diff"));
		assertFalse(this.parser.isValidDiffFormat("Some random text\nwithout hunks"));
	}

	@Test
	@DisplayName("handles 'No newline at end of file' marker")
	void shouldHandleNoNewlineMarker() throws Exception {
		final String diff = """
			@@ -1,2 +1,2 @@
			 line1
			-line2
			\\ No newline at end of file
			+line2 modified
			\\ No newline at end of file
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		// The marker should be skipped
		final List<DiffLine> lines = result.hunks().get(0).lines();
		assertEquals(3, lines.size());
	}

	@Test
	@DisplayName("parses diff with only additions")
	void shouldParseDiffWithOnlyAdditions() throws Exception {
		final String diff = """
			@@ -1,0 +1,3 @@
			+new line 1
			+new line 2
			+new line 3
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		assertEquals(3, result.linesAdded());
		assertEquals(0, result.linesRemoved());
	}

	@Test
	@DisplayName("parses diff with only deletions")
	void shouldParseDiffWithOnlyDeletions() throws Exception {
		final String diff = """
			@@ -1,3 +1,0 @@
			-removed line 1
			-removed line 2
			-removed line 3
			""";

		final DiffResult result = this.parser.parse(diff);

		assertEquals(1, result.hunkCount());
		assertEquals(0, result.linesAdded());
		assertEquals(3, result.linesRemoved());
	}

	@Test
	@DisplayName("handles lines with leading spaces after prefix")
	void shouldHandleLinesWithLeadingSpaces() throws Exception {
		final String diff = """
			@@ -1,2 +1,2 @@
			     indented context
			-    indented removed
			+    indented added
			""";

		final DiffResult result = this.parser.parse(diff);

		final List<DiffLine> lines = result.hunks().get(0).lines();
		// Content is everything after the prefix character
		// " " + "    indented context" -> "    indented context"
		assertEquals("    indented context", lines.get(0).content());
		// "-" + "    indented removed" -> "    indented removed"
		assertEquals("    indented removed", lines.get(1).content());
		// "+" + "    indented added" -> "    indented added"
		assertEquals("    indented added", lines.get(2).content());
	}

	@Test
	@DisplayName("handles empty content lines")
	void shouldHandleEmptyContentLines() throws Exception {
		final String diff = """
			@@ -1,3 +1,3 @@
			 first
			\s
			 third
			""";

		final DiffResult result = this.parser.parse(diff);

		final List<DiffLine> lines = result.hunks().get(0).lines();
		assertEquals(3, lines.size());
		assertEquals("first", lines.get(0).content());
		assertEquals("", lines.get(1).content()); // Empty line
		assertEquals("third", lines.get(2).content());
	}
}
