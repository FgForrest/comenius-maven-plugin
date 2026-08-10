package io.evitadb.comenius;

import io.evitadb.comenius.git.CommitInfo;
import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.structure.MarkdownHeadings;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the translation workflow: analyzing files, validating git state,
 * creating appropriate translation jobs, and reporting dry-run results.
 */
public final class TranslationOrchestrator {

	@Nonnull
	private final GitService gitService;
	@Nonnull
	private final Path sourceDir;
	@Nonnull
	private final Log log;
	/**
	 * Documents whose translation already carries the current source's structure while their
	 * {@code commit} field lags behind. Written from the traversal, which may run in parallel.
	 */
	@Nonnull
	private final Set<Path> translationsAhead = ConcurrentHashMap.newKeySet();

	/**
	 * Creates an orchestrator with the required services.
	 *
	 * @param gitService service for git operations
	 * @param sourceDir  the source root directory for relative path calculation
	 * @param log        Maven log for output
	 */
	public TranslationOrchestrator(
		@Nonnull GitService gitService,
		@Nonnull Path sourceDir,
		@Nonnull Log log
	) {
		this.gitService = Objects.requireNonNull(gitService, "gitService must not be null");
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null").toAbsolutePath().normalize();
		this.log = Objects.requireNonNull(log, "log must not be null");
	}

	/**
	 * Creates a translation job for a file, or returns empty if the file should be skipped.
	 * Validates git state and determines whether this is a new or incremental translation.
	 *
	 * @param sourceFile                    the source markdown file
	 * @param sourceContent                 the content of the source file
	 * @param targetDir                     the target directory for translations
	 * @param locale                        the target locale
	 * @param instructions                  accumulated instructions from `.comenius-instructions` files (may be null)
	 * @param translatableFrontMatterFields optional list of front matter field names to translate
	 * @return Optional containing the job, or empty if file should be skipped
	 * @throws IOException if an I/O error occurs
	 */
	@Nonnull
	public Optional<TranslationJob> createJob(
		@Nonnull Path sourceFile,
		@Nonnull String sourceContent,
		@Nonnull Path targetDir,
		@Nonnull Locale locale,
		@Nullable String instructions,
		@Nullable List<String> translatableFrontMatterFields
	) throws IOException {
		Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		Objects.requireNonNull(sourceContent, "sourceContent must not be null");
		Objects.requireNonNull(targetDir, "targetDir must not be null");
		Objects.requireNonNull(locale, "locale must not be null");

		final Path relativePath = this.sourceDir.relativize(sourceFile.toAbsolutePath().normalize());

		// Validate git state - file must be committed
		if (!this.gitService.isFileCommitted(sourceFile)) {
			this.log.error("[ERROR] Skipping file with uncommitted changes: " + relativePath +
				". Commit your changes before translation.");
			return Optional.empty();
		}

		// Calculate target file path
		final Path targetFile = targetDir.resolve(relativePath);

		// Check if target file exists and get its commit field
		String translatedCommit = null;
		String existingTranslation = null;
		if (Files.exists(targetFile)) {
			existingTranslation = Files.readString(targetFile, StandardCharsets.UTF_8);
			final MarkdownDocument existingDoc = new MarkdownDocument(existingTranslation);
			translatedCommit = existingDoc.getProperty("commit").orElse(null);

			if (translatedCommit == null) {
				this.log.warn("[WARN] Existing translation has no commit field: " + relativePath +
					". Treating as new file.");
			}
		}

		// Build CommitInfo with all git data
		final Optional<CommitInfo> commitInfoOpt = this.gitService.buildCommitInfo(sourceFile, translatedCommit);
		if (commitInfoOpt.isEmpty()) {
			this.log.error("[ERROR] Skipping untracked file: " + relativePath +
				". Add and commit the file before translation.");
			return Optional.empty();
		}
		final CommitInfo commitInfo = commitInfoOpt.get();

		// buildCommitInfo drops a recorded commit this repository cannot reach, which turns the
		// job into a full retranslation. Say so - the alternative is a document that silently
		// stops syncing forever.
		if (translatedCommit != null && commitInfo.translatedCommit() == null) {
			this.log.warn("[WARN] Recorded commit " + translatedCommit + " for " + relativePath +
				" is not reachable in this repository (rewritten history, a different clone, or a" +
				" shallow checkout). Falling back to a full retranslation.");
		}

		// Check if up-to-date
		if (commitInfo.isUpToDate()) {
			return Optional.empty();
		}

		// Create appropriate job type based on CommitInfo state
		if (commitInfo.isNewFile() || existingTranslation == null) {
			return Optional.of(new TranslateNewJob(
				sourceFile, targetFile, locale, sourceContent, commitInfo.currentCommit(), instructions,
				translatableFrontMatterFields
			));
		}

		// Incremental update - verify we have original source
		if (commitInfo.originalSource() == null) {
			this.log.warn("[WARN] Cannot retrieve source at commit " + commitInfo.translatedCommit() +
				" for " + relativePath + ". Treating as new file.");
			return Optional.of(new TranslateNewJob(
				sourceFile, targetFile, locale, sourceContent, commitInfo.currentCommit(), instructions,
				translatableFrontMatterFields
			));
		}

		if (isTranslationAhead(commitInfo.originalSource(), sourceContent, existingTranslation)) {
			this.translationsAhead.add(relativePath);
			this.log.warn(String.format(
				"[AHEAD] %s: the translation already has the structure of the source at %s, but its" +
					" commit field still says %s - it was updated without that field being bumped." +
					" Skipping it: the incremental path cannot map the new source onto a translation" +
					" that no longer matches the old one, so it would fall back to translating the" +
					" whole body from scratch and overwrite that work. Set 'commit: %s' in its front" +
					" matter once you have confirmed it is current.",
				relativePath,
				commitInfo.currentCommit(),
				commitInfo.translatedCommit(),
				commitInfo.currentCommit()
			));
			return Optional.empty();
		}

		return Optional.of(new TranslateIncrementalJob(
			sourceFile, targetFile, locale, sourceContent, commitInfo.currentCommit(), instructions,
			translatableFrontMatterFields,
			commitInfo.originalSource(),
			existingTranslation,
			commitInfo.translatedCommit(),
			commitInfo.commitCount()
		));
	}

	/**
	 * Decides whether an existing translation has already been brought up to the current source
	 * while its {@code commit} field was left behind.
	 *
	 * <p>Staleness is normally judged from that field alone, and for a file nobody touches by hand
	 * that is exact. It is not exact for a file whose translation is written in the same commit as
	 * the source change - the translation is current, the field is not, and the file is queued for
	 * a retranslation with nothing to translate. Worse, the retranslation is not a no-op: section
	 * mapping needs the translation to match the source <em>at the recorded commit</em>, this one
	 * matches the newer source instead, so the mapping is abandoned and the whole body is
	 * retranslated over hand-written text.</p>
	 *
	 * <p>The signal is the heading-level sequence, the one part of document structure that survives
	 * translation. Two conditions must hold together: the source's structure really did change
	 * since the recorded commit, and the translation already carries the <em>new</em> structure.
	 * A translation that merely still matches the old structure is ordinary staleness and is
	 * translated as before.</p>
	 *
	 * <p>This deliberately proves less than "the translation is up to date" - prose can change
	 * without moving a heading, and no structural test can see that. It proves that a full-body
	 * retranslation, which is the only thing that would otherwise happen, would destroy more than
	 * it repairs. Hence: report, and let a human decide.</p>
	 *
	 * @param originalSource      the source content at the commit the translation records
	 * @param currentSource       the current source content
	 * @param existingTranslation the existing translation content
	 * @return true when the translation is ahead of the commit field it carries
	 */
	private static boolean isTranslationAhead(
		@Nonnull String originalSource,
		@Nonnull String currentSource,
		@Nonnull String existingTranslation
	) {
		final List<Integer> originalLevels = headingLevels(originalSource);
		final List<Integer> currentLevels = headingLevels(currentSource);
		if (originalLevels.equals(currentLevels)) {
			return false;
		}
		return currentLevels.equals(headingLevels(existingTranslation));
	}

	/**
	 * Extracts the heading-level sequence of a document's body, ignoring its front matter.
	 *
	 * @param document the raw document content, front matter included
	 * @return heading levels in document order
	 */
	@Nonnull
	private static List<Integer> headingLevels(@Nonnull String document) {
		return MarkdownHeadings.levels(new MarkdownDocument(document).getBodyContent());
	}

	/**
	 * Reports a translation job for dry-run output.
	 *
	 * @param job          the job to report
	 * @param relativePath the relative path for display
	 */
	public void reportJob(@Nonnull TranslationJob job, @Nonnull Path relativePath) {
		Objects.requireNonNull(job, "job must not be null");
		Objects.requireNonNull(relativePath, "relativePath must not be null");

		if (job instanceof TranslateNewJob) {
			this.log.info("[NEW] " + relativePath);
		} else if (job instanceof TranslateIncrementalJob incrementalJob) {
			this.log.info(String.format(
				"[UPDATE] %s: %s -> %s (%d commits)",
				relativePath,
				incrementalJob.getTranslatedCommitShort(),
				incrementalJob.getCurrentCommitShort(),
				incrementalJob.getCommitCount()
			));
		}
	}

	/**
	 * Reports that a file was skipped because it's up-to-date.
	 *
	 * <p>Takes the source file rather than a caller-computed relative path, so that the path this
	 * prints and the path {@link #createJob} recorded are relativized against the same root by the
	 * same code. Keying the suppression below on a path the caller happened to derive the same way
	 * would work only for as long as it kept doing so.</p>
	 *
	 * @param sourceFile the source markdown file that was skipped
	 */
	public void reportUpToDate(@Nonnull Path sourceFile) {
		Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		final Path relativePath = this.sourceDir.relativize(sourceFile.toAbsolutePath().normalize());
		if (this.translationsAhead.contains(relativePath)) {
			// createJob has already reported this one in full; "up to date" would misdescribe it,
			// because its commit field is precisely what is not
			return;
		}
		this.log.info("[SKIP] " + relativePath + " (up to date)");
	}

	/**
	 * Returns how many documents were skipped because their translation already carries the current
	 * source's structure while their {@code commit} field lags behind.
	 *
	 * @return the number of such documents seen so far
	 */
	public int getTranslationsAheadCount() {
		return this.translationsAhead.size();
	}
}
