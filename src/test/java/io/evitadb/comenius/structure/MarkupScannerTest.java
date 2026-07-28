package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the lexer that the round-trip gate cannot pin down: byte equality holds just as
 * well when a construct is classified wrongly, as long as it is classified wrongly *consistently*.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Markup scanner")
public class MarkupScannerTest {

	private static final TagVocabulary VOCABULARY = TagVocabulary.of(
		Set.of("Note", "LS", "Term", "SourceClass", "Table", "Tr", "Td", "MDInclude"),
		Set.of(), Set.of(), false
	);

	@Test
	@DisplayName("ignores hash comments inside a fenced code block")
	public void shouldNotReportHeadingsWhenHashesAreInsideAFence() {
		final String source = "prose\n\n```shell\n# not a heading\n#### also not\n```\n\n## real heading\n";
		final List<MarkupToken> tokens = scan(source);

		final List<MarkupToken> headings = headings(tokens);
		assertEquals(1, headings.size(), "only the heading outside the fence counts");
		assertEquals("real heading", source.substring(
			headings.get(0).contentStart(), headings.get(0).contentEnd()
		));
	}

	@Test
	@DisplayName("closes a fence only on a run at least as long as the opener")
	public void shouldKeepFenceOpenWhenClosingRunIsTooShort() {
		final String source = "````markdown\n```\ninner\n```\n````\n\n# after\n";
		final List<MarkupToken> tokens = scan(source);

		final List<MarkupToken> code = tokens.stream()
			.filter(token -> token.type() == MarkupToken.Type.CODE).toList();
		assertEquals(1, code.size(), "the three-backtick runs must not close a four-backtick fence");
		assertEquals("````markdown\n```\ninner\n```\n````\n", source.substring(
			code.get(0).start(), code.get(0).end()
		));
		assertEquals(1, headings(tokens).size(), "the heading after the fence is still visible");
	}

	@Test
	@DisplayName("treats an unterminated fence as owning the rest of the document")
	public void shouldExtendUnterminatedFenceToEndOfInputWhenNoCloserExists() {
		final String source = "prose\n\n```java\nint x = 1;\n# looks like a heading\n";
		final List<MarkupToken> tokens = scan(source);

		assertTrue(headings(tokens).isEmpty(), "nothing after an unterminated fence is markup");
		assertEquals(source.length(), tokens.get(tokens.size() - 1).end());
	}

	@Test
	@DisplayName("masks inline code so a tag inside it is literal text")
	public void shouldNotReportTagsWhenTheyAreInsideACodeSpan() {
		final String source = "Prose mentioning `<Note>` and `List<SealedEntity>` only.\n";
		assertTrue(tags(scan(source)).isEmpty(), "backticked tags are prose");
	}

	@Test
	@DisplayName("stops a stray code span at the next block instead of swallowing markup")
	public void shouldBoundCodeSpanToItsBlockWhenOpeningBacktickIsUnmatched() {
		// the apostrophe is a real typo pattern; the backtick must not reach the one below
		final String source =
			"    unless `validity' is specified. In other\n"
				+ "    words it makes no sense.\n"
				+ "    </Td>\n"
				+ "    <Td>\n"
				+ "    <LS to=\"e\">`currency`</LS>\n";
		final List<MarkupToken> tags = tags(scan(source));

		assertEquals(4, tags.size(), "all four tags must stay visible past the stray backtick");
		assertEquals("Td", tags.get(0).name());
		assertEquals(MarkupToken.Type.TAG_CLOSE, tags.get(0).type());
		assertEquals("LS", tags.get(3).name());
	}

	@Test
	@DisplayName("keeps a tag intact when a quoted attribute value contains a closing bracket")
	public void shouldParseWholeTagWhenAttributeValueHoldsGreaterThan() {
		final String source = "<Note type=\"info\" title=\"a > b\">\n\nbody\n\n</Note>\n";
		final List<MarkupToken> tags = tags(scan(source));

		assertEquals(2, tags.size());
		assertEquals("<Note type=\"info\" title=\"a > b\">", source.substring(
			tags.get(0).start(), tags.get(0).end()
		));
	}

	@Test
	@DisplayName("recognises a self-closing tag")
	public void shouldClassifyTagAsSelfClosingWhenItEndsWithSlash() {
		final List<MarkupToken> tags = tags(scan("text <MDInclude src=\"a.md\"/> more\n"));

		assertEquals(1, tags.size());
		assertEquals(MarkupToken.Type.TAG_SELF_CLOSING, tags.get(0).type());
	}

	@Test
	@DisplayName("marks only tags that stand alone on their line as block level")
	public void shouldFlagBlockLevelOnlyWhenLineHoldsNothingButTags() {
		final String source =
			"<Table>\n"
				+ "    <Tr><Td>a value</Td></Tr>\n"
				+ "</Table>\n"
				+ "\n"
				+ "Prose with a <Term>term</Term> in it.\n";
		final List<MarkupToken> tags = tags(scan(source));

		assertTrue(tags.get(0).blockLevel(), "<Table> stands alone");
		assertFalse(tags.get(1).blockLevel(), "<Tr> shares its line with prose-bearing tags");
		assertFalse(tags.get(2).blockLevel(), "<Td> wraps a value");
		assertTrue(tags.get(5).blockLevel(), "</Table> stands alone");

		final MarkupToken inlineTerm = tags.stream()
			.filter(token -> "Term".equals(token.name())).findFirst().orElseThrow();
		assertFalse(inlineTerm.blockLevel(), "an inline term is never a cut point");
	}

	@Test
	@DisplayName("ignores names outside the vocabulary")
	public void shouldSkipTagWhenNameIsNotInVocabulary() {
		assertTrue(tags(scan("A <Unknown>thing</Unknown> here.\n")).isEmpty());
	}

	@Test
	@DisplayName("matches tag names case-sensitively")
	public void shouldDistinguishTagsByCaseWhenNamesDifferOnlyInCapitalisation() {
		final List<MarkupToken> tags = tags(scan("<Td>x</Td> and <td>y</td>\n"));

		assertEquals(2, tags.size(), "only the capitalised component is in the vocabulary");
		assertEquals("Td", tags.get(0).name());
	}

	@Test
	@DisplayName("does not treat a hash without a space as a heading")
	public void shouldNotReportHeadingWhenHashIsNotFollowedByWhitespace() {
		assertTrue(headings(scan("#hashtag is prose\n")).isEmpty());
	}

	@Test
	@DisplayName("strips a closing hash sequence from the heading text")
	public void shouldExcludeTrailingHashesWhenHeadingIsClosed() {
		final String source = "## Title ##\n";
		final List<MarkupToken> headings = headings(scan(source));

		assertEquals(1, headings.size());
		assertEquals("Title", source.substring(
			headings.get(0).contentStart(), headings.get(0).contentEnd()
		));
	}

	@Test
	@DisplayName("does not treat a deeply indented hash line as a heading")
	public void shouldIgnoreHeadingWhenIndentedByFourOrMoreSpaces() {
		assertTrue(headings(scan("    ##### Tip\n")).isEmpty());
	}

	@Test
	@DisplayName("returns tokens in ascending offset order without overlaps")
	public void shouldReturnOrderedNonOverlappingTokensWhenSourceIsMixed() {
		final String source =
			"# Head\n\n<Note>\n\ntext `code` more\n\n```java\nx\n```\n\n</Note>\n<!-- c -->\n";
		final List<MarkupToken> tokens = scan(source);

		for (int i = 0; i < tokens.size() - 1; i++) {
			assertTrue(
				tokens.get(i).end() <= tokens.get(i + 1).start(),
				"token " + i + " overlaps its successor"
			);
		}
	}

	// ---------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Scans the given source with the shared test vocabulary.
	 *
	 * @param source the document text
	 * @return the tokens found
	 */
	@Nonnull
	private static List<MarkupToken> scan(@Nonnull String source) {
		return new MarkupScanner(VOCABULARY).scan(source);
	}

	/**
	 * Filters heading tokens out of a token list.
	 *
	 * @param tokens the tokens to filter
	 * @return heading tokens in document order
	 */
	@Nonnull
	private static List<MarkupToken> headings(@Nonnull List<MarkupToken> tokens) {
		return tokens.stream().filter(token -> token.type() == MarkupToken.Type.HEADING).toList();
	}

	/**
	 * Filters tag tokens out of a token list.
	 *
	 * @param tokens the tokens to filter
	 * @return tag tokens in document order
	 */
	@Nonnull
	private static List<MarkupToken> tags(@Nonnull List<MarkupToken> tokens) {
		return tokens.stream().filter(MarkupToken::isTag).toList();
	}

}
