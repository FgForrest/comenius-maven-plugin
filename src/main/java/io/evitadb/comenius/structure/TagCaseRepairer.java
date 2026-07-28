package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically restores the case of a tag name that drifted during translation, using the
 * source fragment as the sole authority on correct casing - no model call involved.
 *
 * {@link TagVocabulary} matches names case-sensitively by design: a corpus may legitimately mix
 * literal-HTML lowercase `&lt;tr&gt;` with a capitalized `&lt;Tr&gt;` component, and only case
 * sensitivity tells them apart. The cost of that design is that a model closing `&lt;Td&gt;` as
 * `&lt;/td&gt;` does not produce a *mismatched* tag the balance check can name - it produces a
 * token the real vocabulary does not recognise as a tag at all, which desynchronizes every
 * open/close pairing after it. Prompting the model to preserve case has been measured to be
 * probabilistic rather than a fix (the same instruction produced 0 lowercase closes on one run and
 * 4 on the next, on the same document). The casing is not a guess, though: it is already known
 * from the source, which the model never touches, so the repair belongs here instead of in the
 * prompt.
 *
 * Two independent guards protect the exact ambiguity {@link TagVocabulary} exists to resolve:
 *
 * - a case-fold occurring with more than one distinct casing **within this fragment's source** is
 *   left alone entirely, because there is no local evidence which casing this occurrence belongs
 *   to;
 * - a case-fold occurring with more than one distinct casing **anywhere in the corpus-derived
 *   vocabulary**, or a translated name that is itself already a recognised structural tag in its
 *   own right, is also left alone - a single unit's source can look unambiguous locally while the
 *   fold is genuinely ambiguous corpus-wide, and only the corpus-derived vocabulary has that
 *   evidence.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class TagCaseRepairer {

	/**
	 * Private constructor, this class is a pure function holder.
	 */
	private TagCaseRepairer() {
	}

	/**
	 * One tag name whose case was found to have drifted.
	 *
	 * @param from      the name as it appears in the translated fragment
	 * @param to        the canonical name, taken from the source
	 * @param nameStart inclusive offset of the name within the translated fragment
	 * @param nameEnd   exclusive offset of the name within the translated fragment
	 */
	public record Fix(@Nonnull String from, @Nonnull String to, int nameStart, int nameEnd) {
	}

	/**
	 * Finds every tag name in the translated fragment whose case-fold matches an unambiguous
	 * source name but whose exact case differs, and which the corpus-derived vocabulary does not
	 * already recognise as a distinct tag in its own right.
	 *
	 * Scanning is deliberately vocabulary-free for tag *discovery*: a case-drifted closing tag is
	 * precisely the kind of occurrence a real, case-sensitive vocabulary fails to recognise as a
	 * tag in the first place. The vocabulary is still consulted, but only as a corpus-wide veto.
	 *
	 * @param vocabulary the corpus-derived vocabulary, consulted only to veto ambiguous folds
	 * @param source     the original fragment, assumed correctly cased
	 * @param translated the translated fragment to check
	 * @return fixes in document order, empty when nothing drifted
	 */
	@Nonnull
	public static List<Fix> find(
		@Nonnull TagVocabulary vocabulary, @Nonnull String source, @Nonnull String translated
	) {
		Objects.requireNonNull(vocabulary, "vocabulary must not be null");
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(translated, "translated must not be null");

		final Map<String, String> canonical = canonicalNames(source);
		if (canonical.isEmpty()) {
			return List.of();
		}
		final Set<String> corpusAmbiguousFolds = ambiguousFolds(vocabulary.getStructuralTags());
		final List<Fix> fixes = new ArrayList<>();
		for (final MarkupToken token : rawTagTokens(translated)) {
			final String name = Objects.requireNonNull(token.name());
			final String fold = name.toLowerCase(Locale.ROOT);
			if (corpusAmbiguousFolds.contains(fold) || vocabulary.isStructural(name)) {
				// either two legitimate corpus-wide meanings share this fold, or this exact name
				// is already a recognised tag on its own - neither is drift to repair
				continue;
			}
			final String canonicalName = canonical.get(fold);
			if (canonicalName != null && !canonicalName.equals(name)) {
				final int nameStart = nameStart(token);
				fixes.add(new Fix(name, canonicalName, nameStart, nameStart + name.length()));
			}
		}
		return fixes;
	}

	/**
	 * Applies every fix {@link #find} would report and returns the corrected fragment.
	 *
	 * @param vocabulary the corpus-derived vocabulary, consulted only to veto ambiguous folds
	 * @param source     the original fragment, assumed correctly cased
	 * @param translated the translated fragment to repair
	 * @return the fragment with every case-drifted tag name corrected; identical to
	 *         {@code translated} when nothing needed fixing
	 */
	@Nonnull
	public static String repair(
		@Nonnull TagVocabulary vocabulary, @Nonnull String source, @Nonnull String translated
	) {
		final List<Fix> fixes = find(vocabulary, source, translated);
		if (fixes.isEmpty()) {
			return translated;
		}
		// applied back to front so that earlier offsets stay valid as later ones are spliced in
		final List<Fix> sorted = new ArrayList<>(fixes);
		sorted.sort(Comparator.comparingInt(Fix::nameStart).reversed());
		final StringBuilder repaired = new StringBuilder(translated);
		for (final Fix fix : sorted) {
			repaired.replace(fix.nameStart(), fix.nameEnd(), fix.to());
		}
		return repaired.toString();
	}

	/**
	 * Builds a case-fold to exact-name map from every tag occurrence in the source, dropping any
	 * fold that maps to more than one distinct casing - such a fold is genuinely ambiguous, not a
	 * defect to repair.
	 *
	 * @param source the original fragment
	 * @return canonical names keyed by lowercase fold, ambiguous folds excluded
	 */
	@Nonnull
	private static Map<String, String> canonicalNames(@Nonnull String source) {
		final Map<String, String> canonical = new HashMap<>();
		final Set<String> ambiguous = new HashSet<>();
		for (final MarkupToken token : rawTagTokens(source)) {
			final String name = Objects.requireNonNull(token.name());
			final String fold = name.toLowerCase(Locale.ROOT);
			if (ambiguous.contains(fold)) {
				continue;
			}
			final String existing = canonical.putIfAbsent(fold, name);
			if (existing != null && !existing.equals(name)) {
				canonical.remove(fold);
				ambiguous.add(fold);
			}
		}
		return canonical;
	}

	/**
	 * Finds every case-fold that more than one distinct structural tag name maps to.
	 *
	 * The corpus-derived vocabulary, not any single unit, is the only place with corpus-wide
	 * evidence: a fold can look perfectly unambiguous within one unit's source while a *different*
	 * document elsewhere legitimately uses the other casing for a different tag.
	 *
	 * @param structuralTags every structural tag name the corpus derivation recognised
	 * @return the case-folds that must never be repaired
	 */
	@Nonnull
	private static Set<String> ambiguousFolds(@Nonnull Set<String> structuralTags) {
		final Map<String, String> byFold = new HashMap<>();
		final Set<String> ambiguous = new HashSet<>();
		for (final String name : structuralTags) {
			final String fold = name.toLowerCase(Locale.ROOT);
			final String existing = byFold.putIfAbsent(fold, name);
			if (existing != null && !existing.equals(name)) {
				ambiguous.add(fold);
			}
		}
		return ambiguous;
	}

	/**
	 * Scans a fragment for every syntactically valid tag, regardless of any configured vocabulary.
	 *
	 * @param text the fragment to scan
	 * @return every tag token found, in document order; code and comments already excluded
	 */
	@Nonnull
	private static List<MarkupToken> rawTagTokens(@Nonnull String text) {
		final List<MarkupToken> tags = new ArrayList<>();
		for (final MarkupToken token : new MarkupScanner(TagVocabulary.discovering()).scan(text)) {
			if (token.isTag()) {
				tags.add(token);
			}
		}
		return tags;
	}

	/**
	 * Computes the offset of a tag's name within its own token span.
	 *
	 * @param token a tag token, opening, closing or self-closing
	 * @return the offset of the first character of the name
	 */
	private static int nameStart(@Nonnull MarkupToken token) {
		final int prefixLength = token.type() == MarkupToken.Type.TAG_CLOSE ? 2 : 1;
		return token.start() + prefixLength;
	}

}
