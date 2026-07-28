package io.evitadb.comenius.structure;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Acceptance for unit packing, stated as three independent assertions.
 *
 * They are deliberately not folded into one. The interesting one - that no unit boundary falls
 * inside a structural tag - is **not** implied by the others: the splitter this design replaces
 * violates it constantly and still concatenates back perfectly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Unit packing")
public class UnitPackerTest {

	private static final String CORPUS_PROPERTY = "comenius.corpus.dir";

	private static final TagVocabulary VOCABULARY = TagVocabulary.of(
		Set.of("Note", "NoteTitle", "LS", "Term", "SourceClass", "Table", "Thead", "Tbody", "Tr", "Td"),
		Set.of("Table", "Thead", "Tbody", "Tr"), Set.of(), false
	);

	@Test
	@DisplayName("never cuts inside a structural tag")
	public void shouldKeepEveryTagWhollyInsideOneUnitWhenPacking() {
		final String source = tableDocument();
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "table");
		// a target small enough to force the packer to cut wherever it legally can
		final List<TranslationUnit> units = pack(tree, new UnitPacker.Settings(64, 128, 0));

		assertTrue(units.size() > 1, "the fixture must actually be split for this to prove anything");
		assertNoBoundaryInsideATag(tree, units);
	}

	@Test
	@DisplayName("the tag-boundary check actually rejects a bad packing")
	public void shouldRejectHandBuiltPackingWhenAUnitStraddlesATag() {
		final String source = tableDocument();
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "table");
		final ScopeNode row = tree.collect(node -> "Tr".equals(node.name())).get(0);
		final ScopeNode closingProse = tree.getRoot().children().stream()
			.filter(node -> node.kind() == ScopeNode.Kind.TEXT)
			.filter(node -> node.content(source).startsWith("Closing"))
			.findFirst().orElseThrow();

		// this invariant has never been observed failing, so prove it can fail: a unit that
		// starts legitimately inside the table and then runs past the table's closing delimiter,
		// stranding </Table> in a different request from <Table>
		final TranslationUnit straddling = TranslationUnit.of(
			List.of(row, closingProse), List.of(tree.getRoot())
		);
		assertThrows(
			AssertionError.class,
			() -> assertNoBoundaryInsideATag(tree, List.of(straddling)),
			"a unit running from inside the table to past </Table> must be rejected"
		);

		// the other shape - a boundary landing strictly inside `<Table ...>` itself - is not
		// merely untested but unconstructible: TranslationUnit derives its offsets from its
		// nodes, and no node begins inside another node's delimiters
		assertThrows(
			IllegalArgumentException.class,
			() -> new TranslationUnit(List.of(row), List.of(tree.getRoot()), row.start() + 3, row.end())
		);
	}

	@Test
	@DisplayName("never cuts a paragraph around an inline tag")
	public void shouldKeepProseTogetherWhenInlineTagsFragmentIt() {
		final String source =
			"The <Term>entity</Term> is stored in a <Term>collection</Term> managed by\n"
				+ "<SourceClass>io/evitadb/core/Evita.java</SourceClass> at runtime.\n"
				+ "\n"
				+ "A second paragraph that may legitimately be separated.\n";
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "prose");
		final List<TranslationUnit> units = pack(tree, new UnitPacker.Settings(16, 32, 0));

		final TranslationUnit first = units.get(0);
		final String text = first.text(source);
		assertTrue(text.startsWith("The <Term>entity</Term>"), "the sentence must start the unit");
		assertTrue(
			text.contains("at runtime."),
			"an inline tag is not a cut point, so the whole sentence travels together: " + text
		);
	}

	@Test
	@DisplayName("descends into an atomic container only above the hard ceiling")
	public void shouldSplitAtomicContainerOnlyWhenItExceedsMaxUnitSize() {
		final String source = tableDocument();
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "table");
		final ScopeNode table = tree.collect(node -> "Table".equals(node.name())).get(0);

		final List<TranslationUnit> whole = pack(
			tree, new UnitPacker.Settings(16, table.length() + 1, 0)
		);
		assertTrue(
			whole.stream().anyMatch(unit -> unit.start() == table.start() && unit.end() == table.end()),
			"below the ceiling the atomic table stays in one piece"
		);

		final List<TranslationUnit> split = pack(tree, new UnitPacker.Settings(16, 32, 0));
		assertTrue(
			split.stream().noneMatch(unit -> unit.start() == table.start() && unit.end() == table.end()),
			"above the ceiling atomicity yields rather than producing an oversized unit"
		);
		assertNoBoundaryInsideATag(tree, split);
	}

	@Test
	@DisplayName("keeps accumulating below the minimum instead of emitting a scrap")
	public void shouldNotEmitTinyUnitWhenBufferIsBelowMinimum() {
		final String source =
			"<Note type=\"info\">\n\n<NoteTitle>Tip</NoteTitle>\n\nBody of the note.\n\n</Note>\n";
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "note");
		final List<TranslationUnit> units = pack(tree, new UnitPacker.Settings(8, 4096, 64));

		final TranslationUnit withTitle = units.stream()
			.filter(unit -> unit.text(source).contains("<NoteTitle>")).findFirst().orElseThrow();
		assertTrue(
			withTitle.text(source).contains("Body of the note."),
			"below the minimum a unit keeps accumulating past the target, so the title travels "
				+ "with the body it introduces rather than alone: [" + withTitle.text(source) + "]"
		);
	}

	@Test
	@DisplayName("carries the enclosing chain as context rather than as text")
	public void shouldExposeAncestorChainWhenUnitIsNested() {
		final String source = "<LS to=\"j\">\n\n<Note type=\"info\">\n\nbody\n\n</Note>\n\n</LS>\n";
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "nested");
		final List<TranslationUnit> units = pack(tree, new UnitPacker.Settings(8, 16, 0));

		final TranslationUnit deepest = units.stream()
			.filter(unit -> unit.text(source).contains("body")).findFirst().orElseThrow();
		assertEquals("<LS to=\"j\"> > <Note type=\"info\">", deepest.describeContext(source));
		assertEquals("/TAG:LS/TAG:Note", deepest.contextKey(), "the key must not carry attributes");
		assertFalse(deepest.text(source).contains("<LS"), "ancestors are context, never text");
	}

	@Test
	@DisplayName("rebuilds the document from translated units")
	public void shouldReconstructDocumentWhenEveryUnitIsReplaced() {
		final String source = tableDocument();
		final ScopeTree tree = new ScopeTreeBuilder(VOCABULARY).build(source, "table");
		final List<TranslationUnit> units = pack(tree, new UnitPacker.Settings(64, 128, 0));

		final Map<TranslationUnit, String> identity = new HashMap<>();
		for (final TranslationUnit unit : units) {
			identity.put(unit, unit.text(source));
		}
		assertEquals(source, ScopeTreeReconstructor.reconstructUnits(tree, identity));
	}

	@Test
	@EnabledIfSystemProperty(named = CORPUS_PROPERTY, matches = ".+")
	@DisplayName("packs an external corpus without cutting a tag or overshooting the ceiling")
	public void shouldPackExternalCorpusWithinBudgetWhenCorpusDirectoryGiven() throws IOException {
		final Path root = Path.of(System.getProperty(CORPUS_PROPERTY));
		final Map<String, String> corpus = readCorpus(root);
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		corpus.forEach(deriver::add);
		final TagVocabulary vocabulary = deriver.derive(
			Set.of("Table", "Thead", "Tbody", "Tr", "CodeTabs", "CodeTabsBlock", "SourceCodeTabs"),
			Set.of(), false
		);
		final UnitPacker.Settings settings = UnitPacker.Settings.defaults();
		final ScopeTreeBuilder builder = new ScopeTreeBuilder(vocabulary);

		int unitCount = 0;
		int largest = 0;
		final List<String> oversized = new ArrayList<>();
		for (final Map.Entry<String, String> document : corpus.entrySet()) {
			final ScopeTree tree = builder.build(document.getValue(), document.getKey());
			final List<TranslationUnit> units = new UnitPacker(vocabulary, settings).pack(tree);
			assertNoBoundaryInsideATag(tree, units);

			final Map<TranslationUnit, String> identity = new HashMap<>();
			for (final TranslationUnit unit : units) {
				identity.put(unit, unit.text(tree.getSource()));
				unitCount++;
				largest = Math.max(largest, unit.length());
				if (unit.length() > settings.maxUnitSize()) {
					// reported explicitly rather than silently sent oversized
					oversized.add(document.getKey() + " line " + tree.lineOf(unit.start())
						+ ": " + unit.length() + " bytes, " + unit.nodes().size() + " node(s)");
				}
			}
			assertEquals(
				document.getValue(),
				ScopeTreeReconstructor.reconstructUnits(tree, identity),
				document.getKey() + " does not rebuild from its own units"
			);
		}
		System.out.println(
			"[comenius] packing: " + corpus.size() + " documents -> " + unitCount + " units, "
				+ "largest " + largest + " bytes, target " + settings.targetUnitSize()
				+ ", ceiling " + settings.maxUnitSize()
		);
		if (!oversized.isEmpty()) {
			fail("units above the hard ceiling:\n  " + String.join("\n  ", oversized));
		}
	}

	// ---------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Asserts that every unit is either disjoint from each tag, wholly contains it, or lies
	 * wholly within its **content** - never partially overlapping it, and never with a boundary
	 * inside the opening or closing markup.
	 *
	 * Stated as nesting rather than as two boundary checks because the interesting violation is
	 * the *straddle*: a unit that legitimately starts inside a tag's content and then runs past
	 * its closing delimiter has both endpoints in defensible-looking places while stranding
	 * `&lt;/Note&gt;` in a different request from its opener.
	 *
	 * @param tree  the tree the units were packed from
	 * @param units the packing to check
	 */
	private static void assertNoBoundaryInsideATag(
		@Nonnull ScopeTree tree,
		@Nonnull List<TranslationUnit> units
	) {
		final List<ScopeNode> tags = tree.collect(node -> node.kind() == ScopeNode.Kind.TAG);
		for (final TranslationUnit unit : units) {
			for (final ScopeNode tag : tags) {
				if (unit.end() <= tag.start() || unit.start() >= tag.end()) {
					continue;
				}
				if (tag.start() >= unit.start() && tag.end() <= unit.end()) {
					continue;
				}
				assertTrue(
					unit.start() >= tag.contentStart() && unit.end() <= tag.contentEnd(),
					"unit [" + unit.start() + "," + unit.end() + ") partially overlaps <"
						+ tag.name() + "> [" + tag.start() + "," + tag.end() + ") whose content is ["
						+ tag.contentStart() + "," + tag.contentEnd() + ")"
				);
			}
		}
	}

	/**
	 * Packs a tree with the given settings and the shared test vocabulary.
	 *
	 * @param tree     the tree to pack
	 * @param settings the size policy
	 * @return the units
	 */
	@Nonnull
	private static List<TranslationUnit> pack(
		@Nonnull ScopeTree tree,
		@Nonnull UnitPacker.Settings settings
	) {
		return new UnitPacker(VOCABULARY, settings).pack(tree);
	}

	/**
	 * Returns a document holding a nested atomic container plus prose on either side.
	 *
	 * @return the fixture text
	 */
	@Nonnull
	private static String tableDocument() {
		return "Intro prose before the table.\n"
			+ "\n"
			+ "<Table>\n"
			+ "    <Thead>\n"
			+ "        <Tr>\n"
			+ "            <Td>Variable</Td>\n"
			+ "            <Td>Description</Td>\n"
			+ "        </Tr>\n"
			+ "    </Thead>\n"
			+ "    <Tbody>\n"
			+ "        <Tr>\n"
			+ "            <Td>EVITA_STORAGE_DIR</Td>\n"
			+ "            <Td>Path to the storage directory used by the server at runtime.</Td>\n"
			+ "        </Tr>\n"
			+ "        <Tr>\n"
			+ "            <Td>EVITA_JAVA_OPTS</Td>\n"
			+ "            <Td>Java command line arguments passed to the embedded runtime.</Td>\n"
			+ "        </Tr>\n"
			+ "    </Tbody>\n"
			+ "</Table>\n"
			+ "\n"
			+ "Closing prose after the table.\n";
	}

	/**
	 * Reads every Markdown body under the given root, skipping generated example directories.
	 *
	 * @param root the corpus root
	 * @return document body keyed by path relative to the root
	 * @throws IOException when the tree cannot be walked
	 */
	@Nonnull
	private static Map<String, String> readCorpus(@Nonnull Path root) throws IOException {
		final Map<String, String> corpus = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> {
					final String text = path.toString();
					return !text.contains("/examples/") && !text.contains("/example/")
						&& !text.contains("/assets/");
				})
				.sorted()
				.forEach(path -> {
					try {
						corpus.put(
							root.relativize(path).toString(),
							new MarkdownDocument(
								new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
							).getBodyContent()
						);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				});
		}
		return corpus;
	}

}
