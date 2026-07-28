package io.evitadb.comenius.model;

import io.evitadb.comenius.structure.MarkupScanner;
import io.evitadb.comenius.structure.TagBalance;
import io.evitadb.comenius.structure.TagVocabulary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DocumentSectionSplitter should split markdown into heading-delimited sections")
public class DocumentSectionSplitterTest {

	@Test
	@DisplayName("shouldSplitSingleHeadingDocument")
	void shouldSplitSingleHeadingDocument() {
		final String body = "# Hello\n\nSome content here.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(1, sections.size());
		final DocumentSection section = sections.get(0);
		assertEquals(0, section.index());
		assertEquals(1, section.headingLevel());
		assertEquals("Hello", section.headingText());
		assertEquals(body, section.content());
		assertFalse(section.isIntro());
	}

	@Test
	@DisplayName("shouldSplitMultipleHeadingsOfMixedLevels")
	void shouldSplitMultipleHeadingsOfMixedLevels() {
		final String body = "# Title\n\nIntro text.\n\n## Section One\n\nContent one.\n\n### Subsection\n\nDetails.\n\n## Section Two\n\nContent two.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(4, sections.size());

		assertEquals(1, sections.get(0).headingLevel());
		assertEquals("Title", sections.get(0).headingText());

		assertEquals(2, sections.get(1).headingLevel());
		assertEquals("Section One", sections.get(1).headingText());

		assertEquals(3, sections.get(2).headingLevel());
		assertEquals("Subsection", sections.get(2).headingText());

		assertEquals(2, sections.get(3).headingLevel());
		assertEquals("Section Two", sections.get(3).headingText());
	}

	@Test
	@DisplayName("shouldCreateIntroSectionForContentBeforeFirstHeading")
	void shouldCreateIntroSectionForContentBeforeFirstHeading() {
		final String body = "Some intro text.\n\nMore intro.\n\n# First Heading\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(2, sections.size());

		final DocumentSection intro = sections.get(0);
		assertEquals(0, intro.index());
		assertEquals(0, intro.headingLevel());
		assertNull(intro.headingText());
		assertTrue(intro.isIntro());
		assertTrue(intro.content().contains("Some intro text."));

		final DocumentSection heading = sections.get(1);
		assertEquals(1, heading.index());
		assertEquals(1, heading.headingLevel());
		assertEquals("First Heading", heading.headingText());
	}

	@Test
	@DisplayName("shouldHandleDocumentWithNoHeadings")
	void shouldHandleDocumentWithNoHeadings() {
		final String body = "Just plain text.\n\nNo headings here.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(1, sections.size());
		final DocumentSection section = sections.get(0);
		assertEquals(0, section.headingLevel());
		assertNull(section.headingText());
		assertTrue(section.isIntro());
		assertEquals(body, section.content());
	}

	@Test
	@DisplayName("shouldReturnEmptyListForEmptyBody")
	void shouldReturnEmptyListForEmptyBody() {
		final List<DocumentSection> sections = DocumentSectionSplitter.split("");

		assertTrue(sections.isEmpty());
	}

	@Test
	@DisplayName("shouldAssignSequentialIndices")
	void shouldAssignSequentialIndices() {
		final String body = "Intro.\n\n# One\n\nA.\n\n## Two\n\nB.\n\n# Three\n\nC.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		for (int i = 0; i < sections.size(); i++) {
			assertEquals(i, sections.get(i).index(),
				"Section at position " + i + " should have index " + i);
		}
	}

	@Test
	@DisplayName("shouldProduceDeterministicHashesForSameContent")
	void shouldProduceDeterministicHashesForSameContent() {
		final String body = "# Title\n\nContent.\n";

		final List<DocumentSection> first = DocumentSectionSplitter.split(body);
		final List<DocumentSection> second = DocumentSectionSplitter.split(body);

		assertEquals(first.size(), second.size());
		for (int i = 0; i < first.size(); i++) {
			assertEquals(first.get(i).contentHash(), second.get(i).contentHash());
		}
	}

	@Test
	@DisplayName("shouldProduceDifferentHashesForDifferentContent")
	void shouldProduceDifferentHashesForDifferentContent() {
		final List<DocumentSection> original = DocumentSectionSplitter.split("# Title\n\nOriginal.\n");
		final List<DocumentSection> modified = DocumentSectionSplitter.split("# Title\n\nModified.\n");

		assertEquals(1, original.size());
		assertEquals(1, modified.size());
		assertNotEquals(original.get(0).contentHash(), modified.get(0).contentHash());
	}

	@Test
	@DisplayName("shouldNormalizeLineEndingsForHashComputation")
	void shouldNormalizeLineEndingsForHashComputation() {
		final String hash1 = DocumentSectionSplitter.computeHash("line1\nline2\n");
		final String hash2 = DocumentSectionSplitter.computeHash("line1\r\nline2\r\n");

		assertEquals(hash1, hash2, "CRLF and LF should produce same hash");
	}

	@Test
	@DisplayName("shouldTrimWhitespaceForHashComputation")
	void shouldTrimWhitespaceForHashComputation() {
		final String hash1 = DocumentSectionSplitter.computeHash("content");
		final String hash2 = DocumentSectionSplitter.computeHash("  content  \n");

		assertEquals(hash1, hash2, "Trimmed content should produce same hash");
	}

	@Test
	@DisplayName("shouldHandleAllHeadingLevels")
	void shouldHandleAllHeadingLevels() {
		final String body = "# H1\n\n## H2\n\n### H3\n\n#### H4\n\n##### H5\n\n###### H6\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(6, sections.size());
		for (int i = 0; i < 6; i++) {
			assertEquals(i + 1, sections.get(i).headingLevel());
		}
	}

	@Test
	@DisplayName("shouldSkipBlankIntroContent")
	void shouldSkipBlankIntroContent() {
		final String body = "\n\n# Heading\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(1, sections.size());
		assertEquals(1, sections.get(0).headingLevel());
	}

	@Test
	@DisplayName("shouldRejectNullInput")
	void shouldRejectNullInput() {
		assertThrows(NullPointerException.class, () ->
			DocumentSectionSplitter.split(null)
		);
	}

	// --- Heading structure validation tests ---

	@Test
	@DisplayName("shouldPassValidationWhenStructuresMatch")
	void shouldPassValidationWhenStructuresMatch() {
		final List<DocumentSection> source = DocumentSectionSplitter.split(
			"# Title\n\nContent.\n\n## Section\n\nMore.\n"
		);
		final List<DocumentSection> translated = DocumentSectionSplitter.split(
			"# Titel\n\nInhalt.\n\n## Abschnitt\n\nMehr.\n"
		);

		assertDoesNotThrow(() ->
			DocumentSectionSplitter.validateHeadingStructure(source, translated)
		);
	}

	@Test
	@DisplayName("shouldFailValidationWhenSectionCountDiffers")
	void shouldFailValidationWhenSectionCountDiffers() {
		final List<DocumentSection> source = DocumentSectionSplitter.split(
			"# One\n\n## Two\n"
		);
		final List<DocumentSection> translated = DocumentSectionSplitter.split(
			"# Eins\n"
		);

		final HeadingStructureMismatchException ex = assertThrows(
			HeadingStructureMismatchException.class,
			() -> DocumentSectionSplitter.validateHeadingStructure(source, translated)
		);
		assertTrue(ex.getMessage().contains("Section count mismatch"));
		assertNotNull(ex.getExpectedStructure());
		assertNotNull(ex.getActualStructure());
	}

	@Test
	@DisplayName("shouldFailValidationWhenHeadingLevelsDiffer")
	void shouldFailValidationWhenHeadingLevelsDiffer() {
		final List<DocumentSection> source = DocumentSectionSplitter.split(
			"# Title\n\n## Section\n"
		);
		final List<DocumentSection> translated = DocumentSectionSplitter.split(
			"# Titel\n\n### Abschnitt\n"
		);

		final HeadingStructureMismatchException ex = assertThrows(
			HeadingStructureMismatchException.class,
			() -> DocumentSectionSplitter.validateHeadingStructure(source, translated)
		);
		assertTrue(ex.getMessage().contains("Heading level mismatch"));
	}

	@Test
	@DisplayName("shouldPassValidationWithIntroSections")
	void shouldPassValidationWithIntroSections() {
		final List<DocumentSection> source = DocumentSectionSplitter.split(
			"Intro text.\n\n# Heading\n\nContent.\n"
		);
		final List<DocumentSection> translated = DocumentSectionSplitter.split(
			"Einleitung.\n\n# Ueberschrift\n\nInhalt.\n"
		);

		assertDoesNotThrow(() ->
			DocumentSectionSplitter.validateHeadingStructure(source, translated)
		);
	}

	@Test
	@DisplayName("shouldPassValidationForEmptySections")
	void shouldPassValidationForEmptySections() {
		final List<DocumentSection> empty = List.of();

		assertDoesNotThrow(() ->
			DocumentSectionSplitter.validateHeadingStructure(empty, empty)
		);
	}

	// --- vocabulary-aware splitting: split(String, TagVocabulary) ---

	private static final TagVocabulary VOCABULARY = TagVocabulary.of(
		Set.of("LS", "Note", "NoteTitle"), Set.of(), Set.of(), false
	);

	@Test
	@DisplayName("shouldFallBackToPlainSplitWhenVocabularyIsNull")
	void shouldFallBackToPlainSplitWhenVocabularyIsNull() {
		final String body = "# Title\n\n<LS to=\"e\">\n\n## Heading\n\nContent.\n\n</LS>\n";

		final List<DocumentSection> withoutVocabulary = DocumentSectionSplitter.split(body);
		final List<DocumentSection> nullVocabulary = DocumentSectionSplitter.split(body, null);

		assertEquals(withoutVocabulary, nullVocabulary);
	}

	@Test
	@DisplayName("shouldAbsorbTagThatOpensImmediatelyBeforeAHeadingIntoThatHeadingsSection")
	void shouldAbsorbTagThatOpensImmediatelyBeforeAHeadingIntoThatHeadingsSection() {
		// mirrors the real get-started/query-our-dataset.md defect: <LS> opens right before a
		// heading, closes before the next one - naive heading-offset splitting strands the open
		// tag in the previous section.
		final String body = "## Run your own server\n\nSome content.\n\n"
			+ "<LS to=\"e,j\">\n\n## Connect the Java client\n\nJava content.\n\n</LS>\n\n"
			+ "<LS to=\"c\">\n\n## Connect the C# client\n\nC# content.\n\n</LS>\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body, VOCABULARY);

		assertEquals(3, sections.size());
		assertFalse(sections.get(0).content().contains("<LS"));
		assertTrue(sections.get(1).content().contains("<LS to=\"e,j\">"));
		assertTrue(sections.get(1).content().contains("</LS>"));
		assertEquals("Connect the Java client", sections.get(1).headingText());
		assertTrue(sections.get(2).content().contains("<LS to=\"c\">"));
		assertTrue(sections.get(2).content().contains("</LS>"));
		assertEquals("Connect the C# client", sections.get(2).headingText());
	}

	@Test
	@DisplayName("shouldKeepNoteWithHeadingAsItsOwnTitleInOneSection")
	void shouldKeepNoteWithHeadingAsItsOwnTitleInOneSection() {
		// mirrors the real query/requirements/hierarchy.md defect: a heading is used as a Note's
		// own title, nested directly inside <Note><NoteTitle> - naive splitting cuts right at
		// that heading, tearing the enclosing Note in half.
		final String body = "## Level\n\n<Note type=\"info\">\n\nIntro content.\n\n</Note>\n\n"
			+ "<Note type=\"info\">\n\n<NoteTitle toggles=\"true\">\n"
			+ "##### Why would I use this?\n</NoteTitle>\n\nExplanation.\n\n</Note>\n\n"
			+ "## Next Section\n\nMore content.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body, VOCABULARY);

		final DocumentSection noteSection = sections.stream()
			.filter(s -> s.content().contains("Why would I use this?"))
			.findFirst()
			.orElseThrow();
		assertTrue(noteSection.content().contains("<Note type=\"info\">"));
		assertTrue(noteSection.content().contains("<NoteTitle toggles=\"true\">"));
		assertTrue(noteSection.content().contains("</NoteTitle>"));
		assertTrue(noteSection.content().contains("</Note>"));

		for (final DocumentSection section : sections) {
			assertTrue(
				TagBalance.match(
					new MarkupScanner(VOCABULARY).scan(section.content())
				).isBalanced(),
				"section not self-balanced: " + section.content()
			);
		}
	}

	@Test
	@DisplayName("shouldMergeHeadingsForwardWhenATagWrapsMultipleHeadings")
	void shouldMergeHeadingsForwardWhenATagWrapsMultipleHeadings() {
		// a single wrapper spanning two headings - the shift-back rule alone cannot fix this
		// (there is no dangling open immediately before the second heading), so the boundary at
		// the second heading must be dropped and the section grown until it closes.
		final String body = "<LS to=\"e\">\n\n## First\n\nContent A.\n\n## Second\n\nContent B.\n\n</LS>\n\n"
			+ "## Third\n\nContent C.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body, VOCABULARY);

		assertEquals(2, sections.size());
		assertTrue(sections.get(0).content().contains("## First"));
		assertTrue(sections.get(0).content().contains("## Second"));
		assertTrue(sections.get(0).content().contains("</LS>"));
		assertEquals("Third", sections.get(1).headingText());

		for (final DocumentSection section : sections) {
			assertTrue(
				TagBalance.match(
					new MarkupScanner(VOCABULARY).scan(section.content())
				).isBalanced(),
				"section not self-balanced: " + section.content()
			);
		}
	}

	@Test
	@DisplayName("does not split on shell comments inside a fenced code block")
	void shouldNotTreatFencedCommentsAsHeadings() {
		// "# run in foreground" is a shell comment, not a heading - splitting there would cut
		// the section in half through the middle of the code fence.
		final String body = "## Docker\n\nRun it:\n\n"
			+ "```bash\n"
			+ "# run in foreground, destroy the container on exit\n"
			+ "## also just a comment\n"
			+ "docker run evitadb\n"
			+ "```\n\n"
			+ "## Configuration\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(2, sections.size(), "fenced comments must not open new sections");
		assertEquals("Docker", sections.get(0).headingText());
		assertEquals("Configuration", sections.get(1).headingText());
		assertTrue(
			sections.get(0).content().contains("docker run evitadb"),
			"the code fence must stay intact inside its section"
		);
	}

	@Test
	@DisplayName("does not split on fenced comments in the vocabulary-aware overload either")
	void shouldNotTreatFencedCommentsAsHeadingsWithVocabulary() {
		final String body = "## Docker\n\nRun it:\n\n"
			+ "```bash\n"
			+ "# run in foreground\n"
			+ "docker run evitadb\n"
			+ "```\n\n"
			+ "## Configuration\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body, VOCABULARY);

		assertEquals(2, sections.size(), "fenced comments must not open new sections");
		assertEquals("Docker", sections.get(0).headingText());
		assertEquals("Configuration", sections.get(1).headingText());
	}

	@Test
	@DisplayName("still splits on a heading that follows a closed code fence")
	void shouldSplitOnHeadingAfterFenceIsClosed() {
		final String body = "## One\n\n```\n# not a heading\n```\n\n## Two\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(2, sections.size());
		assertEquals("One", sections.get(0).headingText());
		assertEquals("Two", sections.get(1).headingText());
	}

	@Test
	@DisplayName("treats a shorter inner fence marker as content, not as a fence terminator")
	void shouldRequireClosingFenceAtLeastAsLongAsTheOpeningOne() {
		// The ``` line inside a ````-fenced block does not close it, so the heading-looking
		// line after it is still fenced content.
		final String body = "## One\n\n````\n```\n# still fenced\n```\n````\n\n## Two\n\nContent.\n";

		final List<DocumentSection> sections = DocumentSectionSplitter.split(body);

		assertEquals(2, sections.size());
		assertEquals("One", sections.get(0).headingText());
		assertEquals("Two", sections.get(1).headingText());
	}
}
