package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance for {@link TagCaseRepairer}.
 *
 * Exists because of a reproduced defect: a model closing `&lt;Td&gt;` as `&lt;/td&gt;` does not
 * produce a mismatched tag the balance check can name - the closing token simply is not a tag at
 * all under a case-sensitive vocabulary, which desynchronizes every pairing after it. A prompt
 * instruction to preserve case was measured to be probabilistic (fixed one run, recurred on the
 * next with an identical instruction), so the repair here is purely mechanical: the correct casing
 * is already known from the source and is restored without any model call.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Tag case repair")
public class TagCaseRepairerTest {

	/** Models the real corpus: capitalized component tags, no lowercase counterpart exists. */
	private static final TagVocabulary VOCABULARY =
		TagVocabulary.of(Set.of("Td", "Term", "Br"), Set.of(), Set.of(), false);

	/** No corpus-wide evidence at all - isolates the per-fragment ambiguity guard alone. */
	private static final TagVocabulary EMPTY_VOCABULARY =
		TagVocabulary.of(Set.of(), Set.of(), Set.of(), false);

	@Test
	@DisplayName("restores a closing tag's case to match its source occurrence")
	public void shouldRepairLowercaseClosingTag() {
		final String source = "<Td>certificate authority</Td>";
		final String translated = "<Td>certifikační autorita</td>";

		final String repaired = TagCaseRepairer.repair(VOCABULARY, source, translated);

		assertEquals("<Td>certifikační autorita</Td>", repaired);
	}

	@Test
	@DisplayName("restores an opening tag's case to match its source occurrence")
	public void shouldRepairLowercaseOpeningTag() {
		final String source = "<Td>certificate authority</Td>";
		final String translated = "<td>certifikační autorita</Td>";

		final String repaired = TagCaseRepairer.repair(VOCABULARY, source, translated);

		assertEquals("<Td>certifikační autorita</Td>", repaired);
	}

	@Test
	@DisplayName("reports the fix with offsets that splice cleanly into the translated string")
	public void shouldReportSpliceableFix() {
		final String source = "<Td>certificate authority</Td>";
		final String translated = "<Td>certifikační autorita</td>";

		final List<TagCaseRepairer.Fix> fixes = TagCaseRepairer.find(VOCABULARY, source, translated);

		assertEquals(1, fixes.size());
		final TagCaseRepairer.Fix fix = fixes.get(0);
		assertEquals("td", fix.from());
		assertEquals("Td", fix.to());
		assertEquals("td", translated.substring(fix.nameStart(), fix.nameEnd()));
	}

	@Test
	@DisplayName("leaves a fold alone when this unit's own source uses it with two distinct casings")
	public void shouldNotRepairFoldAmbiguousWithinFragment() {
		// literal lowercase HTML <tr> and a capitalized <Tr> component coexist in this fragment
		// alone - with no corpus-wide vocabulary evidence either way, there is no basis to guess
		final String source = "<tr>raw html</tr> and <Tr>component</Tr>";
		final String translated = "<tr>syrové html</tr> a <tr>komponenta</tr>";

		assertTrue(TagCaseRepairer.find(EMPTY_VOCABULARY, source, translated).isEmpty());
		assertEquals(translated, TagCaseRepairer.repair(EMPTY_VOCABULARY, source, translated));
	}

	@Test
	@DisplayName(
		"leaves a translated tag alone when the corpus recognises it as a distinct tag in its own " +
			"right, even though this unit's own source looks unambiguous"
	)
	public void shouldNotRepairWhenCorpusRecognisesBothCasings() {
		// this unit's own source only ever shows the lowercase, literal-HTML form, so a purely
		// per-fragment view would confidently "fix" a translated <Tr> down to <tr> - but the
		// corpus-derived vocabulary knows from OTHER documents that <Tr> is a legitimate, distinct
		// component, and that corpus-wide evidence must win over this one unit's local picture
		final String source = "<tr>raw html row</tr>";
		final TagVocabulary corpusVocabulary = TagVocabulary.of(Set.of("tr", "Tr"), Set.of(), Set.of(), false);
		final String translated = "<Tr>komponenta</Tr>";

		assertTrue(TagCaseRepairer.find(corpusVocabulary, source, translated).isEmpty());
		assertEquals(translated, TagCaseRepairer.repair(corpusVocabulary, source, translated));
	}

	@Test
	@DisplayName("leaves a name alone that never occurs in the source at all")
	public void shouldNotRepairUnknownName() {
		final String source = "<Td>certificate authority</Td>";
		final String translated = "<Td>certifikační autorita</Td> <xr/>";

		assertTrue(TagCaseRepairer.find(VOCABULARY, source, translated).isEmpty());
		assertEquals(translated, TagCaseRepairer.repair(VOCABULARY, source, translated));
	}

	@Test
	@DisplayName("does not touch a case-drifted look-alike inside a fenced code block")
	public void shouldNotRepairInsideCode() {
		final String source = "<Td>example</Td>";
		final String translated = "<Td>příklad</Td>\n\n```html\n<Td>example</td>\n```";

		final String repaired = TagCaseRepairer.repair(VOCABULARY, source, translated);

		assertEquals(translated, repaired);
	}

	@Test
	@DisplayName("restores a self-closing tag's case to match its source occurrence")
	public void shouldRepairSelfClosingTagCase() {
		final String source = "line one<Br/>line two";
		final String translated = "řádek jedna<br/>řádek dva";

		assertEquals(
			"řádek jedna<Br/>řádek dva", TagCaseRepairer.repair(VOCABULARY, source, translated)
		);
	}

	@Test
	@DisplayName("applies multiple fixes without corrupting earlier offsets")
	public void shouldApplyMultipleFixesInOneFragment() {
		final String source = "<Td>one</Td> and <Term>two</Term>";
		final String translated = "<td>jedna</TD> a <term>dva</TERM>";

		final String repaired = TagCaseRepairer.repair(VOCABULARY, source, translated);

		assertEquals("<Td>jedna</Td> a <Term>dva</Term>", repaired);
	}

	@Test
	@DisplayName("reports nothing and returns the input unchanged when casing already matches")
	public void shouldReportNothingWhenCasingMatches() {
		final String source = "<Td>certificate authority</Td>";
		final String translated = "<Td>certifikační autorita</Td>";

		assertTrue(TagCaseRepairer.find(VOCABULARY, source, translated).isEmpty());
		assertEquals(translated, TagCaseRepairer.repair(VOCABULARY, source, translated));
	}

}
