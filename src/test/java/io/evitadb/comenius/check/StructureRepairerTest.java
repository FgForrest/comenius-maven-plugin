package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StructureRepairer should restore headings swallowed by the preceding block")
public class StructureRepairerTest {

	/**
	 * @param body markdown body content
	 * @return number of headings a real markdown parser recognises
	 */
	private static int parsedHeadings(String body) {
		return HeadingAnchorIndex.fromDocument(new MarkdownDocument(body).getDocument()).size();
	}

	@Test
	@DisplayName("leaves a healthy document untouched")
	void shouldLeaveHealthyDocumentUntouched() {
		final String body = "# Title\n\nIntro.\n\n<Note type=\"info\">\n\n<NoteTitle toggles=\"true\">\n\n"
			+ "##### A proper title\n</NoteTitle>\n\nBody.\n\n</Note>\n";

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertFalse(result.isModified());
		assertEquals(body, result.content());
		assertTrue(result.repairs().isEmpty());
		assertTrue(result.unrepaired().isEmpty());
	}

	@Test
	@DisplayName("restores a heading swallowed by a closing tag")
	void shouldRestoreHeadingSwallowedByClosingTag() {
		// exactly the shape the join seam produced: </Note> then a heading, no blank line
		final String body = "# Title\n\n<Note type=\"info\">\n\nText.\n\n</Note>\n## And\n\nMore.\n";

		assertEquals(1, parsedHeadings(body), "precondition: '## And' is swallowed");

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertTrue(result.isModified());
		assertEquals(2, parsedHeadings(result.content()));
		assertEquals(1, result.repairs().size());
		assertEquals("And", result.repairs().get(0).headingText());
		assertTrue(result.content().contains("</Note>\n\n## And"));
	}

	@Test
	@DisplayName("restores a heading used as its own note title")
	void shouldRestoreHeadingUsedAsNoteTitle() {
		final String body = "# Title\n\n<Note type=\"info\">\n\n<NoteTitle toggles=\"true\">\n"
			+ "##### List of matching products\n</NoteTitle>\n\nBody.\n\n</Note>\n";

		assertEquals(1, parsedHeadings(body), "precondition: the note title is swallowed");

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertTrue(result.isModified());
		assertEquals(2, parsedHeadings(result.content()));
		assertTrue(result.content().contains("<NoteTitle toggles=\"true\">\n\n##### List of matching products"));
	}

	@Test
	@DisplayName("restores every swallowed heading in a document with several")
	void shouldRestoreMultipleSwallowedHeadings() {
		final String body = "# Title\n\n<Note>\n\nA.\n\n</Note>\n## One\n\nB.\n\n<Note>\n\nC.\n\n</Note>\n## Two\n\nD.\n";

		assertEquals(1, parsedHeadings(body));

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertEquals(3, parsedHeadings(result.content()));
		assertEquals(2, result.repairs().size());
		assertTrue(result.unrepaired().isEmpty());
	}

	@Test
	@DisplayName("changes nothing but whitespace")
	void shouldChangeNothingButWhitespace() {
		final String body = "# Titel\n\n<Note>\n\nText.\n\n</Note>\n## Abschnitt\n\nInhalt.\n";

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertTrue(result.isModified());
		assertEquals(
			body.replaceAll("\\s+", ""),
			result.content().replaceAll("\\s+", ""),
			"repair must only insert whitespace, never touch wording"
		);
	}

	@Test
	@DisplayName("ignores heading-like comments inside a fenced code block")
	void shouldIgnoreFencedComments() {
		// "# run in foreground" is a shell comment; it is not a heading and must not be
		// "repaired" by inserting a blank line into the middle of the code block
		final String body = "# Title\n\nRun it:\n\n```bash\ndocker pull evitadb\n"
			+ "# run in foreground\ndocker run evitadb\n```\n\nDone.\n";

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertFalse(result.isModified(), "fenced comments must never be treated as headings");
		assertEquals(body, result.content());
	}

	@Test
	@DisplayName("is idempotent")
	void shouldBeIdempotent() {
		final String body = "# Title\n\n<Note>\n\nText.\n\n</Note>\n## Section\n\nContent.\n";

		final String once = StructureRepairer.repair(body).content();
		final StructureRepairer.Result twice = StructureRepairer.repair(once);

		assertFalse(twice.isModified());
		assertEquals(once, twice.content());
	}

	@Test
	@DisplayName("does not report a heading that legitimately follows a paragraph or code fence")
	void shouldNotReportHealthyHeadingsThatFollowNonBlankLines() {
		// Both headings sit directly under a non-blank line, which is the shape a swallowed
		// heading has - but ATX headings may interrupt a paragraph and may follow a closed
		// code fence, so neither is broken and neither may be reported as unrepairable.
		final String body = "# Title\n\nA paragraph.\n## Interrupting heading\n\n"
			+ "```\ncode\n```\n### After a fence\n\nText.\n";

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertEquals(3, parsedHeadings(body), "precondition: all three are already headings");
		assertFalse(result.isModified());
		assertTrue(
			result.unrepaired().isEmpty(),
			"healthy headings must not be reported as unrepairable, got: " + result.unrepaired()
		);
	}

	@Test
	@DisplayName("reports a heading it cannot restore instead of reshaping the document")
	void shouldReportUnrepairableHeadingRatherThanGuess() {
		// A heading indented by four spaces is an indented code block, not a heading. Adding a
		// blank line in front of it does not turn it into one, so the repairer must roll the
		// attempt back and report it rather than keep mutating the document.
		final String body = "# Title\n\nText.\n    ## not really a heading\n\nMore.\n";

		final StructureRepairer.Result result = StructureRepairer.repair(body);

		assertEquals(body, result.content(), "document must be left untouched");
		assertFalse(result.isModified());
	}
}
