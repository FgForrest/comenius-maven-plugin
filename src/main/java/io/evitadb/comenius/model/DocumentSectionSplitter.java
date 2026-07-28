package io.evitadb.comenius.model;

import io.evitadb.comenius.structure.MarkdownHeadings;
import io.evitadb.comenius.structure.MarkupScanner;
import io.evitadb.comenius.structure.MarkupToken;
import io.evitadb.comenius.structure.TagBalance;
import io.evitadb.comenius.structure.TagVocabulary;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Splits a markdown body (without front matter) into sections at heading boundaries.
 * Each section includes a SHA-256 hash of its normalized content for change detection
 * in incremental translation workflows.
 *
 * Sections are split flat (not hierarchically): every ATX-style heading starts a new section.
 * Content before the first heading becomes an intro section with heading level 0.
 */
public final class DocumentSectionSplitter {

	private DocumentSectionSplitter() {
		// utility class
	}

	/**
	 * Finds every ATX heading a reader sees, skipping lines inside fenced code blocks.
	 *
	 * <p>Scanning with a bare heading regex is not enough: shell snippets routinely contain
	 * comments such as {@code # run in foreground}, which look exactly like headings. Treating
	 * those as section boundaries cuts a section in half through the middle of a code fence,
	 * and the fragments are then handed to the translator as if they were whole documents.
	 * Three documents in the evitaDB corpus contain such lines.</p>
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @return headings in document order, each carrying its offset in {@code bodyContent}
	 */
	@Nonnull
	private static List<HeadingMatch> findHeadings(@Nonnull String bodyContent) {
		return MarkdownHeadings.scan(bodyContent).stream()
			.map(heading -> new HeadingMatch(heading.level(), heading.text(), heading.offset()))
			.toList();
	}

	/**
	 * Splits a markdown body into heading-delimited sections.
	 * Each heading starts a new section. Content before the first heading is
	 * an intro section (level 0). If the body contains no headings, the entire
	 * body becomes a single intro section.
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @return list of document sections in document order
	 */
	@Nonnull
	public static List<DocumentSection> split(@Nonnull String bodyContent) {
		Objects.requireNonNull(bodyContent, "bodyContent must not be null");

		if (bodyContent.isEmpty()) {
			return List.of();
		}

		final List<DocumentSection> sections = new ArrayList<>();
		final List<HeadingMatch> headings = findHeadings(bodyContent);

		// No headings — entire body is one intro section
		if (headings.isEmpty()) {
			sections.add(new DocumentSection(
				0, 0, null, bodyContent, computeHash(bodyContent)
			));
			return sections;
		}

		int sectionIndex = 0;

		// Intro content before first heading
		final int firstHeadingOffset = headings.get(0).offset();
		if (firstHeadingOffset > 0) {
			final String introContent = bodyContent.substring(0, firstHeadingOffset);
			if (!introContent.isBlank()) {
				sections.add(new DocumentSection(
					sectionIndex++, 0, null, introContent, computeHash(introContent)
				));
			}
		}

		// Each heading starts a new section
		for (int i = 0; i < headings.size(); i++) {
			final HeadingMatch heading = headings.get(i);
			final int start = heading.offset();
			final int end = (i + 1 < headings.size())
				? headings.get(i + 1).offset()
				: bodyContent.length();
			final String sectionContent = bodyContent.substring(start, end);

			sections.add(new DocumentSection(
				sectionIndex++,
				heading.level(),
				heading.text(),
				sectionContent,
				computeHash(sectionContent)
			));
		}

		return sections;
	}

	/**
	 * Splits a markdown body into heading-delimited sections, additionally ensuring that no
	 * section tears a custom block tag in half at its boundary.
	 *
	 * Plain heading-offset splitting (as done by {@link #split(String)}) is unaware of custom
	 * tags such as {@code <Note>} or {@code <LS>}. A tag that opens immediately before a heading
	 * (e.g. a language-switch block wrapping that heading's content) or that wraps a heading used
	 * as its own title (e.g. {@code <NoteTitle>##### Title</NoteTitle>}) gets its opening half
	 * stranded in the previous section and its closing half orphaned in the next - silently
	 * corrupting the document when sections are independently retranslated and rejoined. This
	 * overload prevents that in two passes:
	 *
	 * 1. **Shift back**: a heading boundary that is immediately preceded (only whitespace
	 *    between) by a still-open tag is moved to just before that tag, so the tag travels with
	 *    the section it actually belongs to instead of the one it doesn't.
	 * 2. **Merge forward**: if a boundary would still leave a section with an unclosed tag (a
	 *    single wrapper spanning several headings), that boundary is dropped and the section
	 *    grows to include the next candidate, repeating until it is self-balanced.
	 *
	 * When {@code vocabulary} is {@code null} (derivation failed for this run), this delegates to
	 * {@link #split(String)} unchanged - the safety checks are skipped, not enforced against a
	 * guess.
	 *
	 * @param bodyContent the markdown body content (without front matter)
	 * @param vocabulary  the corpus-derived tag vocabulary, or {@code null} to skip tag-awareness
	 * @return list of document sections in document order, each self-balanced under the vocabulary
	 */
	@Nonnull
	public static List<DocumentSection> split(
		@Nonnull String bodyContent, @Nullable TagVocabulary vocabulary
	) {
		Objects.requireNonNull(bodyContent, "bodyContent must not be null");
		if (vocabulary == null) {
			return split(bodyContent);
		}
		if (bodyContent.isEmpty()) {
			return List.of();
		}

		final List<HeadingMatch> headings = findHeadings(bodyContent);
		if (headings.isEmpty()) {
			return List.of(new DocumentSection(0, 0, null, bodyContent, computeHash(bodyContent)));
		}

		final MarkupScanner scanner = new MarkupScanner(vocabulary);
		final List<MarkupToken> tagTokens = scanner.scan(bodyContent).stream()
			.filter(MarkupToken::isTag)
			.toList();

		// Pass 1: shift each heading boundary backward over dangling immediately-preceding opens.
		final TreeSet<Integer> candidateSet = new TreeSet<>();
		candidateSet.add(0);
		for (final HeadingMatch heading : headings) {
			final int shifted = shiftBoundaryBeforeDanglingOpens(bodyContent, heading.offset(), tagTokens);
			if (shifted > 0) {
				candidateSet.add(shifted);
			}
		}
		candidateSet.add(bodyContent.length());
		final List<Integer> candidates = new ArrayList<>(candidateSet);

		// Pass 2: merge sections forward until each is self-balanced under the real vocabulary.
		final List<Integer> boundaries = new ArrayList<>();
		int runningStart = candidates.get(0);
		for (int i = 1; i < candidates.size(); i++) {
			final int candidateEnd = candidates.get(i);
			final boolean lastCandidate = i == candidates.size() - 1;
			final boolean balanced = TagBalance.match(
				scanner.scan(bodyContent.substring(runningStart, candidateEnd))
			).isBalanced();
			if (balanced || lastCandidate) {
				boundaries.add(runningStart);
				runningStart = candidateEnd;
			}
		}
		boundaries.add(bodyContent.length());

		final List<DocumentSection> sections = new ArrayList<>();
		int sectionIndex = 0;
		for (int b = 0; b < boundaries.size() - 1; b++) {
			final int start = boundaries.get(b);
			final int end = boundaries.get(b + 1);
			if (start >= end) {
				continue;
			}
			final String sectionContent = bodyContent.substring(start, end);
			if (start == 0 && findHeadingInRange(headings, start, end) == null) {
				// Leading content before the first heading - an intro section, same as split(String).
				if (sectionContent.isBlank()) {
					continue;
				}
				sections.add(new DocumentSection(
					sectionIndex++, 0, null, sectionContent, computeHash(sectionContent)
				));
				continue;
			}
			final HeadingMatch leading = findHeadingInRange(headings, start, end);
			final int level = leading != null ? leading.level() : 0;
			final String text = leading != null ? leading.text() : null;
			sections.add(new DocumentSection(
				sectionIndex++, level, text, sectionContent, computeHash(sectionContent)
			));
		}
		return sections;
	}

	/**
	 * Finds the first heading whose offset falls within {@code [start, end)}.
	 *
	 * @param headings all headings in the document
	 * @param start    range start (inclusive)
	 * @param end      range end (exclusive)
	 * @return the first heading in range, or {@code null} if none
	 */
	@Nullable
	private static HeadingMatch findHeadingInRange(
		@Nonnull List<HeadingMatch> headings, int start, int end
	) {
		for (final HeadingMatch heading : headings) {
			if (heading.offset() >= start && heading.offset() < end) {
				return heading;
			}
		}
		return null;
	}

	/**
	 * Shifts a candidate section boundary backward over a run of immediately-preceding (only
	 * whitespace in between) opening tags that are not yet closed by the time the boundary is
	 * reached - so an opening tag travels with the section it wraps instead of the one before it.
	 *
	 * @param content    the full document body
	 * @param boundary   the candidate boundary offset (a heading's start)
	 * @param tagTokens  every tag token in the document, in ascending offset order
	 * @return the shifted boundary offset
	 */
	private static int shiftBoundaryBeforeDanglingOpens(
		@Nonnull String content, int boundary, @Nonnull List<MarkupToken> tagTokens
	) {
		while (true) {
			MarkupToken prev = null;
			int prevIndex = -1;
			for (int i = tagTokens.size() - 1; i >= 0; i--) {
				if (tagTokens.get(i).end() <= boundary) {
					prev = tagTokens.get(i);
					prevIndex = i;
					break;
				}
			}
			if (prev == null || prev.type() != MarkupToken.Type.TAG_OPEN) {
				return boundary;
			}
			if (!content.substring(prev.end(), boundary).isBlank()) {
				return boundary;
			}
			// Is prev's matching close before 'boundary'? Simple same-name stack scan forward.
			int depth = 0;
			boolean closedBeforeBoundary = false;
			for (int i = prevIndex; i < tagTokens.size(); i++) {
				final MarkupToken t = tagTokens.get(i);
				if (t.start() >= boundary && i != prevIndex) {
					break;
				}
				if (t.name() == null || !t.name().equals(prev.name())) {
					continue;
				}
				if (t.type() == MarkupToken.Type.TAG_OPEN) {
					depth++;
				} else if (t.type() == MarkupToken.Type.TAG_CLOSE) {
					depth--;
					if (depth == 0) {
						closedBeforeBoundary = t.start() < boundary;
						break;
					}
				}
			}
			if (closedBeforeBoundary) {
				return boundary;
			}
			boundary = prev.start();
		}
	}

	/**
	 * Validates that two lists of sections have matching heading structure.
	 * Checks that section count, heading levels, and intro presence match.
	 * Heading text is NOT checked (it may be translated).
	 *
	 * @param sourceSections     sections from the source document
	 * @param translatedSections sections from the translated document
	 * @throws HeadingStructureMismatchException if structures don't match
	 */
	public static void validateHeadingStructure(
		@Nonnull List<DocumentSection> sourceSections,
		@Nonnull List<DocumentSection> translatedSections
	) throws HeadingStructureMismatchException {
		Objects.requireNonNull(sourceSections, "sourceSections must not be null");
		Objects.requireNonNull(translatedSections, "translatedSections must not be null");

		if (sourceSections.size() != translatedSections.size()) {
			throw new HeadingStructureMismatchException(
				"Section count mismatch: source has " + sourceSections.size() +
					" sections, translation has " + translatedSections.size() + " sections",
				formatStructure(sourceSections),
				formatStructure(translatedSections)
			);
		}

		for (int i = 0; i < sourceSections.size(); i++) {
			final DocumentSection source = sourceSections.get(i);
			final DocumentSection translated = translatedSections.get(i);

			if (source.headingLevel() != translated.headingLevel()) {
				throw new HeadingStructureMismatchException(
					"Heading level mismatch at section " + i + ": source has level " +
						source.headingLevel() + ", translation has level " +
						translated.headingLevel(),
					formatStructure(sourceSections),
					formatStructure(translatedSections)
				);
			}
		}
	}

	/**
	 * Computes SHA-256 hash of normalized content.
	 * Normalization: trim whitespace, normalize line endings to LF.
	 *
	 * @param content the content to hash
	 * @return hex-encoded SHA-256 hash
	 */
	@Nonnull
	static String computeHash(@Nonnull String content) {
		final String normalized = content.trim().replace("\r\n", "\n");
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed to be available in every JVM
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	/**
	 * Formats section structure for error messages.
	 *
	 * @param sections the sections to format
	 * @return human-readable structure description
	 */
	@Nonnull
	private static String formatStructure(@Nonnull List<DocumentSection> sections) {
		final StringBuilder sb = new StringBuilder();
		for (final DocumentSection section : sections) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			if (section.isIntro()) {
				sb.append("intro");
			} else {
				sb.append("H").append(section.headingLevel());
			}
		}
		return "[" + sb + "]";
	}

	/**
	 * Internal record for heading match information during parsing.
	 */
	private record HeadingMatch(int level, @Nonnull String text, int offset) {}
}
