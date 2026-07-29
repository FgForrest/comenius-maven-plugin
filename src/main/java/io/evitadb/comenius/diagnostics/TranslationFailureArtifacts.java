package io.evitadb.comenius.diagnostics;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes out everything needed to diagnose a translation the structural gates refused.
 *
 * <p>A gate that rejects a translation protects the corpus, but it also discards the only copy of
 * what the model actually produced. The failure is then reported as a one-line message, and
 * working out <em>why</em> it happened costs another paid run - which, when the failure is
 * deterministic, buys nothing but the same message again. This writer keeps the source text, every
 * rejected attempt and the reasons on disk, so the second look at the problem is free.</p>
 *
 * <p>Artifacts are laid out as {@code <baseDir>/<language-tag>/<source>/<unit>/}, mirroring the
 * shape of the run that produced them: one directory per target language, then per source
 * document, then per failing unit (the whole body, or one section of it).</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class TranslationFailureArtifacts {

	/**
	 * How many trailing path segments of a source file name its artifact directory.
	 *
	 * <p>The bare file name is not enough - {@code fetching.md} exists under several directories in
	 * a real corpus, and two of them failing in one run would write into the same place. Three
	 * segments ({@code query_requirements_fetching.md}) stays readable and disambiguates every
	 * collision seen in practice.</p>
	 */
	private static final int NAMING_SEGMENTS = 3;

	/**
	 * File name of the report listing why the translation was rejected.
	 */
	private static final String REASON_FILE = "reason.txt";

	@Nonnull
	private final Path baseDir;

	/**
	 * Creates a writer rooted at the given directory. The directory is created on first use, not
	 * here - a run in which nothing fails leaves no trace.
	 *
	 * @param baseDir the directory to write failure artifacts under
	 */
	public TranslationFailureArtifacts(@Nonnull Path baseDir) {
		this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null")
			.toAbsolutePath().normalize();
	}

	/**
	 * Persists one rejected translation.
	 *
	 * @param locale      the target language the translation was rejected for
	 * @param sourceFile  the source document being translated
	 * @param unit        identifies the failing unit within that document, e.g. {@code body} or
	 *                    {@code section-4}
	 * @param reasons     why the translation was rejected, one entry per problem
	 * @param attachments the texts to keep, keyed by file name (iteration order is preserved, so
	 *                    pass a {@link java.util.LinkedHashMap} to control it)
	 * @return the directory the artifacts were written to
	 * @throws IOException if the artifacts cannot be written
	 */
	@Nonnull
	public Path record(
		@Nonnull Locale locale,
		@Nonnull Path sourceFile,
		@Nonnull String unit,
		@Nonnull List<String> reasons,
		@Nonnull Map<String, String> attachments
	) throws IOException {
		Objects.requireNonNull(locale, "locale must not be null");
		Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		Objects.requireNonNull(unit, "unit must not be null");
		Objects.requireNonNull(reasons, "reasons must not be null");
		Objects.requireNonNull(attachments, "attachments must not be null");

		final Path directory = this.baseDir
			.resolve(sanitize(locale.toLanguageTag()))
			.resolve(nameFor(sourceFile))
			.resolve(sanitize(unit));
		Files.createDirectories(directory);

		final StringBuilder report = new StringBuilder(256);
		report.append("source: ").append(sourceFile).append(System.lineSeparator());
		report.append("locale: ").append(locale.toLanguageTag()).append(System.lineSeparator());
		report.append("unit:   ").append(unit).append(System.lineSeparator());
		report.append(System.lineSeparator());
		for (final String reason : reasons) {
			report.append("- ").append(reason).append(System.lineSeparator());
		}
		Files.writeString(directory.resolve(REASON_FILE), report.toString(), StandardCharsets.UTF_8);

		for (final Map.Entry<String, String> attachment : attachments.entrySet()) {
			Files.writeString(
				directory.resolve(sanitize(attachment.getKey())),
				attachment.getValue(),
				StandardCharsets.UTF_8
			);
		}

		return directory;
	}

	/**
	 * Builds the artifact directory name for a source file from its trailing path segments.
	 *
	 * @param sourceFile the source document
	 * @return a directory name identifying that document
	 */
	@Nonnull
	private static String nameFor(@Nonnull Path sourceFile) {
		final Path normalized = sourceFile.toAbsolutePath().normalize();
		final int segments = normalized.getNameCount();
		final StringBuilder name = new StringBuilder(64);
		for (int i = Math.max(0, segments - NAMING_SEGMENTS); i < segments; i++) {
			if (!name.isEmpty()) {
				name.append('_');
			}
			name.append(sanitize(normalized.getName(i).toString()));
		}
		return name.isEmpty() ? "unnamed" : name.toString();
	}

	/**
	 * Reduces a caller-supplied string to characters that are safe in a file name on any platform.
	 *
	 * @param value the string to sanitize
	 * @return the sanitized string
	 */
	@Nonnull
	private static String sanitize(@Nonnull String value) {
		final String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
		return sanitized.isBlank() ? "unnamed" : sanitized;
	}
}
