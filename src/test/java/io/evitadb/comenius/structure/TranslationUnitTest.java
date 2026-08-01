package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Acceptance for {@link TranslationUnit#core(String)} and
 * {@link TranslationUnit#wrapTranslation(String, String)}.
 *
 * These exist because of a reproduced defect: a unit's own trailing whitespace - the blank line
 * that separates it from whatever the packer put next - is not reliably returned by the model.
 * gpt-4.1 strips it every time. Under the tiling invariant that blank line belongs to exactly one
 * side of the seam, so when the model drops it, reconstruction glues the next unit's heading
 * straight onto this unit's last sentence with no separator at all. The fix removes the model
 * from the whitespace-fidelity question entirely: only the trimmed core is ever sent, and the
 * original edges are always reattached in Java afterward.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Translation unit core/edge handling")
public class TranslationUnitTest {

	@Test
	@DisplayName("core() strips leading and trailing whitespace-only padding")
	public void shouldStripLeadingAndTrailingWhitespaceInCore() {
		final String source = "\n\nHello world.\n\n";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		assertEquals("Hello world.", unit.core(source));
	}

	@Test
	@DisplayName("core() preserves blank lines between paragraphs")
	public void shouldPreserveInteriorBlankLinesInCore() {
		final String source = "\n\nFirst paragraph.\n\nSecond paragraph.\n\n";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		assertEquals("First paragraph.\n\nSecond paragraph.", unit.core(source));
	}

	@Test
	@DisplayName("wrapTranslation() restores the original trailing blank line the model dropped")
	public void shouldRestoreOriginalTrailingEdgeWhenModelStripsIt() {
		// this is the exact shape that glued a heading onto the previous sentence in the probe:
		// the unit's own text ends in a blank line, and the model's answer does not
		final String source = "The gRPC protocol.\n\n";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		final String modelAnswer = "Protokol gRPC.";
		assertEquals("Protokol gRPC.\n\n", unit.wrapTranslation(source, modelAnswer));
	}

	@Test
	@DisplayName("wrapTranslation() restores a missing leading edge the same way")
	public void shouldRestoreOriginalLeadingEdgeWhenModelStripsIt() {
		final String source = "\n\n## Creating a certificate";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		final String modelAnswer = "## Vytvoření certifikátu";
		assertEquals("\n\n## Vytvoření certifikátu", unit.wrapTranslation(source, modelAnswer));
	}

	@Test
	@DisplayName("wrapTranslation() strips whitespace the model added that the source never had")
	public void shouldStripModelAddedWhitespaceEvenWhenSourceHadNone() {
		final String source = "## Heading text";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		final String modelAnswer = "## Nadpis textu\n";
		assertEquals("## Nadpis textu", unit.wrapTranslation(source, modelAnswer));
	}

	@Test
	@DisplayName("wrapTranslation() leaves the model's interior formatting untouched")
	public void shouldPreserveInteriorWhitespaceOfTheModelAnswerWhenWrapping() {
		final String source = "\nFirst.\n\nSecond.\n";
		final TranslationUnit unit = wholeSourceAsUnit(source);
		// the model padded its own answer with stray edge whitespace, which must not survive either
		final String modelAnswer = "  First cs.\n\nSecond cs.  ";
		assertEquals("\nFirst cs.\n\nSecond cs.\n", unit.wrapTranslation(source, modelAnswer));
	}

	@Test
	@DisplayName("contentHash() is stable for the same context and text")
	public void shouldProduceSameHashForIdenticalContextAndText() {
		final String source = "\n\nHello world.\n\n";
		final TranslationUnit unitA = wholeSourceAsUnit(source);
		final TranslationUnit unitB = wholeSourceAsUnit(source);
		assertEquals(unitA.contentHash(source), unitB.contentHash(source));
	}

	@Test
	@DisplayName("contentHash() differs when the core text differs")
	public void shouldProduceDifferentHashForDifferentText() {
		final String sourceA = "\n\nHello world.\n\n";
		final String sourceB = "\n\nGoodbye world.\n\n";
		final TranslationUnit unitA = wholeSourceAsUnit(sourceA);
		final TranslationUnit unitB = wholeSourceAsUnit(sourceB);
		assertNotEquals(
			unitA.contentHash(sourceA), unitB.contentHash(sourceB)
		);
	}

	@Test
	@DisplayName("contentHash() differs when the structural context differs but the text is identical")
	public void shouldProduceDifferentHashForSameTextInDifferentContext() {
		final String source = "Same text.";
		final ScopeNode textNode = ScopeNode.text(0, source.length(), 0, source.length());

		final TranslationUnit topLevel = TranslationUnit.of(List.of(textNode), List.of());
		final ScopeNode lsAncestor = ScopeNode.tag("LS", 0, 0, 0, 0, true, List.of());
		final TranslationUnit insideLs = TranslationUnit.of(List.of(textNode), List.of(lsAncestor));

		assertNotEquals(
			topLevel.contentHash(source), insideLs.contentHash(source)
		);
	}

	@Test
	@DisplayName("contentHash() ignores whitespace-only edge differences, same as core()")
	public void shouldIgnoreEdgeWhitespaceDifferences() {
		final String tightSource = "Hello world.";
		final String paddedSource = "\n\nHello world.\n\n";
		final TranslationUnit tight = wholeSourceAsUnit(tightSource);
		final TranslationUnit padded = wholeSourceAsUnit(paddedSource);
		assertEquals(tight.contentHash(tightSource), padded.contentHash(paddedSource));
	}

	private static TranslationUnit wholeSourceAsUnit(String source) {
		final ScopeNode node = ScopeNode.text(0, source.length(), 0, source.length());
		return TranslationUnit.of(List.of(node), List.of());
	}

}
