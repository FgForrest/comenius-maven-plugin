package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives a {@link TagVocabulary} from a corpus, so that no project-specific tag name has to be
 * hard-coded into the plugin or written into a configuration file.
 *
 * Derivation is deliberately **corpus-wide** rather than per document: a name must mean the same
 * thing everywhere, and deciding per file would let one document's forgotten closer silently
 * reclassify a component as prose in that file alone - producing exactly the inconsistent
 * splitting this design exists to eliminate.
 *
 * The rule that makes automatic derivation safe is the distinction between two very different
 * populations of unpaired tags:
 *
 * - A name that **never pairs anywhere** in the corpus was never markup. `List&lt;SealedEntity&gt;`
 *   in prose reaches the scanner as an opening tag with no closer, and there is no evidence
 *   whatsoever that it is a component. It is classified as literal text, silently.
 * - A name that **pairs somewhere but not everywhere** is markup with a defect. That is a
 *   forgotten closer, and it is reported rather than guessed around.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TagVocabularyDeriver {

	private final MarkupScanner scanner = new MarkupScanner(TagVocabulary.discovering());
	private final Map<String, TagStatistics> statistics = new TreeMap<>();
	private final List<Defect> defects = new ArrayList<>();

	/**
	 * Per-name evidence accumulated over the corpus.
	 *
	 * @param occurrences total number of opening, closing and self-closing occurrences
	 * @param paired      `true` when the name matched a partner at least once
	 * @param documents   documents the name occurs in
	 */
	public record TagStatistics(int occurrences, boolean paired, @Nonnull Set<String> documents) {
	}

	/**
	 * An unbalanced occurrence of a name that is markup elsewhere.
	 *
	 * @param location document the defect was found in
	 * @param defect   the offending occurrence
	 */
	public record Defect(@Nonnull String location, @Nonnull UnbalancedMarkupException.Defect defect) {
	}

	/**
	 * Feeds one document into the derivation.
	 *
	 * @param location human-readable identity of the document, typically a relative path
	 * @param source   the document text
	 */
	public void add(@Nonnull String location, @Nonnull String source) {
		Objects.requireNonNull(location, "location must not be null");
		Objects.requireNonNull(source, "source must not be null");
		final List<MarkupToken> tokens = this.scanner.scan(source);
		final TagBalance.Result balance = TagBalance.match(tokens);
		for (int i = 0; i < tokens.size(); i++) {
			final MarkupToken token = tokens.get(i);
			if (!token.isTag()) {
				continue;
			}
			final String name = Objects.requireNonNull(token.name());
			final TagStatistics previous = this.statistics.get(name);
			final Set<String> documents = previous == null ? new TreeSet<>() : previous.documents();
			documents.add(location);
			this.statistics.put(name, new TagStatistics(
				(previous == null ? 0 : previous.occurrences()) + 1,
				(previous != null && previous.paired()) || balance.pairedNames().contains(name),
				documents
			));
			if (balance.brokenIndices().contains(i)) {
				this.defects.add(new Defect(location, new UnbalancedMarkupException.Defect(
					name,
					token.start(),
					lineOf(source, token.start()),
					token.type() == MarkupToken.Type.TAG_OPEN
						? "opened but never closed"
						: "closed but never opened"
				)));
			}
		}
	}

	/**
	 * Produces the vocabulary implied by everything fed in so far.
	 *
	 * @param atomic  names whose content must never be split across units; merged with
	 *                {@link TagVocabulary#DEFAULT_ATOMIC_TAGS}
	 * @param opaque  names whose content is preserved verbatim instead of translated
	 * @param lenient when `true`, a defect degrades the occurrence to literal text instead of
	 *                failing
	 * @return the derived vocabulary
	 * @throws UnbalancedMarkupException when a name that is markup elsewhere is unbalanced
	 *                                   somewhere, and `lenient` is `false`
	 */
	@Nonnull
	public TagVocabulary derive(
		@Nonnull Set<String> atomic,
		@Nonnull Set<String> opaque,
		boolean lenient
	) {
		final Set<String> structural = new LinkedHashSet<>();
		for (final Map.Entry<String, TagStatistics> entry : this.statistics.entrySet()) {
			if (entry.getValue().paired()) {
				structural.add(entry.getKey());
			}
		}
		final List<Defect> reportable = getReportableDefects(structural);
		if (!reportable.isEmpty() && !lenient) {
			final List<UnbalancedMarkupException.Defect> flattened = new ArrayList<>(reportable.size());
			for (final Defect defect : reportable) {
				flattened.add(new UnbalancedMarkupException.Defect(
					defect.defect().name(),
					defect.defect().offset(),
					defect.defect().line(),
					defect.defect().reason() + " (in " + defect.location() + ")"
				));
			}
			throw new UnbalancedMarkupException("the derived corpus", flattened);
		}
		return TagVocabulary.of(structural, atomic, opaque, lenient);
	}

	/**
	 * Filters the accumulated defects down to those that involve a name classified as markup.
	 *
	 * A defect on a name that never pairs anywhere is not a defect at all - it is the evidence
	 * that led to classifying the name as prose.
	 *
	 * @param structural the names classified as markup
	 * @return the defects worth reporting, in the order they were found
	 */
	@Nonnull
	public List<Defect> getReportableDefects(@Nonnull Set<String> structural) {
		final List<Defect> reportable = new ArrayList<>();
		for (final Defect defect : this.defects) {
			if (structural.contains(defect.defect().name())) {
				reportable.add(defect);
			}
		}
		return reportable;
	}

	/**
	 * Returns the per-name evidence gathered so far, keyed by name.
	 *
	 * @return an unmodifiable view of the statistics
	 */
	@Nonnull
	public Map<String, TagStatistics> getStatistics() {
		return Collections.unmodifiableMap(this.statistics);
	}

	/**
	 * Renders a ready-to-paste configuration block for the derived vocabulary.
	 *
	 * Deriving on every run is correct but re-does work and leaves the project's markup contract
	 * implicit; this lets a project freeze the derived set after a single run instead of
	 * hand-writing it.
	 *
	 * @param atomic names to list as atomic
	 * @param opaque names to list as opaque
	 * @return an XML fragment suitable for a plugin configuration section
	 */
	@Nonnull
	public String suggestConfiguration(@Nonnull Set<String> atomic, @Nonnull Set<String> opaque) {
		final Set<String> structural = new TreeSet<>();
		for (final Map.Entry<String, TagStatistics> entry : this.statistics.entrySet()) {
			if (entry.getValue().paired()) {
				structural.add(entry.getKey());
			}
		}
		final StringBuilder result = new StringBuilder(256);
		result.append("<markup>\n");
		result.append("\t<structuralTags>").append(String.join(",", structural))
			.append("</structuralTags>\n");
		result.append("\t<atomicTags>").append(String.join(",", new TreeSet<>(atomic)))
			.append("</atomicTags>\n");
		result.append("\t<opaqueTags>").append(String.join(",", new TreeSet<>(opaque)))
			.append("</opaqueTags>\n");
		result.append("</markup>");
		return result.toString();
	}

	/**
	 * Computes the 1-based line number of an offset.
	 *
	 * @param source the document text
	 * @param offset the offset to locate
	 * @return the 1-based line number
	 */
	private static int lineOf(@Nonnull String source, int offset) {
		int line = 1;
		final int limit = Math.min(offset, source.length());
		for (int i = 0; i < limit; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

}
