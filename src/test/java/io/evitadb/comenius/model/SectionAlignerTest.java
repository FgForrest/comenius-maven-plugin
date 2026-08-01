package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SectionAligner should align old and new sections using LCS")
public class SectionAlignerTest {

	@Test
	@DisplayName("shouldReturnAllUnchangedForIdenticalDocuments")
	void shouldReturnAllUnchangedForIdenticalDocuments() {
		final List<DocumentSection> sections = DocumentSectionSplitter.split(
			"# Title\n\nContent.\n\n## Section\n\nMore content.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(sections, sections);

		assertEquals(2, alignments.size());
		for (final SectionAlignment alignment : alignments) {
			assertEquals(SectionAlignment.Type.UNCHANGED, alignment.type());
		}
	}

	@Test
	@DisplayName("shouldDetectModifiedSection")
	void shouldDetectModifiedSection() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# Title\n\nOld content.\n\n## Section\n\nUnchanged.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# Title\n\nNew content.\n\n## Section\n\nUnchanged.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(2, alignments.size());
		// First section modified (different content under same heading)
		assertEquals(SectionAlignment.Type.MODIFIED, alignments.get(0).type());
		assertEquals(0, alignments.get(0).oldIndex());
		assertEquals(0, alignments.get(0).newIndex());
		// Second section unchanged
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(1).type());
		assertEquals(1, alignments.get(1).oldIndex());
		assertEquals(1, alignments.get(1).newIndex());
	}

	@Test
	@DisplayName("shouldDetectAddedSectionInMiddle")
	void shouldDetectAddedSectionInMiddle() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# First\n\nContent 1.\n\n# Third\n\nContent 3.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# First\n\nContent 1.\n\n# Second\n\nContent 2.\n\n# Third\n\nContent 3.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(3, alignments.size());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(0).type());
		assertEquals(SectionAlignment.Type.ADDED, alignments.get(1).type());
		assertEquals(-1, alignments.get(1).oldIndex());
		assertEquals(1, alignments.get(1).newIndex());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(2).type());
	}

	@Test
	@DisplayName("shouldDetectDeletedSection")
	void shouldDetectDeletedSection() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# First\n\nContent 1.\n\n# Middle\n\nTo be removed.\n\n# Last\n\nContent 3.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# First\n\nContent 1.\n\n# Last\n\nContent 3.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(3, alignments.size());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(0).type());
		assertEquals(SectionAlignment.Type.DELETED, alignments.get(1).type());
		assertEquals(1, alignments.get(1).oldIndex());
		assertEquals(-1, alignments.get(1).newIndex());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(2).type());
	}

	@Test
	@DisplayName("shouldHandleComplexScenario")
	void shouldHandleComplexScenario() {
		// Old: A B C D
		// New: A C' E D  (B deleted, C modified, E added)
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# A\n\nAlpha.\n\n# B\n\nBravo.\n\n# C\n\nCharlie.\n\n# D\n\nDelta.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# A\n\nAlpha.\n\n# C\n\nCharlie modified.\n\n# E\n\nEcho.\n\n# D\n\nDelta.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		// A unchanged
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(0).type());
		assertEquals(0, alignments.get(0).oldIndex());
		assertEquals(0, alignments.get(0).newIndex());

		// B (old=1) and C' (new=1) are in the gap between anchors A and D
		// B pairs with C' as MODIFIED
		assertEquals(SectionAlignment.Type.MODIFIED, alignments.get(1).type());
		assertEquals(1, alignments.get(1).oldIndex());
		assertEquals(1, alignments.get(1).newIndex());

		// C (old=2) pairs with E (new=2) as MODIFIED
		assertEquals(SectionAlignment.Type.MODIFIED, alignments.get(2).type());
		assertEquals(2, alignments.get(2).oldIndex());
		assertEquals(2, alignments.get(2).newIndex());

		// D unchanged
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(3).type());
		assertEquals(3, alignments.get(3).oldIndex());
		assertEquals(3, alignments.get(3).newIndex());
	}

	@Test
	@DisplayName("shouldHandleCompleteRewrite")
	void shouldHandleCompleteRewrite() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# Old Title\n\nOld content.\n\n## Old Section\n\nOld stuff.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# New Title\n\nNew content.\n\n## New Section\n\nNew stuff.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(2, alignments.size());
		// No matching hashes, so all paired as MODIFIED
		for (final SectionAlignment alignment : alignments) {
			assertEquals(SectionAlignment.Type.MODIFIED, alignment.type());
		}
	}

	@Test
	@DisplayName("shouldHandleEmptyOldDocument")
	void shouldHandleEmptyOldDocument() {
		final List<DocumentSection> oldSections = List.of();
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# New\n\nBrand new content.\n\n## Also New\n\nMore new.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(2, alignments.size());
		for (final SectionAlignment alignment : alignments) {
			assertEquals(SectionAlignment.Type.ADDED, alignment.type());
			assertEquals(-1, alignment.oldIndex());
		}
	}

	@Test
	@DisplayName("shouldHandleEmptyNewDocument")
	void shouldHandleEmptyNewDocument() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# Old\n\nContent.\n\n## Also Old\n\nMore.\n"
		);
		final List<DocumentSection> newSections = List.of();

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(2, alignments.size());
		for (final SectionAlignment alignment : alignments) {
			assertEquals(SectionAlignment.Type.DELETED, alignment.type());
			assertEquals(-1, alignment.newIndex());
		}
	}

	@Test
	@DisplayName("shouldHandleBothEmpty")
	void shouldHandleBothEmpty() {
		final List<SectionAlignment> alignments = SectionAligner.align(List.of(), List.of());

		assertTrue(alignments.isEmpty());
	}

	@Test
	@DisplayName("shouldHandleReorderedSections")
	void shouldHandleReorderedSections() {
		// Old: A B C — New: C B A
		// LCS will find one match (B stays in common), rest are gaps
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# A\n\nAlpha.\n\n# B\n\nBravo.\n\n# C\n\nCharlie.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# C\n\nCharlie.\n\n# B\n\nBravo.\n\n# A\n\nAlpha.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		// Should have some UNCHANGED, and the rest are MODIFIED/ADDED/DELETED
		// LCS should find at least one match
		final long unchangedCount = alignments.stream()
			.filter(a -> a.type() == SectionAlignment.Type.UNCHANGED)
			.count();
		assertTrue(unchangedCount >= 1, "Should find at least one unchanged section");
	}

	@Test
	@DisplayName("shouldPreserveCorrectIndices")
	void shouldPreserveCorrectIndices() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# Shared\n\nSame.\n\n# Old Only\n\nRemoved.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# New First\n\nAdded.\n\n# Shared\n\nSame.\n"
		);

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		// Verify indices reference valid positions
		for (final SectionAlignment alignment : alignments) {
			if (alignment.oldIndex() >= 0) {
				assertTrue(alignment.oldIndex() < oldSections.size());
			}
			if (alignment.newIndex() >= 0) {
				assertTrue(alignment.newIndex() < newSections.size());
			}
			if (alignment.type() == SectionAlignment.Type.ADDED) {
				assertEquals(-1, alignment.oldIndex());
			}
			if (alignment.type() == SectionAlignment.Type.DELETED) {
				assertEquals(-1, alignment.newIndex());
			}
		}
	}

	@Test
	@DisplayName("shouldRejectNullInputs")
	void shouldRejectNullInputs() {
		final List<DocumentSection> sections = List.of();

		assertThrows(NullPointerException.class, () ->
			SectionAligner.align(null, sections)
		);
		assertThrows(NullPointerException.class, () ->
			SectionAligner.align(sections, null)
		);
	}

	@Test
	@DisplayName("shouldHandleSingleSectionUnchanged")
	void shouldHandleSingleSectionUnchanged() {
		final List<DocumentSection> sections = DocumentSectionSplitter.split("# Only\n\nContent.\n");

		final List<SectionAlignment> alignments = SectionAligner.align(sections, sections);

		assertEquals(1, alignments.size());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(0).type());
		assertEquals(0, alignments.get(0).oldIndex());
		assertEquals(0, alignments.get(0).newIndex());
	}

	@Test
	@DisplayName("shouldHandleSingleSectionModified")
	void shouldHandleSingleSectionModified() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split("# Title\n\nOld.\n");
		final List<DocumentSection> newSections = DocumentSectionSplitter.split("# Title\n\nNew.\n");

		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		assertEquals(1, alignments.size());
		assertEquals(SectionAlignment.Type.MODIFIED, alignments.get(0).type());
	}

	/**
	 * {@link SectionAligner#align(List, List)} is a thin wrapper that extracts
	 * {@link DocumentSection#contentHash()} and delegates to {@link SectionAligner#alignByHash}.
	 * This pins that the hash-based core alone reproduces the same classifications, since it is
	 * the entry point a non-{@link DocumentSection} caller (e.g. one aligning
	 * {@link io.evitadb.comenius.structure.TranslationUnit}s) has to use instead.
	 */
	@Test
	@DisplayName("alignByHash reproduces the same classifications as the DocumentSection overload")
	void shouldAlignByHashDirectly() {
		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(
			"# First\n\nOld content.\n\n# Second\n\nUnchanged.\n"
		);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(
			"# First\n\nNew content.\n\n# Second\n\nUnchanged.\n\n# Third\n\nAdded.\n"
		);

		final List<SectionAlignment> expected = SectionAligner.align(oldSections, newSections);
		final List<SectionAlignment> actual = SectionAligner.alignByHash(
			oldSections.stream().map(DocumentSection::contentHash).toList(),
			newSections.stream().map(DocumentSection::contentHash).toList()
		);

		assertEquals(expected, actual);
		assertEquals(3, actual.size());
		assertEquals(SectionAlignment.Type.MODIFIED, actual.get(0).type());
		assertEquals(SectionAlignment.Type.UNCHANGED, actual.get(1).type());
		assertEquals(SectionAlignment.Type.ADDED, actual.get(2).type());
	}

	@Test
	@DisplayName("alignByHash treats hash collisions between unrelated items as a match, same as the section overload")
	void shouldAlignByHashUsingOnlyTheHashValue() {
		// alignByHash has no notion of "what a hash represents" - two unrelated items sharing a
		// hash string are indistinguishable from the same item appearing twice, exactly like two
		// DocumentSections with identical normalized content are today
		final List<SectionAlignment> alignments = SectionAligner.alignByHash(
			List.of("h1", "h2"), List.of("h1", "h3", "h2")
		);

		assertEquals(3, alignments.size());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(0).type());
		assertEquals(SectionAlignment.Type.ADDED, alignments.get(1).type());
		assertEquals(1, alignments.get(1).newIndex());
		assertEquals(SectionAlignment.Type.UNCHANGED, alignments.get(2).type());
	}
}
