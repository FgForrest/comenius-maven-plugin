package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
