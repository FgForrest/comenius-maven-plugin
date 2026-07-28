package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance for {@link UntranslatedContentChecker}.
 *
 * Exists because of a reproduced defect: a model can leave a tag's inner text exactly as it was
 * in the source - still in the source language - while every structural check (tag sequence,
 * inline multiset, blank-line count) reports the fragment as intact, because none of them look at
 * what the text actually says. This is a heuristic, not a language detector: it flags a leaf tag
 * whose translated content is byte-for-byte (case/whitespace-insensitive) identical to some
 * source occurrence of the same tag name, while staying quiet about the things that are supposed
 * to echo - code spans and short technical acronyms.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Untranslated content detection")
public class UntranslatedContentCheckerTest {

	private static final TagVocabulary VOCABULARY = TagVocabulary.of(
		Set.of("Term", "SourceClass"), Set.of(), Set.of("SourceClass"), false
	);

	@Test
	@DisplayName("flags a leaf tag whose content was left in the source language")
	public void shouldFlagUntranslatedLeafContent() {
		final String source = "Trust the <Term>certificate authority</Term> that issued it.";
		final String translated = "Důvěřujte <Term>certificate authority</Term>, která jej vydala.";

		final List<UntranslatedContentChecker.Suspect> suspects =
			UntranslatedContentChecker.find(VOCABULARY, source, translated);

		assertEquals(1, suspects.size());
		assertEquals("Term", suspects.get(0).tagName());
		assertEquals("certificate authority", suspects.get(0).text());
	}

	@Test
	@DisplayName("does not flag content that was actually translated")
	public void shouldNotFlagTranslatedContent() {
		final String source = "Trust the <Term>certificate authority</Term> that issued it.";
		final String translated = "Důvěřujte <Term>certifikační autoritě</Term>, která jej vydala.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag a short technical acronym even when it stays identical")
	public void shouldNotFlagShortAcronyms() {
		final String source = "Configure <Term>TLS</Term> before going live.";
		final String translated = "Před nasazením nakonfigurujte <Term>TLS</Term>.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag a code span even when it stays identical and is long")
	public void shouldNotFlagCodeSpans() {
		final String source = "Run <Term>`-newkey cipher:bits`</Term> to pick the cipher.";
		final String translated = "Ke zvoleni sifry spustte <Term>`-newkey cipher:bits`</Term>.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag an opaque tag even when it stays identical")
	public void shouldNotFlagOpaqueTags() {
		final String source = "See <SourceClass>io/evitadb/core/Evita.java</SourceClass> for details.";
		final String translated = "Podrobnosti viz <SourceClass>io/evitadb/core/Evita.java</SourceClass>.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag a leaf whose content is a markdown link, even when long and identical")
	public void shouldNotFlagMarkdownLinks() {
		// real corpus pattern (query/filtering/comparable.md): a language-switch span whose whole
		// leaf content is a link to external API docs - the URL, and often the whole link, is
		// supposed to stay exactly as it is; only the surrounding prose changes
		final String source = "Compare two <Term>**[String](https://docs.oracle.com/javase/17/docs/api/"
			+ "java.base/java/lang/String.html)**</Term> values.";
		final String translated = "Porovnejte dvě hodnoty <Term>**[String](https://docs.oracle.com/javase/17/"
			+ "docs/api/java.base/java/lang/String.html)**</Term>.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag a plain, unwrapped markdown link either")
	public void shouldNotFlagPlainMarkdownLinks() {
		final String source = "Uses the correct <Term>[collator](https://docs.oracle.com/en/java/javase/17/"
			+ "docs/api/java.base/java/text/Collator.html)</Term> to compare.";
		final String translated = "Používá správný <Term>[collator](https://docs.oracle.com/en/java/javase/17/"
			+ "docs/api/java.base/java/text/Collator.html)</Term> k porovnání.";

		assertTrue(UntranslatedContentChecker.find(VOCABULARY, source, translated).isEmpty());
	}

	@Test
	@DisplayName("does not flag a tag with nested markup - only genuine leaves are checked")
	public void shouldNotFlagTagsWithNestedMarkup() {
		final String source = "See <Term>the <Term>certificate authority</Term> concept</Term>.";
		final String translated = "Viz <Term>the <Term>certificate authority</Term> concept</Term>.";

		// the outer Term is not a leaf (it has a nested Term inside), so only the inner one - a
		// genuine leaf - is eligible to be flagged
		final List<UntranslatedContentChecker.Suspect> suspects =
			UntranslatedContentChecker.find(VOCABULARY, source, translated);
		assertEquals(1, suspects.size());
		assertEquals("certificate authority", suspects.get(0).text());
	}

	@Test
	@DisplayName("reports an offset usable to splice a replacement into the translated string")
	public void shouldReportSpliceableOffsets() {
		final String source = "Trust the <Term>certificate authority</Term> that issued it.";
		final String translated = "Důvěřujte <Term>certificate authority</Term>, která jej vydala.";

		final UntranslatedContentChecker.Suspect suspect =
			UntranslatedContentChecker.find(VOCABULARY, source, translated).get(0);

		assertEquals(
			"certificate authority",
			translated.substring(suspect.start(), suspect.end())
		);
	}

}
