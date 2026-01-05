package io.evitadb.comenius.diff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UnifiedDiffApplicator should apply diffs to content")
public class UnifiedDiffApplicatorTest {

	private UnifiedDiffApplicator applicator;
	private UnifiedDiffParser parser;

	@BeforeEach
	void setUp() {
		this.applicator = new UnifiedDiffApplicator();
		this.parser = new UnifiedDiffParser();
	}

	@Test
	@DisplayName("applies simple single-line change")
	void shouldApplySingleLineChange() throws Exception {
		final String original = "line1\nline2\nline3\n";
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-line2
			+modified line2
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("line1\nmodified line2\nline3\n", result);
	}

	@Test
	@DisplayName("applies line addition correctly")
	void shouldApplyLineAddition() throws Exception {
		final String original = "line1\nline3\n";
		final String diff = """
			@@ -1,2 +1,3 @@
			 line1
			+line2
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("line1\nline2\nline3\n", result);
	}

	@Test
	@DisplayName("applies line removal correctly")
	void shouldApplyLineRemoval() throws Exception {
		final String original = "line1\nline2\nline3\n";
		final String diff = """
			@@ -1,3 +1,2 @@
			 line1
			-line2
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("line1\nline3\n", result);
	}

	@Test
	@DisplayName("applies multiple hunks in correct order")
	void shouldApplyMultipleHunks() throws Exception {
		final String original = "a\nb\nc\nd\ne\nf\ng\n";
		final String diff = """
			@@ -1,2 +1,2 @@
			-a
			+A
			 b
			@@ -5,2 +5,2 @@
			-e
			+E
			 f
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("A\nb\nc\nd\nE\nf\ng\n", result);
	}

	@Test
	@DisplayName("throws DiffApplicationException on context mismatch")
	void shouldThrowOnContextMismatch() throws Exception {
		final String original = "line1\nactual content\nline3\n";
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-expected content
			+new content
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);

		final DiffApplicationException exception = assertThrows(
			DiffApplicationException.class,
			() -> this.applicator.apply(original, parsedDiff)
		);

		assertTrue(exception.getMessage().contains("Context mismatch"));
		assertEquals("expected content", exception.getExpectedContext());
		assertEquals("actual content", exception.getActualContext());
	}

	@Test
	@DisplayName("validates diff before application")
	void shouldValidateDiffBeforeApplying() throws Exception {
		final String original = "line1\nline2\nline3\n";
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-wrong content
			+new content
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final UnifiedDiffApplicator.DiffValidationResult validation =
			this.applicator.validate(original, parsedDiff);

		assertFalse(validation.valid());
		assertEquals(1, validation.mismatches().size());

		final UnifiedDiffApplicator.ContextMismatch mismatch = validation.mismatches().get(0);
		assertEquals("wrong content", mismatch.expected());
		assertEquals("line2", mismatch.actual());
	}

	@Test
	@DisplayName("preserves trailing newline when present")
	void shouldPreserveTrailingNewline() throws Exception {
		final String originalWithNewline = "content\n";
		final String diff = """
			@@ -1,1 +1,1 @@
			-content
			+modified
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(originalWithNewline, parsedDiff);

		assertTrue(result.endsWith("\n"));
		assertEquals("modified\n", result);
	}

	@Test
	@DisplayName("handles content without trailing newline")
	void shouldHandleContentWithoutTrailingNewline() throws Exception {
		final String originalNoNewline = "content";
		final String diff = """
			@@ -1,1 +1,1 @@
			-content
			+modified
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(originalNoNewline, parsedDiff);

		assertFalse(result.endsWith("\n"));
		assertEquals("modified", result);
	}

	@Test
	@DisplayName("handles empty original content with additions")
	void shouldHandleEmptyOriginalContent() throws Exception {
		final String original = "";
		final String diff = """
			@@ -0,0 +1,2 @@
			+new line 1
			+new line 2
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("new line 1\nnew line 2", result);
	}

	@Test
	@DisplayName("handles diff that adds to end of file")
	void shouldHandleAdditionAtEndOfFile() throws Exception {
		final String original = "line1\nline2\n";
		final String diff = """
			@@ -1,2 +1,4 @@
			 line1
			 line2
			+line3
			+line4
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("line1\nline2\nline3\nline4\n", result);
	}

	@Test
	@DisplayName("handles diff that removes from beginning")
	void shouldHandleRemovalFromBeginning() throws Exception {
		final String original = "header\nline1\nline2\n";
		final String diff = """
			@@ -1,3 +1,2 @@
			-header
			 line1
			 line2
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("line1\nline2\n", result);
	}

	@Test
	@DisplayName("returns unchanged content for empty diff")
	void shouldReturnUnchangedForEmptyDiff() throws Exception {
		final String original = "unchanged content\n";
		final DiffResult emptyDiff = DiffResult.empty();

		final String result = this.applicator.apply(original, emptyDiff);

		assertEquals(original, result);
	}

	@Test
	@DisplayName("reports detailed mismatch information on failure")
	void shouldReportDetailedMismatchInfo() throws Exception {
		final String original = "line1\nactual\nline3\n";
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-expected
			+new
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final UnifiedDiffApplicator.DiffValidationResult validation =
			this.applicator.validate(original, parsedDiff);

		assertFalse(validation.valid());
		final UnifiedDiffApplicator.ContextMismatch mismatch = validation.mismatches().get(0);
		assertEquals(0, mismatch.hunkIndex());
		assertEquals(2, mismatch.lineNumber());
		assertEquals("expected", mismatch.expected());
		assertEquals("actual", mismatch.actual());
	}

	@Test
	@DisplayName("handles multiple additions in sequence")
	void shouldHandleMultipleAdditionsInSequence() throws Exception {
		final String original = "start\nend\n";
		final String diff = """
			@@ -1,2 +1,5 @@
			 start
			+add1
			+add2
			+add3
			 end
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("start\nadd1\nadd2\nadd3\nend\n", result);
	}

	@Test
	@DisplayName("handles multiple removals in sequence")
	void shouldHandleMultipleRemovalsInSequence() throws Exception {
		final String original = "start\nremove1\nremove2\nremove3\nend\n";
		final String diff = """
			@@ -1,5 +1,2 @@
			 start
			-remove1
			-remove2
			-remove3
			 end
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertEquals("start\nend\n", result);
	}

	@Test
	@DisplayName("validates correctly with valid diff")
	void shouldValidateCorrectlyWithValidDiff() throws Exception {
		final String original = "line1\nline2\nline3\n";
		final String diff = """
			@@ -1,3 +1,3 @@
			 line1
			-line2
			+modified
			 line3
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final UnifiedDiffApplicator.DiffValidationResult validation =
			this.applicator.validate(original, parsedDiff);

		assertTrue(validation.valid());
		assertTrue(validation.mismatches().isEmpty());
	}

	@Test
	@DisplayName("handles real-world markdown translation diff")
	void shouldHandleRealWorldMarkdownDiff() throws Exception {
		final String original = """
			# Introduction

			This is an introduction paragraph.

			## Getting Started

			Follow these steps to begin.
			""";

		final String diff = """
			@@ -1,7 +1,7 @@
			-# Introduction
			+# Einführung

			-This is an introduction paragraph.
			+Dies ist ein Einführungsabsatz.

			-## Getting Started
			+## Erste Schritte

			-Follow these steps to begin.
			+Befolgen Sie diese Schritte, um zu beginnen.
			""";

		final DiffResult parsedDiff = this.parser.parse(diff);
		final String result = this.applicator.apply(original, parsedDiff);

		assertTrue(result.contains("# Einführung"));
		assertTrue(result.contains("Dies ist ein Einführungsabsatz."));
		assertTrue(result.contains("## Erste Schritte"));
		assertTrue(result.contains("Befolgen Sie diese Schritte, um zu beginnen."));
	}
}
