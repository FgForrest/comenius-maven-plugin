package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares the markup of a source fragment against its translation and reports what changed
 * structurally - the same comparison validated live in {@code ScopeTreeTranslationProbe}, promoted
 * to main sources so the production {@code Translator} can use it too.
 *
 * Block-level tags and headings are compared as an **ordered sequence**, because their order is
 * document structure. Inline tags are compared as a **multiset**, because word order legitimately
 * moves them within a translated sentence. Blank lines are compared by **count**, because a blank
 * line carries no {@link MarkupToken} at all - a model that silently drops one is invisible to
 * both checks above, and this was measured to happen in practice (see the seam-whitespace and
 * silent-truncation defects this comparison exists to catch).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class StructuralComparator {

	private StructuralComparator() {
	}

	/**
	 * Compares a translated fragment against its source.
	 *
	 * @param vocabulary the vocabulary to scan with
	 * @param before     the source fragment
	 * @param after      the translated fragment
	 * @return problems found, empty when the structure survived intact
	 */
	@Nonnull
	public static List<String> compare(
		@Nonnull TagVocabulary vocabulary, @Nonnull String before, @Nonnull String after
	) {
		final List<String> problems = new ArrayList<>();
		final MarkupScanner scanner = new MarkupScanner(vocabulary);
		final List<MarkupToken> sourceTokens = scanner.scan(before);
		final List<MarkupToken> targetTokens = scanner.scan(after);

		final String sourceBlocks = describeBlocks(sourceTokens);
		final String targetBlocks = describeBlocks(targetTokens);
		if (!sourceBlocks.equals(targetBlocks)) {
			problems.add("block structure changed - was: " + sourceBlocks + " - now: " + targetBlocks);
		}
		final Map<String, Integer> sourceInline = countInline(sourceTokens);
		final Map<String, Integer> targetInline = countInline(targetTokens);
		if (!sourceInline.equals(targetInline)) {
			problems.add("inline tags changed - was: " + sourceInline + " - now: " + targetInline);
		}

		final long sourceBlankLines = countBlankLines(before);
		final long targetBlankLines = countBlankLines(after);
		if (sourceBlankLines != targetBlankLines) {
			problems.add("blank line count changed (" + sourceBlankLines + " -> " + targetBlankLines + ")");
		}
		return problems;
	}

	private static long countBlankLines(@Nonnull String text) {
		return text.lines().filter(String::isBlank).count();
	}

	@Nonnull
	private static String describeBlocks(@Nonnull List<MarkupToken> tokens) {
		final StringBuilder result = new StringBuilder(64);
		for (final MarkupToken token : tokens) {
			switch (token.type()) {
				case HEADING -> result.append("h").append(token.level()).append(' ');
				case CODE -> result.append("code ");
				case TAG_OPEN -> appendIfBlock(result, token, "<" + token.name() + ">");
				case TAG_CLOSE -> appendIfBlock(result, token, "</" + token.name() + ">");
				case TAG_SELF_CLOSING -> appendIfBlock(result, token, "<" + token.name() + "/>");
				case COMMENT -> result.append("comment ");
			}
		}
		return result.toString().trim();
	}

	private static void appendIfBlock(
		@Nonnull StringBuilder result, @Nonnull MarkupToken token, @Nonnull String text
	) {
		if (token.blockLevel()) {
			result.append(text).append(' ');
		}
	}

	@Nonnull
	private static Map<String, Integer> countInline(@Nonnull List<MarkupToken> tokens) {
		final Map<String, Integer> counts = new TreeMap<>();
		for (final MarkupToken token : tokens) {
			if (token.isTag() && !token.blockLevel()) {
				counts.merge(token.name(), 1, Integer::sum);
			}
		}
		return counts;
	}

}
