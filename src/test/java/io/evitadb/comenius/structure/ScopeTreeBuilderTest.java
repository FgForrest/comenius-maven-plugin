package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the scoping rule itself - the part of the design that decides where a translation
 * unit is allowed to end.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Scope tree builder")
public class ScopeTreeBuilderTest {

	private static final TagVocabulary VOCABULARY = TagVocabulary.of(
		Set.of("Note", "LS", "Term", "Table", "Tr", "Td"), Set.of(), Set.of(), false
	);

	@Test
	@DisplayName("stops a heading section at the end of its enclosing tag")
	public void shouldTruncateHeadingSectionWhenEnclosingTagClosesFirst() {
		final String source =
			"intro\n\n"
				+ "<LS to=\"j\">\n\n"
				+ "## Java\n\n"
				+ "body of the java section\n\n"
				+ "</LS>\n\n"
				+ "prose that belongs to nobody\n";
		final ScopeTree tree = build(source);

		final ScopeNode languageSwitch = single(tree, node ->
			node.kind() == ScopeNode.Kind.TAG && "LS".equals(node.name()));
		final ScopeNode section = single(tree, node ->
			node.kind() == ScopeNode.Kind.HEADING_SECTION);

		assertTrue(
			section.end() <= languageSwitch.contentEnd(),
			"the section must not outlive the tag that encloses it"
		);
		assertTrue(
			source.substring(section.start(), section.end()).contains("body of the java section"),
			"the section still owns its own body"
		);
		assertTrue(
			!source.substring(section.start(), section.end()).contains("belongs to nobody"),
			"prose after the closing tag is outside the section"
		);
	}

	@Test
	@DisplayName("nests a deeper heading inside the section above it")
	public void shouldNestHeadingSectionWhenLevelIsDeeper() {
		final String source = "## Outer\n\na\n\n### Inner\n\nb\n\n## Sibling\n\nc\n";
		final ScopeTree tree = build(source);

		final List<ScopeNode> topLevel = tree.getRoot().children().stream()
			.filter(node -> node.kind() == ScopeNode.Kind.HEADING_SECTION).toList();
		assertEquals(2, topLevel.size(), "two level-two sections");
		assertEquals(2, topLevel.get(0).headingLevel());

		final List<ScopeNode> nested = topLevel.get(0).children().stream()
			.filter(node -> node.kind() == ScopeNode.Kind.HEADING_SECTION).toList();
		assertEquals(1, nested.size(), "the level-three section belongs to the first level-two one");
		assertEquals(3, nested.get(0).headingLevel());
	}

	@Test
	@DisplayName("keeps a heading inside a tag out of the document outline")
	public void shouldScopeHeadingToTagWhenHeadingIsNestedInsideIt() {
		final String source =
			"# Document\n\nintro\n\n<Note type=\"info\">\n\n## Inside the note\n\nnote body\n\n</Note>\n\nafter\n";
		final ScopeTree tree = build(source);

		final ScopeNode note = single(tree, node ->
			node.kind() == ScopeNode.Kind.TAG && "Note".equals(node.name()));
		final ScopeNode inner = single(tree, node ->
			node.kind() == ScopeNode.Kind.HEADING_SECTION && node.headingLevel() == 2);

		assertTrue(
			inner.start() >= note.contentStart() && inner.end() <= note.contentEnd(),
			"a heading inside a note may not restructure the document around it"
		);
	}

	@Test
	@DisplayName("splits prose into one node per block")
	public void shouldEmitOneTextNodePerBlockWhenProseIsSeparatedByBlankLines() {
		final String source = "first paragraph\n\nsecond paragraph\n\nthird paragraph\n";
		final ScopeTree tree = build(source);

		final List<ScopeNode> texts = tree.collect(node -> node.kind() == ScopeNode.Kind.TEXT);
		assertEquals(3, texts.size(), "block splitting is the last tier of cut point precedence");
		assertEquals("first paragraph", texts.get(0).content(source));
		assertEquals("third paragraph", texts.get(2).content(source));
	}

	@Test
	@DisplayName("excludes surrounding whitespace from a prose node's content")
	public void shouldTrimContentRangeWhenBlockCarriesTrailingBlankLines() {
		final String source = "para\n\n\n\nnext\n";
		final ScopeTree tree = build(source);

		final ScopeNode first = tree.collect(node -> node.kind() == ScopeNode.Kind.TEXT).get(0);
		assertEquals("para", first.content(source));
		assertEquals("para\n\n\n\n", first.fullText(source), "the blank lines are still owned");
	}

	@Test
	@DisplayName("keeps the blank lines that pad a tag's content inside the tag")
	public void shouldOwnPaddingBlankLinesWhenTagContentIsBlankLineDelimited() {
		// virtually every <Note>/<LS> in a real corpus has this shape, so the node structure is
		// asserted explicitly rather than left to byte equality, which would stay green even if
		// a future change to block splitting reshaped several hundred tags' worth of nodes
		final String source = "<Note type=\"info\">\n\nbody\n\n</Note>\n";
		final ScopeTree tree = build(source);

		final ScopeNode note = single(tree, node -> node.kind() == ScopeNode.Kind.TAG);
		assertEquals("\n\nbody\n\n", source.substring(note.contentStart(), note.contentEnd()));
		assertEquals(1, note.children().size(), "the padded body is one block, not three");

		final ScopeNode body = note.children().get(0);
		assertEquals(ScopeNode.Kind.TEXT, body.kind());
		assertEquals("body", body.content(source), "padding is owned but never translated");
		assertEquals("\n\nbody\n\n", body.fullText(source));
	}

	@Test
	@DisplayName("treats a tag as a cut point only when both delimiters stand alone")
	public void shouldNotMarkTagBlockLevelWhenOnlyTheOpeningDelimiterStandsAlone() {
		final String source = "<Note type=\"info\">\n\nbody text</Note> and more prose.\n";
		final ScopeNode note = single(build(source), node -> node.kind() == ScopeNode.Kind.TAG);

		assertFalse(
			note.blockLevel(),
			"cutting before this tag would strand its closer in the middle of a sentence"
		);
	}

	@Test
	@DisplayName("fails with the offending line when a tag never closes")
	public void shouldThrowWhenTagIsUnbalancedAndVocabularyIsStrict() {
		final String source = "intro\n\n<Note type=\"info\">\n\nbody that never closes\n";

		final UnbalancedMarkupException exception = assertThrows(
			UnbalancedMarkupException.class, () -> build(source)
		);
		assertEquals(1, exception.getDefects().size());
		assertEquals("Note", exception.getDefects().get(0).name());
		assertEquals(3, exception.getDefects().get(0).line());
		assertTrue(exception.getMessage().contains("opened but never closed"));
	}

	@Test
	@DisplayName("fails when a closing tag has nothing to close")
	public void shouldThrowWhenClosingTagHasNoOpenerAndVocabularyIsStrict() {
		final UnbalancedMarkupException exception = assertThrows(
			UnbalancedMarkupException.class, () -> build("body\n\n</Note>\n")
		);
		assertTrue(exception.getMessage().contains("closed but never opened"));
	}

	@Test
	@DisplayName("degrades an unbalanced tag to prose when lenient")
	public void shouldTreatUnbalancedTagAsTextWhenVocabularyIsLenient() {
		final String source = "intro\n\n<Note type=\"info\">\n\nbody that never closes\n";
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY.withLenient(true))
			.build(source, "lenient");

		assertTrue(
			tree.collect(node -> node.kind() == ScopeNode.Kind.TAG).isEmpty(),
			"the unbalanced tag must not become a container"
		);
		assertEquals(source, ScopeTreeReconstructor.reconstruct(tree));
	}

	@Test
	@DisplayName("reports mismatched nesting rather than guessing")
	public void shouldThrowWhenTagsCloseOutOfOrder() {
		final UnbalancedMarkupException exception = assertThrows(
			UnbalancedMarkupException.class,
			() -> build("<Note>\n\n<LS to=\"j\">\n\ntext\n\n</Note>\n")
		);
		assertEquals("LS", exception.getDefects().get(0).name());
	}

	@Test
	@DisplayName("refuses a replacement that belongs to a different tree")
	public void shouldThrowWhenReplacementKeyIsForeignToTheTree() {
		final ScopeTree tree = build("hello\n");
		final ScopeTree other = build("world\n");
		final var replacements = ScopeTreeReconstructor.newReplacementMap();
		replacements.put(other.collect(node -> node.kind() == ScopeNode.Kind.TEXT).get(0), "x");

		assertThrows(
			IllegalArgumentException.class,
			() -> ScopeTreeReconstructor.reconstruct(tree, replacements),
			"a silently ignored replacement would look like a successful translation"
		);
	}

	@Test
	@DisplayName("binds the tree to the exact source it indexes")
	public void shouldExposeTheSourceItWasBuiltFromWhenTreeIsCreated() {
		final String source = "text\n";
		assertSame(source, build(source).getSource());
	}

	// ---------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Builds a validated tree with the shared strict test vocabulary.
	 *
	 * @param source the document text
	 * @return the tree
	 */
	@Nonnull
	private static ScopeTree build(@Nonnull String source) {
		return new ScopeTreeBuilder(VOCABULARY).build(source, "test");
	}

	/**
	 * Returns the single node matching the predicate, failing when there is not exactly one.
	 *
	 * @param tree      the tree to search
	 * @param predicate the filter to apply
	 * @return the matching node
	 */
	@Nonnull
	private static ScopeNode single(
		@Nonnull ScopeTree tree,
		@Nonnull java.util.function.Predicate<ScopeNode> predicate
	) {
		final List<ScopeNode> matches = tree.collect(predicate);
		assertEquals(1, matches.size(), "expected exactly one matching node, found " + matches.size());
		assertNotNull(matches.get(0));
		return matches.get(0);
	}

}
