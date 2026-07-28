package io.evitadb.comenius.structure;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The acceptance gate for the scope tree.
 *
 * Reconstructing a tree with no replacements must reproduce the input **byte for byte**. Nothing
 * downstream - unit packing, translation, incremental reuse - is worth building on a tree that
 * cannot survive this, because every one of those steps assumes that the parts it does not touch
 * come back unchanged.
 *
 * Byte equality alone is not sufficient, and the harness therefore always asserts the tiling
 * invariant as well: a reconstructor that copies the gaps between sibling spans would round-trip
 * perfectly while leaving prose in those gaps owned by no node, and therefore invisible to the
 * packer and to the translation memory.
 *
 * The whole gate costs nothing to run - no LLM is involved at any point.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Scope tree identity round trip")
public class ScopeTreeRoundTripTest {

	/**
	 * System property pointing at a directory of real Markdown, so a project's own corpus can be
	 * run through the gate without vendoring it into the plugin.
	 */
	private static final String CORPUS_PROPERTY = "comenius.corpus.dir";

	@ParameterizedTest(name = "{0}")
	@MethodSource("fixtures")
	@DisplayName("reconstructs every fixture byte for byte")
	public void shouldReconstructFixtureByteForByteWhenNoReplacementsGiven(
		String name,
		String body
	) {
		final TagVocabulary vocabulary = deriveFrom(Map.of(name, body), true);
		assertByteExactRoundTrip(name, body, vocabulary);
	}

	@Test
	@DisplayName("reconstructs degenerate inputs byte for byte")
	public void shouldReconstructDegenerateInputsWhenSourceIsEmptyOrWhitespace() {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("Note"), Set.of(), Set.of(), false);
		assertByteExactRoundTrip("empty", "", vocabulary);
		assertByteExactRoundTrip("single newline", "\n", vocabulary);
		assertByteExactRoundTrip("whitespace only", "  \n\n\t\n", vocabulary);
		assertByteExactRoundTrip("no trailing newline", "one line, no newline", vocabulary);
		assertByteExactRoundTrip("no trailing newline after tag", "<Note>\n\nbody\n\n</Note>", vocabulary);
		assertByteExactRoundTrip("heading only", "# Title", vocabulary);
		assertByteExactRoundTrip("unterminated fence", "prose\n\n```java\nint x = 1;\n", vocabulary);
		assertByteExactRoundTrip("crlf line endings", "para one\r\n\r\n# Head\r\n\r\npara two\r\n", vocabulary);
	}

	@Test
	@DisplayName("substitutes only the nominated node and leaves every other byte alone")
	public void shouldSubstituteOnlyNominatedNodeWhenReplacementGiven() {
		final String source = "before\n\n<Note type=\"info\">\n\n## Heading\n\nbody\n\n</Note>\n\nafter\n";
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("Note"), Set.of(), Set.of(), false);
		final ScopeTree tree = new ScopeTreeBuilder(vocabulary).build(source, "inline");
		final List<ScopeNode> headings = tree.collect(node -> node.kind() == ScopeNode.Kind.HEADING);
		assertEquals(1, headings.size(), "fixture should hold exactly one heading");

		final Map<ScopeNode, String> replacements = ScopeTreeReconstructor.newReplacementMap();
		replacements.put(headings.get(0), "Nadpis");
		final String result = ScopeTreeReconstructor.reconstruct(tree, replacements);

		assertEquals(
			"before\n\n<Note type=\"info\">\n\n## Nadpis\n\nbody\n\n</Note>\n\nafter\n",
			result,
			"only the heading text should change - markers, tags and blank lines are structural"
		);
	}

	@Test
	@EnabledIfSystemProperty(named = CORPUS_PROPERTY, matches = ".+")
	@DisplayName("reconstructs an external corpus byte for byte")
	public void shouldReconstructExternalCorpusByteForByteWhenCorpusDirectoryGiven() throws IOException {
		final Path root = Path.of(System.getProperty(CORPUS_PROPERTY));
		final Map<String, String> corpus = readCorpus(root);
		if (corpus.isEmpty()) {
			fail("no Markdown files found under " + root.toAbsolutePath());
		}
		final TagVocabulary vocabulary = deriveFrom(corpus, false);
		final List<String> failures = new ArrayList<>();
		for (final Map.Entry<String, String> document : corpus.entrySet()) {
			try {
				assertByteExactRoundTrip(document.getKey(), document.getValue(), vocabulary);
			} catch (AssertionError | RuntimeException exception) {
				failures.add(exception.getMessage());
			}
		}
		if (!failures.isEmpty()) {
			fail(
				failures.size() + " of " + corpus.size() + " documents failed the gate:\n\n"
					+ String.join("\n\n", failures.subList(0, Math.min(5, failures.size())))
					+ (failures.size() > 5 ? "\n\n...and " + (failures.size() - 5) + " more" : "")
			);
		}
		System.out.println(
			"[comenius] scope tree gate: " + corpus.size() + " documents round-tripped byte-exactly, "
				+ "vocabulary = " + vocabulary.getStructuralTags()
		);
	}

	// ---------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Builds a tree, checks the tiling invariant and asserts a byte-exact identity round trip.
	 *
	 * @param location human-readable identity of the document, used in failure messages
	 * @param source   the document body
	 * @param vocabulary the vocabulary to parse with
	 */
	public static void assertByteExactRoundTrip(
		@Nonnull String location,
		@Nonnull String source,
		@Nonnull TagVocabulary vocabulary
	) {
		final ScopeTree tree = new ScopeTreeBuilder(vocabulary).build(source, location);
		final String reconstructed = ScopeTreeReconstructor.reconstruct(tree);
		if (!source.equals(reconstructed)) {
			fail(describeDivergence(location, source, reconstructed));
		}
	}

	/**
	 * Renders a failure message that points at the first differing character with surrounding
	 * context. With a corpus of seventy documents a bare equality failure is unusable.
	 *
	 * @param location      human-readable identity of the document
	 * @param expected      the original source
	 * @param actual        the reconstructed source
	 * @return a message naming the file, the offset, the line and the context on both sides
	 */
	@Nonnull
	private static String describeDivergence(
		@Nonnull String location,
		@Nonnull String expected,
		@Nonnull String actual
	) {
		int divergence = 0;
		final int shared = Math.min(expected.length(), actual.length());
		while (divergence < shared && expected.charAt(divergence) == actual.charAt(divergence)) {
			divergence++;
		}
		int line = 1;
		for (int i = 0; i < divergence; i++) {
			if (expected.charAt(i) == '\n') {
				line++;
			}
		}
		return location + ": round trip diverged at offset " + divergence + " (line " + line + ")"
			+ "\n  expected length " + expected.length() + ", actual length " + actual.length()
			+ "\n  expected: " + context(expected, divergence)
			+ "\n  actual  : " + context(actual, divergence);
	}

	/**
	 * Extracts a readable window of text around an offset.
	 *
	 * @param text   the string to sample
	 * @param offset the centre of the window
	 * @return an escaped, bounded excerpt
	 */
	@Nonnull
	private static String context(@Nonnull String text, int offset) {
		final int from = Math.max(0, offset - 40);
		final int to = Math.min(text.length(), offset + 40);
		return "..." + text.substring(from, to).replace("\n", "\\n").replace("\t", "\\t") + "...";
	}

	/**
	 * Runs corpus-wide vocabulary derivation over the given documents.
	 *
	 * @param documents document body keyed by a human-readable identity
	 * @param lenient   whether unbalanced markup degrades to text instead of failing
	 * @return the derived vocabulary
	 */
	@Nonnull
	private static TagVocabulary deriveFrom(@Nonnull Map<String, String> documents, boolean lenient) {
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		for (final Map.Entry<String, String> document : documents.entrySet()) {
			deriver.add(document.getKey(), document.getValue());
		}
		return deriver.derive(Set.of("Table", "Thead", "Tbody", "Tr"), Set.of(), lenient);
	}

	/**
	 * Reads every Markdown body under the given root, skipping directories that hold generated
	 * examples rather than prose.
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
						final String content = new String(
							Files.readAllBytes(path), StandardCharsets.UTF_8
						);
						corpus.put(
							root.relativize(path).toString(),
							new MarkdownDocument(content).getBodyContent()
						);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				});
		}
		return corpus;
	}

	/**
	 * Supplies every fixture under `src/test/resources/markup` to the parameterized gate.
	 *
	 * @return a stream of `(name, body)` argument pairs
	 * @throws IOException        when the fixture directory cannot be read
	 * @throws URISyntaxException when the fixture directory cannot be located on the classpath
	 */
	@Nonnull
	private static Stream<org.junit.jupiter.params.provider.Arguments> fixtures()
		throws IOException, URISyntaxException {
		final Path root = Path.of(
			java.util.Objects.requireNonNull(
				ScopeTreeRoundTripTest.class.getResource("/markup"), "fixture directory missing"
			).toURI()
		);
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".md"))
				.sorted()
				.map(path -> {
					try {
						final String content = new String(
							Files.readAllBytes(path), StandardCharsets.UTF_8
						);
						return org.junit.jupiter.params.provider.Arguments.of(
							path.getFileName().toString(),
							new MarkdownDocument(content).getBodyContent()
						);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				})
				.toList()
				.stream();
		}
	}

}
