package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance for {@link StructuralComparator} - the same comparison validated live in
 * {@code ScopeTreeTranslationProbe}, promoted to main sources.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Structural comparison")
public class StructuralComparatorTest {

	private static final TagVocabulary VOCABULARY =
		TagVocabulary.of(Set.of("Note", "NoteTitle", "LS", "Term"), Set.of(), Set.of(), false);

	@Test
	@DisplayName("reports nothing when structure survived translation intact")
	public void shouldReportNothingWhenStructureIntact() {
		final String before = "# Title\n\n<Note>\n\nSome text with <Term>a term</Term>.\n\n</Note>\n";
		final String after = "# Nadpis\n\n<Note>\n\nNějaký text s <Term>termínem</Term>.\n\n</Note>\n";

		assertTrue(StructuralComparator.compare(VOCABULARY, before, after).isEmpty());
	}

	@Test
	@DisplayName("flags a dropped heading-free block even though heading structure is unaffected")
	public void shouldFlagDroppedHeadingFreeBlock() {
		// this is the real defect this class exists to catch: a whole <LS> block silently
		// dropped by the model, invisible to any heading-only structural check
		final String before = "<Note>\n\n<NoteTitle>\n\n##### Result\n\n</NoteTitle>\n\n"
			+ "Some intro text.\n\n<LS to=\"e\">\n\nEnglish variant.\n\n</LS>\n\n</Note>\n";
		final String after = "<Note>\n\n<NoteTitle>\n\n##### Výsledek\n\n</NoteTitle>\n\n"
			+ "Nějaký úvodní text.\n\n</Note>\n";

		final List<String> problems = StructuralComparator.compare(VOCABULARY, before, after);

		assertTrue(problems.stream().anyMatch(p -> p.contains("block structure changed")));
	}

	@Test
	@DisplayName("flags a change in inline tag multiset")
	public void shouldFlagInlineTagMultisetChange() {
		final String before = "See <Term>certificate</Term> and <Term>authority</Term> here.";
		final String after = "Viz <Term>certifikát</Term> zde.";

		final List<String> problems = StructuralComparator.compare(VOCABULARY, before, after);

		assertTrue(problems.stream().anyMatch(p -> p.contains("inline tags changed")));
	}

	@Test
	@DisplayName("flags a dropped blank line even when tag structure is unaffected")
	public void shouldFlagDroppedBlankLine() {
		final String before = "First paragraph.\n\nSecond paragraph.\n";
		final String after = "První odstavec.\nDruhý odstavec.\n";

		final List<String> problems = StructuralComparator.compare(VOCABULARY, before, after);

		assertEquals(1, problems.size());
		assertTrue(problems.get(0).contains("blank line count changed"));
	}

}
