package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a markdown body (without front matter) into sections at heading boundaries.
 * Each section includes a SHA-256 hash of its normalized content for change detection
 * in incremental translation workflows.
 *
 * Sections are split flat (not hierarchically): every ATX-style heading starts a new section.
 * Content before the first heading becomes an intro section with heading level 0.
 */
public final class DocumentSectionSplitter {

	/**
	 * Pattern to match ATX-style headings (# Heading, ## Heading, etc.)
	 * Must be at the start of a line, not inside a code block.
	 */
	private static final Pattern HEADING_PATTERN = Pattern.compile(
		"^(#{1,6})\\s+(.+)$",
		Pattern.MULTILINE
	);

	private DocumentSectionSplitter() {
		// utility class
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
		final Matcher matcher = HEADING_PATTERN.matcher(bodyContent);
		final List<HeadingMatch> headings = new ArrayList<>();

		while (matcher.find()) {
			final int level = matcher.group(1).length();
			final String text = matcher.group(2).trim();
			final int offset = matcher.start();
			headings.add(new HeadingMatch(level, text, offset));
		}

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
