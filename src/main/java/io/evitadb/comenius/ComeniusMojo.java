package io.evitadb.comenius;

import dev.langchain4j.model.chat.ChatModel;
import io.evitadb.comenius.check.CheckResult;
import io.evitadb.comenius.check.ContentChecker;
import io.evitadb.comenius.check.GitError;
import io.evitadb.comenius.check.LinkCorrector;
import io.evitadb.comenius.check.LinkCorrectionResult;
import io.evitadb.comenius.check.LinkError;
import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.llm.ChatModelFactory;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationSummary;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Main Mojo for Comenius plugin providing actions:
 * - show-config: prints current configuration
 * - translate: runs traversal and translation workflow
 */
@Mojo(name = "run", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class ComeniusMojo extends AbstractMojo {

	/** Which action to perform: "show-config" or "translate". */
	@Parameter(property = "comenius.action", defaultValue = "show-config")
	private String action;

	/** LLM provider: "openai" or "anthropic". */
	@Parameter(property = "comenius.llmProvider", defaultValue = "openai")
	private String llmProvider = "openai";

	/** LLM URL (no default). */
	@Parameter(property = "comenius.llmUrl")
	private String llmUrl;

	/** LLM token (no default). */
	@Parameter(property = "comenius.llmToken")
	private String llmToken;

	/** LLM model name. */
	@Parameter(property = "comenius.llmModel", defaultValue = "gpt-4o")
	private String llmModel = "gpt-4o";

	/** Source directory path - relative to the project root (no default). */
	@Parameter(property = "comenius.sourceDir")
	private String sourceDir;

	/** Regex to match all files to translate - default (?i).*\.md (ignore case). */
	@Parameter(property = "comenius.fileRegex", defaultValue = "(?i).*\\.md")
	private String fileRegex = "(?i).*\\.md";

	/** Collection of target languages (no default). */
	@Parameter(property = "comenius.targets")
	private List<Target> targets;

	/** Maximum number of files to be translated (default Integer.MAX_VALUE). */
	@Parameter(property = "comenius.limit", defaultValue = "2147483647")
	private int limit = Integer.MAX_VALUE;

	/** When true, do not write any changes, only simulate. */
	@Parameter(property = "comenius.dryRun", defaultValue = "false")
	private boolean dryRun = false;

	/** Number of parallel translation threads (default 4). */
	@Parameter(property = "comenius.parallelism", defaultValue = "4")
	private int parallelism = 4;

	/** Regex patterns to exclude directories/files from processing. */
	@Parameter(property = "comenius.excludedFilePatterns")
	private List<String> excludedFilePatterns;

	/** YAML front matter field names to translate (e.g., "title", "perex"). */
	@Parameter(property = "comenius.translatableFrontMatterFields")
	private List<String> translatableFrontMatterFields;

	@Override
	public void execute() throws MojoExecutionException {
		if (this.action == null || this.action.isBlank()) {
			this.action = "show-config";
		}
		switch (this.action) {
			case "show-config":
				showConfig(getLog());
				break;
			case "translate":
				translate(getLog());
				break;
			case "check":
				check(getLog());
				break;
			default:
				throw new MojoExecutionException("Unknown action: " + this.action + ". Supported actions: show-config, translate, check");
		}
	}

	private void showConfig(@Nonnull final Log log) {
		log.info("Comenius Plugin Configuration:");
		log.info(" - llmProvider: " + this.llmProvider);
		log.info(" - llmUrl: " + (this.llmUrl == null || this.llmUrl.isBlank() ? "<not set>" : this.llmUrl));
		if (this.llmUrl == null || this.llmUrl.isBlank()) {
			log.warn("LLM url is not set");
		}
		log.info(" - llmToken: " + (this.llmToken == null || this.llmToken.isBlank() ? "<not set>" : mask(this.llmToken)));
		if (this.llmToken == null || this.llmToken.isBlank()) {
			log.warn("LLM token is not set");
		}
		log.info(" - llmModel: " + this.llmModel);
		log.info(" - sourceDir: " + (this.sourceDir == null || this.sourceDir.isBlank() ? "<not set>" : this.sourceDir));
		if (this.sourceDir == null || this.sourceDir.isBlank()) {
			log.warn("Source directory is not set");
		}
		log.info(" - fileRegex: " + this.fileRegex);
		if (this.targets == null || this.targets.isEmpty()) {
			log.info(" - targets: <none>");
			log.warn("No target languages configured");
		} else {
			log.info(" - targets:");
			for (final Target t : this.targets) {
				final String locale = t == null ? null : t.getLocale();
				final String tDir = t == null ? null : t.getTargetDir();
				log.info("   - locale: " + (locale == null || locale.isBlank() ? "<not set>" : locale) +
					", targetDir: " + (tDir == null || tDir.isBlank() ? "<not set>" : tDir));
				if (locale == null || locale.isBlank()) {
					log.warn("Target locale is not set");
				}
				if (tDir == null || tDir.isBlank()) {
					log.warn("Target directory is not set for locale " + (locale == null ? "<unknown>" : locale));
				}
			}
		}
		log.info(" - limit: " + this.limit);
		log.info(" - dryRun: " + this.dryRun);
		log.info(" - parallelism: " + this.parallelism);
		if (this.excludedFilePatterns == null || this.excludedFilePatterns.isEmpty()) {
			log.info(" - excludedFilePatterns: <none>");
		} else {
			log.info(" - excludedFilePatterns:");
			for (final String pattern : this.excludedFilePatterns) {
				log.info("   - " + pattern);
			}
		}
		if (this.translatableFrontMatterFields == null || this.translatableFrontMatterFields.isEmpty()) {
			log.info(" - translatableFrontMatterFields: <none>");
		} else {
			log.info(" - translatableFrontMatterFields:");
			for (final String field : this.translatableFrontMatterFields) {
				log.info("   - " + field);
			}
		}
	}

	@Nonnull
	private static String mask(@Nullable final String value) {
		if (value == null || value.length() <= 4) {
			return "****";
		}
		return "****" + value.substring(value.length() - 4);
	}

	/**
	 * Compiles the exclusion patterns list into Pattern objects.
	 *
	 * @param patterns the list of regex patterns to compile
	 * @return list of compiled patterns, or null if input is null/empty
	 */
	@Nullable
	private static List<Pattern> compileExclusionPatterns(@Nullable final List<String> patterns) {
		if (patterns == null || patterns.isEmpty()) {
			return null;
		}
		return patterns.stream()
			.filter(s -> s != null && !s.isBlank())
			.map(Pattern::compile)
			.toList();
	}

	private void translate(@Nonnull final Log log) {
		// Validate required parameters
		if (this.sourceDir == null || this.sourceDir.isBlank()) {
			log.error("Source directory must be specified for translate action");
			return;
		}
		if (!this.dryRun && (this.llmUrl == null || this.llmUrl.isBlank())) {
			log.error("LLM URL must be specified for non-dry-run translate action");
			return;
		}
		if (this.targets == null || this.targets.isEmpty()) {
			log.error("At least one target must be specified for translate action");
			return;
		}

		try {
			final Path root = Path.of(this.sourceDir).toAbsolutePath().normalize();
			if (!Files.exists(root) || !Files.isDirectory(root)) {
				log.error("Source directory does not exist or is not a directory: " + root);
				return;
			}
			final Pattern pattern = Pattern.compile(this.fileRegex);

			// Find git repository root
			final Path gitRoot = findGitRoot(root);
			final GitService gitService = new GitService(gitRoot);

			// Create translator and executor only for non-dry-run
			Translator translator = null;
			TranslationExecutor executor = null;
			if (!this.dryRun) {
				final ChatModel chatModel = ChatModelFactory.create(
					this.llmProvider, this.llmUrl, this.llmToken, this.llmModel
				);
				final PromptLoader promptLoader = new PromptLoader();
				translator = new Translator(chatModel, promptLoader);
				executor = new TranslationExecutor(this.parallelism, translator, new Writer(), log, root);
			}

			// Process each target locale
			for (final Target target : this.targets) {
				if (target == null || target.getLocale() == null || target.getTargetDir() == null) {
					log.warn("Skipping incomplete target configuration");
					continue;
				}

				final Locale locale = Locale.forLanguageTag(target.getLocale());
				final Path targetDir = Path.of(target.getTargetDir()).toAbsolutePath().normalize();

				log.info("=== Processing target: " + locale.getDisplayName() + " (" + locale.toLanguageTag() + ") -> " + targetDir + " ===");

				final TranslationOrchestrator orchestrator = new TranslationOrchestrator(gitService, root, log);

				// Phase 1: Collect jobs (respects limit)
				final List<TranslationJob> jobs = new ArrayList<>();
				final AtomicInteger newCount = new AtomicInteger(0);
				final AtomicInteger updateCount = new AtomicInteger(0);
				final AtomicInteger skippedCount = new AtomicInteger(0);
				final AtomicInteger errorCount = new AtomicInteger(0);
				final AtomicInteger processedCount = new AtomicInteger(0);

				final Visitor collectingVisitor = (file, content, instructions) -> {
					if (processedCount.get() >= this.limit) {
						return; // Respect limit
					}

					try {
						final Optional<TranslationJob> jobOpt = orchestrator.createJob(
							file, content, targetDir, locale, instructions, this.translatableFrontMatterFields
						);

						final Path relativePath = root.relativize(file.toAbsolutePath().normalize());

						if (jobOpt.isPresent()) {
							final TranslationJob job = jobOpt.get();
							jobs.add(job);
							processedCount.incrementAndGet();

							if (this.dryRun) {
								orchestrator.reportJob(job, relativePath);
							}

							if (job instanceof TranslateIncrementalJob) {
								updateCount.incrementAndGet();
							} else {
								newCount.incrementAndGet();
							}
						} else {
							// File was skipped (up-to-date or error)
							skippedCount.incrementAndGet();
							if (this.dryRun) {
								orchestrator.reportUpToDate(relativePath);
							}
						}
					} catch (IOException e) {
						errorCount.incrementAndGet();
						log.error("Error processing file " + file + ": " + e.getMessage());
					}
				};

				final List<Pattern> exclusionPatterns = compileExclusionPatterns(this.excludedFilePatterns);
				final Traverser traverser = new Traverser(root, pattern, exclusionPatterns, collectingVisitor);
				traverser.traverse();

				// Phase 2: Execute or Report summary
				if (this.dryRun) {
					log.info("--- Dry-run Summary ---");
					log.info("New files: " + newCount.get());
					log.info("Files to update: " + updateCount.get());
					log.info("Skipped (up-to-date): " + skippedCount.get());
					if (errorCount.get() > 0) {
						log.info("Errors: " + errorCount.get());
					}
				} else {
					// Execute translations
					log.info("Executing " + jobs.size() + " translations with parallelism " + this.parallelism + "...");
					final TranslationSummary summary = executor.executeAll(jobs);

					log.info("--- Translation Summary ---");
					log.info("Successful: " + summary.successCount());
					log.info("Failed: " + summary.failedCount());
					log.info("Skipped: " + skippedCount.get());
					log.info("Input tokens: " + summary.inputTokens());
					log.info("Output tokens: " + summary.outputTokens());

					// Link correction phase
					if (summary.successCount() > 0) {
						log.info("--- Link Correction Phase ---");
						final Map<Path, String> translatedFiles = executor.getSuccessfullyTranslatedFiles();
						correctLinksInTranslatedFiles(
							log, root, targetDir, pattern, exclusionPatterns,
							translatedFiles, gitService, gitRoot
						);
					}
				}
			}

			// Cleanup
			if (executor != null) {
				executor.shutdown();
			}

		} catch (final Exception ex) {
			log.error("Failed to execute translate action: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Executes the check action to validate files before translation.
	 * Checks that all matched files are committed to Git and have valid links.
	 *
	 * @param log the Maven log
	 * @throws MojoExecutionException if validation fails with errors
	 */
	private void check(@Nonnull final Log log) throws MojoExecutionException {
		// Validate required parameters
		if (this.sourceDir == null || this.sourceDir.isBlank()) {
			log.error("Source directory must be specified for check action");
			throw new MojoExecutionException("Source directory not specified");
		}

		try {
			final Path root = Path.of(this.sourceDir).toAbsolutePath().normalize();
			if (!Files.exists(root) || !Files.isDirectory(root)) {
				log.error("Source directory does not exist or is not a directory: " + root);
				throw new MojoExecutionException("Invalid source directory: " + root);
			}
			final Pattern pattern = Pattern.compile(this.fileRegex);

			// Find git repository root
			final Path gitRoot = findGitRoot(root);
			final GitService gitService = new GitService(gitRoot);

			log.info("=== Checking files in: " + root + " ===");
			log.info("Git repository root: " + gitRoot);

			final ContentChecker checker = new ContentChecker(gitService, root, gitRoot);
			final AtomicInteger fileCount = new AtomicInteger(0);

			final Visitor checkingVisitor = (file, content, instructions) -> {
				checker.checkFile(file, content);
				fileCount.incrementAndGet();
			};

			final List<Pattern> exclusionPatterns = compileExclusionPatterns(this.excludedFilePatterns);
			final Traverser traverser = new Traverser(root, pattern, exclusionPatterns, checkingVisitor);
			traverser.traverse();

			final CheckResult result = checker.getResult();

			// Report results
			log.info("Checked " + fileCount.get() + " files");

			if (!result.gitErrors().isEmpty()) {
				log.error("Git status errors: " + result.gitErrors().size());
				for (final GitError error : result.gitErrors()) {
					final Path relativePath = root.relativize(error.file());
					log.error("  " + error.type() + ": " + relativePath);
				}
			}

			if (!result.linkErrors().isEmpty()) {
				log.error("Link validation errors: " + result.linkErrors().size());
				for (final LinkError error : result.linkErrors()) {
					final Path relativePath = root.relativize(error.sourceFile());
					log.error("  " + relativePath + ": " + error.linkDestination() +
						" (" + error.type() + ")");
				}
			}

			if (!result.isSuccess()) {
				throw new MojoExecutionException(
					"Check failed with " + result.errorCount() + " error(s)"
				);
			}

			log.info("All checks passed!");

		} catch (final IOException ex) {
			throw new MojoExecutionException("Check action failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Finds the git repository root by walking up the directory tree.
	 *
	 * @param startDir the directory to start from
	 * @return the git repository root
	 * @throws IOException if not inside a git repository
	 */
	@Nonnull
	private static Path findGitRoot(@Nonnull Path startDir) throws IOException {
		Path current = startDir.toAbsolutePath().normalize();
		while (current != null) {
			if (Files.exists(current.resolve(".git"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IOException("Not inside a git repository: " + startDir);
	}

	/**
	 * Corrects links in translated files and validates the results.
	 *
	 * This method performs post-translation link correction:
	 * 1. Asset links are recalculated to point from target directory to source assets
	 * 2. Anchor links are translated by mapping heading indices between source and translated docs
	 * 3. Corrected files are written back to disk
	 * 4. ContentChecker validates all links are correct after correction
	 *
	 * @param log               Maven log for output
	 * @param sourceDir         source directory containing original files
	 * @param targetDir         target directory containing translated files
	 * @param filePattern       pattern to match translatable markdown files
	 * @param exclusionPatterns patterns for files to exclude
	 * @param translatedFiles   map of translated file paths to their content
	 * @param gitService        git service for validation
	 * @param gitRoot           git repository root
	 */
	private void correctLinksInTranslatedFiles(
		@Nonnull Log log,
		@Nonnull Path sourceDir,
		@Nonnull Path targetDir,
		@Nonnull Pattern filePattern,
		@Nullable List<Pattern> exclusionPatterns,
		@Nonnull Map<Path, String> translatedFiles,
		@Nonnull GitService gitService,
		@Nonnull Path gitRoot
	) {
		final LinkCorrector corrector = new LinkCorrector(
			sourceDir, targetDir, filePattern, exclusionPatterns,
			this.translatableFrontMatterFields, log
		);

		// Read actual content from disk (includes commit field added during translation)
		final Map<Path, String> filesWithContent = new HashMap<>();
		for (final Path path : translatedFiles.keySet()) {
			try {
				final String content = Files.readString(path, StandardCharsets.UTF_8);
				filesWithContent.put(path, content);
			} catch (IOException e) {
				log.error("Failed to read translated file for link correction: " + path);
			}
		}

		final List<LinkCorrectionResult> results = corrector.correctAll(filesWithContent);

		// Write corrected files and collect statistics
		final Writer writer = new Writer();
		int totalCorrections = 0;
		int filesWithCorrections = 0;
		int correctionErrors = 0;

		for (final LinkCorrectionResult result : results) {
			if (!result.isSuccess()) {
				correctionErrors++;
				for (final String error : result.errors()) {
					log.error("Link correction error in " + result.targetFile() + ": " + error);
				}
				continue;
			}

			if (result.totalCorrections() > 0) {
				try {
					final MarkdownDocument doc = new MarkdownDocument(result.correctedContent());
					writer.write(doc, result.targetFile());
					filesWithCorrections++;
					totalCorrections += result.totalCorrections();
					log.debug("Corrected " + result.totalCorrections() + " links in " +
						result.targetFile().getFileName());
				} catch (IOException e) {
					log.error("Failed to write corrected file " + result.targetFile() + ": " + e.getMessage());
					correctionErrors++;
				}
			}
		}

		log.info("Link corrections: " + totalCorrections + " in " + filesWithCorrections + " files");
		if (correctionErrors > 0) {
			log.error("Link correction errors: " + correctionErrors);
		}

		// Validation phase
		log.info("--- Link Validation Phase ---");
		final ContentChecker checker = new ContentChecker(gitService, targetDir, gitRoot);
		int validatedCount = 0;

		for (final LinkCorrectionResult result : results) {
			if (result.isSuccess()) {
				checker.checkFile(result.targetFile(), result.correctedContent());
				validatedCount++;
			}
		}

		final CheckResult checkResult = checker.getResult();

		if (!checkResult.linkErrors().isEmpty()) {
			log.error("Post-correction link validation errors: " + checkResult.linkErrors().size());
			for (final LinkError error : checkResult.linkErrors()) {
				final Path relativePath = targetDir.relativize(error.sourceFile());
				log.error("  " + relativePath + ": " + error.linkDestination() +
					" (" + error.type() + ")");
			}
		} else {
			log.info("Validated " + validatedCount + " files - all links OK");
		}
	}

	// Setters to aid testing without Maven parameter injection
	void setAction(@Nullable final String action) { this.action = action; }
	void setLlmProvider(@Nullable final String llmProvider) { this.llmProvider = llmProvider; }
	void setLlmUrl(@Nullable final String llmUrl) { this.llmUrl = llmUrl; }
	void setLlmToken(@Nullable final String llmToken) { this.llmToken = llmToken; }
	void setLlmModel(@Nullable final String llmModel) { this.llmModel = llmModel; }
	void setSourceDir(@Nullable final String sourceDir) { this.sourceDir = sourceDir; }
	void setFileRegex(@Nonnull final String fileRegex) { this.fileRegex = fileRegex; }
	void setTargets(@Nullable final List<Target> targets) { this.targets = targets; }
	void setLimit(final int limit) { this.limit = limit; }
	void setDryRun(final boolean dryRun) { this.dryRun = dryRun; }
	void setParallelism(final int parallelism) { this.parallelism = parallelism; }
	void setExcludedFilePatterns(@Nullable final List<String> patterns) { this.excludedFilePatterns = patterns; }
	void setTranslatableFrontMatterFields(@Nullable final List<String> fields) { this.translatableFrontMatterFields = fields; }

	/** Target language configuration. */
	public static class Target {
		@Parameter
		private String locale;
		@Parameter
		private String targetDir;

		public Target() {}

		public Target(@Nullable final String locale, @Nullable final String targetDir) {
			this.locale = locale;
			this.targetDir = targetDir;
		}

		@Nullable
		public String getLocale() { return this.locale; }

		@Nullable
		public String getTargetDir() { return this.targetDir; }

		public void setLocale(@Nullable final String locale) { this.locale = locale; }

		public void setTargetDir(@Nullable final String targetDir) { this.targetDir = targetDir; }
	}
}
