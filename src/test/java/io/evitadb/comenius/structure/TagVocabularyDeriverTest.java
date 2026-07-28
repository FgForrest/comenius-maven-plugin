package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rule that makes zero-configuration operation safe: telling a component with a
 * forgotten closer apart from a Java generic that was never markup in the first place.
 *
 * Getting this wrong in either direction is expensive. Treat every unpaired name as a defect and
 * any project writing `List&lt;Entity&gt;` in prose cannot build; treat every unpaired name as
 * prose and a genuinely forgotten closer is silently absorbed, which is the failure this whole
 * design exists to remove.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Tag vocabulary derivation")
public class TagVocabularyDeriverTest {

	@Test
	@DisplayName("classifies a name that never pairs anywhere as prose")
	public void shouldNotTreatNameAsMarkupWhenItNeverPairsInTheCorpus() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("a.md", "The method returns a <SealedEntity> instance and nothing closes it.\n");
		deriver.add("b.md", "Another <SealedEntity> mention, still unpaired.\n");

		final TagVocabulary vocabulary = deriver.derive(Set.of(), Set.of(), false);

		assertFalse(
			vocabulary.isStructural("SealedEntity"),
			"a name with no evidence of being a container is prose, not a defect"
		);
	}

	@Test
	@DisplayName("reports a name that pairs elsewhere but not everywhere")
	public void shouldThrowWhenMarkupNameIsUnbalancedInOneDocument() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("good.md", "<Note>\n\nbody\n\n</Note>\n");
		deriver.add("bad.md", "intro\n\n<Note>\n\nforgotten closer\n");

		final UnbalancedMarkupException exception = assertThrows(
			UnbalancedMarkupException.class, () -> deriver.derive(Set.of(), Set.of(), false)
		);
		assertEquals(1, exception.getDefects().size());
		assertEquals("Note", exception.getDefects().get(0).name());
		assertTrue(
			exception.getMessage().contains("bad.md"),
			"the message must name the document that needs fixing"
		);
	}

	@Test
	@DisplayName("derives every balanced name across the whole corpus")
	public void shouldCollectStructuralNamesWhenTheyPairInAnyDocument() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("a.md", "<Note>\n\nbody\n\n</Note>\n");
		deriver.add("b.md", "Text with a <Term>term</Term> inside.\n");
		deriver.add("c.md", "<LS to=\"j\">\n\njava\n\n</LS>\n");

		final TagVocabulary vocabulary = deriver.derive(Set.of(), Set.of(), false);

		assertTrue(vocabulary.isStructural("Note"));
		assertTrue(vocabulary.isStructural("Term"));
		assertTrue(vocabulary.isStructural("LS"));
	}

	@Test
	@DisplayName("degrades a defect to prose when lenient")
	public void shouldNotThrowWhenUnbalancedAndLenient() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("good.md", "<Note>\n\nbody\n\n</Note>\n");
		deriver.add("bad.md", "<Note>\n\nforgotten closer\n");

		final TagVocabulary vocabulary = deriver.derive(Set.of(), Set.of(), true);

		assertTrue(vocabulary.isStructural("Note"));
		assertTrue(vocabulary.isLenient());
	}

	@Test
	@DisplayName("never sees tags that live inside code")
	public void shouldIgnoreOccurrencesWhenTheyAreFencedOrBackticked() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("a.md", "```java\nList<Foo> list = new ArrayList<>();\n```\n\nAnd `<Bar>` inline.\n");

		final TagVocabulary vocabulary = deriver.derive(Set.of(), Set.of(), false);

		assertTrue(vocabulary.getStructuralTags().isEmpty());
		assertTrue(deriver.getStatistics().isEmpty(), "code is not evidence of anything");
	}

	@Test
	@DisplayName("merges configured atomic names with the generic HTML defaults")
	public void shouldKeepDefaultAtomicTagsWhenExtraNamesAreConfigured() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("a.md", "<Table>\n<Tr>\n<Td>x</Td>\n</Tr>\n</Table>\n");

		final TagVocabulary vocabulary = deriver.derive(Set.of("Table", "Tr"), Set.of(), false);

		assertTrue(vocabulary.isAtomic("Table"), "configured, capitalised component");
		assertTrue(vocabulary.isAtomic("table"), "generic lowercase HTML default");
		assertFalse(vocabulary.isAtomic("Td"), "not configured and not an HTML default");
	}

	@Test
	@DisplayName("renders a pasteable configuration block")
	public void shouldSuggestConfigurationWhenCorpusHasBeenScanned() {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		deriver.add("a.md", "<Note>\n\nbody\n\n</Note>\n\n<Term>t</Term>\n");

		final String suggestion = deriver.suggestConfiguration(Set.of("Table"), Set.of("SourceClass"));

		assertTrue(suggestion.contains("<structuralTags>Note,Term</structuralTags>"));
		assertTrue(suggestion.contains("<atomicTags>Table</atomicTags>"));
		assertTrue(suggestion.contains("<opaqueTags>SourceClass</opaqueTags>"));
	}

}
