package io.evitadb.comenius;

import io.evitadb.comenius.git.CommitInfo;
import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
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

		return Optional.of(new TranslateIncrementalJob(
			sourceFile, targetFile, locale, sourceContent, commitInfo.currentCommit(), instructions,
			translatableFrontMatterFields,
			commitInfo.originalSource(),
			existingTranslation,
			commitInfo.diff() != null ? commitInfo.diff() : "",
			commitInfo.translatedCommit(),
			commitInfo.commitCount()
		));
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
	 * @param relativePath the relative path for display
	 */
	public void reportUpToDate(@Nonnull Path relativePath) {
		this.log.info("[SKIP] " + relativePath + " (up to date)");
	}
}
