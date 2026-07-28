package io.evitadb.comenius;

import dev.langchain4j.model.chat.ChatModel;
import io.evitadb.comenius.check.AnchorChangeSet;
import io.evitadb.comenius.check.CheckResult;
import io.evitadb.comenius.check.ContentChecker;
import io.evitadb.comenius.check.ExternalLinkCorrector;
import io.evitadb.comenius.check.GitError;
import io.evitadb.comenius.check.LinkCorrector;
import io.evitadb.comenius.check.LinkCorrectionResult;
import io.evitadb.comenius.check.LinkError;
import io.evitadb.comenius.check.StructureRepairer;
import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.llm.ChatModelFactory;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationSummary;
import io.evitadb.comenius.structure.TagVocabulary;
import io.evitadb.comenius.structure.TagVocabularyDeriver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Main Mojo for Comenius plugin providing actions:
 * - show-config: prints current configuration
 * - translate: runs traversal and translation workflow
 */
@Mojo(name = "run", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class ComeniusMojo extends AbstractMojo {

	/**
	 * Identifies every translatable markdown document, independent of {@link #fileRegex}.
	 * {@code fileRegex} scopes which files *this run* translates or fixes; link correction
	 * needs to recognise the full corpus regardless of that scope, or else a narrow run (e.g.
	 * translating a single file) mis-files every other real document as a non-translatable
	 * asset, corrupting cross-document links and skipping external anchor staleness checks.
	 */
	private static final Pattern TRANSLATABLE_MARKDOWN_PATTERN = Pattern.compile("(?i).*\\.md");

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

	/** Custom key-value pairs to add to the front matter of all translated files. */
	@Parameter(property = "comenius.customFrontMatter")
	private Map<String, String> customFrontMatter;

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
			case "fix-links":
				fixLinks(getLog());
				break;
			case "fix-structure":
				fixStructure(getLog());
				break;
			default:
				throw new MojoExecutionException("Unknown action: " + this.action + ". Supported actions: show-config, translate, check, fix-links, fix-structure");
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
		if (this.customFrontMatter == null || this.customFrontMatter.isEmpty()) {
			log.info(" - customFrontMatter: <none>");
		} else {
			log.info(" - customFrontMatter:");
			for (final Map.Entry<String, String> entry : this.customFrontMatter.entrySet()) {
				log.info("   - " + entry.getKey() + ": " + entry.getValue());
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
		final List<Pattern> compiled = new ArrayList<>(patterns.size());
		for (final String s : patterns) {
			if (s != null && !s.isBlank()) {
				compiled.add(Pattern.compile(s));
			}
		}
		return compiled;
	}

	/**
	 * Derives a tag vocabulary from every markdown document under the source root, so that
	 * {@link Translator} can repair tag-case drift, autofix untranslated leaf content, and run a
	 * structural comparison beyond heading counts.
	 *
	 * Derivation is corpus-wide by design (see {@link TagVocabularyDeriver}): a tag name means the
	 * same thing everywhere, or the whole point of auto-derivation collapses. A failure here
	 * (an unbalanced tag introduced somewhere in the corpus) degrades to the prior behaviour -
	 * {@code null}, meaning the new checks are skipped - rather than blocking the entire translate
	 * action over files that may not even be affected.
	 *
	 * @param root the source directory root
	 * @param log  the Maven log
	 * @return the derived vocabulary, or {@code null} if derivation failed
	 */
	@Nullable
	private static TagVocabulary deriveVocabulary(@Nonnull final Path root, @Nonnull final Log log) {
		try {
			final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(path -> path.getFileName().toString().endsWith(".md"))
					.filter(path -> {
						final String text = path.toString();
						return !text.contains("/examples/") && !text.contains("/example/")
							&& !text.contains("/assets/");
					})
					.sorted()
					.forEach(path -> {
						try {
							deriver.add(
								root.relativize(path).toString(),
								new MarkdownDocument(
									new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
								).getBodyContent()
							);
						} catch (final IOException exception) {
							throw new UncheckedIOException(exception);
						}
					});
			}
			return deriver.derive(
				Set.of("Table", "Thead", "Tbody", "Tr", "CodeTabs", "CodeTabsBlock", "SourceCodeTabs"),
				Set.of("SourceClass", "MDInclude", "dt", "dd"), false
			);
		} catch (final RuntimeException | IOException exception) {
			log.warn(
				"Tag vocabulary derivation failed, tag-case repair / untranslated-content autofix /" +
					" structural comparison are disabled for this run: " + exception.getMessage()
			);
			return null;
		}
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
			// Use a shared ForkJoinPool for all parallel work (translations and link correction)
			Translator translator = null;
			TranslationExecutor executor = null;
			ForkJoinPool translationPool = null;
			if (!this.dryRun) {
				translationPool = new ForkJoinPool(this.parallelism);
				final ChatModel chatModel = ChatModelFactory.create(
					this.llmProvider, this.llmUrl, this.llmToken, this.llmModel
				);
				// LangChain4j handles retry logic internally
				final LlmClient llmClient = new LlmClient(chatModel);
				final PromptLoader promptLoader = new PromptLoader();
				final TagVocabulary vocabulary = deriveVocabulary(root, log);
				translator = new Translator(llmClient, promptLoader, translationPool, log, vocabulary);
				executor = new TranslationExecutor(translationPool, translator, new Writer(), log, root);
				executor.setCustomFrontMatter(this.customFrontMatter);
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

					// Link correction phase (uses same ForkJoinPool for parallel processing)
					if (summary.successCount() > 0) {
						log.info("--- Link Correction Phase ---");
						final Set<Path> translatedFiles = executor.getSuccessfullyTranslatedFiles();
						correctLinksInTranslatedFiles(
							log, root, targetDir, TRANSLATABLE_MARKDOWN_PATTERN, exclusionPatterns,
							translatedFiles, gitService, gitRoot, executor.getExecutor()
						);

						// External anchor correction phase
						final Map<Path, AnchorChangeSet> anchorChanges = executor.getAnchorChanges();
						if (!anchorChanges.isEmpty()) {
							correctExternalAnchors(
								log, targetDir, TRANSLATABLE_MARKDOWN_PATTERN, exclusionPatterns,
								anchorChanges, translatedFiles, executor.getExecutor()
							);
						}
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
			reportCheckResult(log, root, result);

			// Translated trees are documents in their own right - their links can rot exactly
			// like the source ones, and until they are checked too a green run says nothing
			// about them. Each target is checked against itself, so a Czech document linking to
			// a Czech anchor is validated in Czech.
			int targetErrors = 0;
			if (this.targets != null) {
				for (final Target target : this.targets) {
					if (target == null || target.getLocale() == null || target.getTargetDir() == null) {
						log.warn("Skipping incomplete target configuration");
						continue;
					}
					final Locale locale = Locale.forLanguageTag(target.getLocale());
					final Path targetDir = Path.of(target.getTargetDir()).toAbsolutePath().normalize();
					if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
						log.warn("Target directory does not exist, skipping: " + targetDir);
						continue;
					}

					final ContentChecker targetChecker = new ContentChecker(gitService, targetDir, gitRoot);
					final AtomicInteger targetFileCount = new AtomicInteger(0);
					final Visitor targetVisitor = (file, content, instructions) -> {
						targetChecker.checkFile(file, content);
						targetFileCount.incrementAndGet();
					};
					new Traverser(targetDir, pattern, exclusionPatterns, targetVisitor).traverse();

					final CheckResult targetResult = targetChecker.getResult();
					log.info("=== Checked " + targetFileCount.get() + " files for: " +
						locale.getDisplayName() + " (" + locale.toLanguageTag() + ") in " + targetDir + " ===");
					reportCheckResult(log, targetDir, targetResult);
					targetErrors += targetResult.errorCount();
				}
			}

			final int totalErrors = result.errorCount() + targetErrors;
			if (totalErrors > 0) {
				throw new MojoExecutionException(
					"Check failed with " + totalErrors + " error(s)"
				);
			}

			log.info("All checks passed!");

		} catch (final IOException ex) {
			throw new MojoExecutionException("Check action failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Executes the fix-links action to correct links in all translated files.
	 * This action runs only the link correction phase without performing translations.
	 *
	 * @param log the Maven log
	 * @throws MojoExecutionException if the action fails
	 */
	private void fixLinks(@Nonnull final Log log) throws MojoExecutionException {
		// Validate required parameters
		if (this.sourceDir == null || this.sourceDir.isBlank()) {
			log.error("Source directory must be specified for fix-links action");
			throw new MojoExecutionException("Source directory not specified");
		}
		if (this.targets == null || this.targets.isEmpty()) {
			log.error("At least one target must be specified for fix-links action");
			throw new MojoExecutionException("No targets specified");
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

			// Create shared ForkJoinPool for parallel work
			final ForkJoinPool pool = new ForkJoinPool(this.parallelism);

			try {
				// Process each target
				for (final Target target : this.targets) {
					if (target == null || target.getLocale() == null || target.getTargetDir() == null) {
						log.warn("Skipping incomplete target configuration");
						continue;
					}

					final Locale locale = Locale.forLanguageTag(target.getLocale());
					final Path targetDir = Path.of(target.getTargetDir()).toAbsolutePath().normalize();

					if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
						log.warn("Target directory does not exist, skipping: " + targetDir);
						continue;
					}

					log.info("=== Fixing links for: " + locale.getDisplayName() + " (" + locale.toLanguageTag() + ") in " + targetDir + " ===");

					// Collect all files in target directory
					final List<Pattern> exclusionPatterns = compileExclusionPatterns(this.excludedFilePatterns);
					final Map<Path, String> filesToProcess = new HashMap<>();
					final Visitor collectingVisitor = (file, content, instructions) -> {
						filesToProcess.put(file, content);
					};

					final Traverser traverser = new Traverser(targetDir, pattern, exclusionPatterns, collectingVisitor);
					traverser.traverse();

					log.info("Found " + filesToProcess.size() + " files to process");

					if (filesToProcess.isEmpty()) {
						continue;
					}

					// Run link correction
					final LinkCorrector corrector = new LinkCorrector(
						root, targetDir, TRANSLATABLE_MARKDOWN_PATTERN, exclusionPatterns,
						this.translatableFrontMatterFields, log
					);

					final List<LinkCorrectionResult> results = corrector.correctAllParallel(filesToProcess, pool);

					// Write corrected files
					writeCorrectedFiles(log, results, this.dryRun);

					// Validation phase
					validateCorrectedLinks(log, targetDir, results, gitService, gitRoot);
				}
			} finally {
				pool.shutdown();
			}

		} catch (final IOException ex) {
			throw new MojoExecutionException("Fix-links action failed: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Logs the git and link errors of a single checked tree.
	 *
	 * @param log    the Maven log
	 * @param root   the tree the errors are reported relative to
	 * @param result the check outcome for that tree
	 */
	private static void reportCheckResult(
		@Nonnull Log log,
		@Nonnull Path root,
		@Nonnull CheckResult result
	) {
		if (!result.gitErrors().isEmpty()) {
			log.error("Git status errors: " + result.gitErrors().size());
			for (final GitError error : result.gitErrors()) {
				log.error("  " + error.type() + ": " + root.relativize(error.file()));
			}
		}
		if (!result.linkErrors().isEmpty()) {
			log.error("Link validation errors: " + result.linkErrors().size());
			for (final LinkError error : result.linkErrors()) {
				log.error("  " + root.relativize(error.sourceFile()) + ": " + error.linkDestination() +
					" (" + error.type() + ")");
			}
		}
	}

	/**
	 * Executes the fix-structure action: restores headings in translated documents that a
	 * markdown parser no longer recognises because the blank line in front of them is missing.
	 *
	 * Runs no LLM calls and changes no wording - the only edit ever made is the insertion of a
	 * blank line, and only when re-parsing proves it brings a heading back. With
	 * {@code -Dcomenius.dryRun=true} nothing is written at all, which makes this usable as a
	 * read-only report on the structural health of the translated corpus.
	 *
	 * This must run before {@code fix-links}: anchors are mapped by heading position, so while
	 * a heading is still swallowed every later anchor in that document is off by one and link
	 * correction would confidently write the wrong target.
	 *
	 * @param log the Maven log
	 * @throws MojoExecutionException if the action fails
	 */
	private void fixStructure(@Nonnull final Log log) throws MojoExecutionException {
		if (this.targets == null || this.targets.isEmpty()) {
			log.error("At least one target must be specified for fix-structure action");
			throw new MojoExecutionException("No targets specified");
		}

		final Pattern pattern = Pattern.compile(this.fileRegex);
		final List<Pattern> exclusionPatterns = compileExclusionPatterns(this.excludedFilePatterns);
		final Writer writer = new Writer();

		int totalRepairs = 0;
		int repairedFiles = 0;
		int unrepairedFiles = 0;
		int writeErrors = 0;

		try {
			for (final Target target : this.targets) {
				if (target == null || target.getLocale() == null || target.getTargetDir() == null) {
					log.warn("Skipping incomplete target configuration");
					continue;
				}

				final Locale locale = Locale.forLanguageTag(target.getLocale());
				final Path targetDir = Path.of(target.getTargetDir()).toAbsolutePath().normalize();
				if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
					log.warn("Target directory does not exist, skipping: " + targetDir);
					continue;
				}

				log.info("=== Repairing structure for: " + locale.getDisplayName() +
					" (" + locale.toLanguageTag() + ") in " + targetDir + " ===");

				final Map<Path, String> filesToProcess = new HashMap<>();
				final Visitor collectingVisitor = (file, content, instructions) -> filesToProcess.put(file, content);
				new Traverser(targetDir, pattern, exclusionPatterns, collectingVisitor).traverse();
				log.info("Found " + filesToProcess.size() + " files to inspect");

				for (final Map.Entry<Path, String> entry : filesToProcess.entrySet()) {
					final Path file = entry.getKey();
					final MarkdownDocument document = new MarkdownDocument(entry.getValue());
					final StructureRepairer.Result result = StructureRepairer.repair(document.getBodyContent());

					if (!result.unrepaired().isEmpty()) {
						unrepairedFiles++;
						log.warn(targetDir.relativize(file) + ": " + result.unrepaired().size() +
							" heading(s) not recognised by a markdown parser that a blank line does not" +
							" fix, needs a human look: " + result.unrepaired());
					}
					if (!result.isModified()) {
						continue;
					}

					repairedFiles++;
					totalRepairs += result.repairs().size();
					for (final StructureRepairer.Repair repair : result.repairs()) {
						log.info((this.dryRun ? "[DRY-RUN] would restore " : "restored ") +
							targetDir.relativize(file) + ":" + repair.lineNumber() +
							" \"" + repair.headingText() + "\"");
					}

					if (!this.dryRun) {
						try {
							// rebuild the file from its own front matter plus the repaired body,
							// the same way corrected links are written back
							writer.write(
								new MarkdownDocument(document.serializeFrontMatter() + result.content()),
								file
							);
						} catch (IOException e) {
							log.error("Failed to write repaired file " + file + ": " + e.getMessage());
							writeErrors++;
						}
					}
				}
			}
		} catch (final IOException ex) {
			throw new MojoExecutionException("Fix-structure action failed: " + ex.getMessage(), ex);
		}

		log.info("Structure repairs: " + totalRepairs + " heading(s) in " + repairedFiles + " file(s)");
		if (unrepairedFiles > 0) {
			log.warn("Files with headings a blank line cannot restore: " + unrepairedFiles);
		}
		if (this.dryRun) {
			log.info("[DRY-RUN] nothing was written; re-run without -Dcomenius.dryRun=true to apply");
		}
		if (writeErrors > 0) {
			throw new MojoExecutionException("Fix-structure failed to write " + writeErrors + " file(s)");
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
	 * @param translatedFiles   set of translated file paths
	 * @param gitService        git service for validation
	 * @param gitRoot           git repository root
	 * @param executor          executor for parallel link correction
	 */
	private void correctLinksInTranslatedFiles(
		@Nonnull Log log,
		@Nonnull Path sourceDir,
		@Nonnull Path targetDir,
		@Nonnull Pattern filePattern,
		@Nullable List<Pattern> exclusionPatterns,
		@Nonnull Set<Path> translatedFiles,
		@Nonnull GitService gitService,
		@Nonnull Path gitRoot,
		@Nonnull Executor executor
	) {
		final LinkCorrector corrector = new LinkCorrector(
			sourceDir, targetDir, filePattern, exclusionPatterns,
			this.translatableFrontMatterFields, log
		);

		// Read actual content from disk (includes commit field added during translation)
		final Map<Path, String> filesWithContent = new HashMap<>();
		for (final Path path : translatedFiles) {
			try {
				final String content = Files.readString(path, StandardCharsets.UTF_8);
				filesWithContent.put(path, content);
			} catch (IOException e) {
				log.error("Failed to read translated file for link correction: " + path);
			}
		}

		final List<LinkCorrectionResult> results = corrector.correctAllParallel(filesWithContent, executor);

		// Write corrected files
		writeCorrectedFiles(log, results, this.dryRun);

		// Validation phase
		validateCorrectedLinks(log, targetDir, results, gitService, gitRoot);
	}

	/**
	 * Corrects anchor references in external translated files that point to
	 * re-translated documents whose headings have changed.
	 *
	 * Scans all markdown files in the target directory (excluding just-translated ones)
	 * and fixes stale anchor references using the anchor change data collected
	 * during translation.
	 *
	 * @param log               Maven log
	 * @param targetDir         target directory containing translated files
	 * @param filePattern       regex pattern to match markdown files
	 * @param exclusionPatterns patterns for files to exclude
	 * @param anchorChanges     map of target file path to AnchorChangeSet
	 * @param justTranslated    set of files that were just translated (excluded from scanning)
	 * @param executor          executor for parallel processing
	 */
	private void correctExternalAnchors(
		@Nonnull Log log,
		@Nonnull Path targetDir,
		@Nonnull Pattern filePattern,
		@Nullable List<Pattern> exclusionPatterns,
		@Nonnull Map<Path, AnchorChangeSet> anchorChanges,
		@Nonnull Set<Path> justTranslated,
		@Nonnull Executor executor
	) {
		log.info("--- External Anchor Correction Phase ---");
		log.info("Files with changed anchors: " + anchorChanges.size());

		// Collect all markdown files in target directory except just-translated ones
		final Map<Path, String> externalFiles = new HashMap<>();
		final Visitor collectingVisitor = (file, content, instructions) -> {
			final Path normalizedFile = file.toAbsolutePath().normalize();
			if (!justTranslated.contains(normalizedFile)) {
				externalFiles.put(normalizedFile, content);
			}
		};

		try {
			final Traverser traverser = new Traverser(
				targetDir, filePattern, exclusionPatterns, collectingVisitor
			);
			traverser.traverse();
		} catch (IOException e) {
			log.error("Failed to traverse target directory for anchor correction: " + e.getMessage());
			return;
		}

		if (externalFiles.isEmpty()) {
			log.info("No external files to check for anchor corrections");
			return;
		}

		log.info("Scanning " + externalFiles.size() + " external files for stale anchors");

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			targetDir, this.translatableFrontMatterFields, log
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			anchorChanges, externalFiles, executor
		);

		// Write corrected files using existing infrastructure
		writeCorrectedFiles(log, results, this.dryRun);
	}

	/**
	 * Writes corrected files to disk and logs statistics.
	 *
	 * @param log     Maven log for output
	 * @param results the link correction results to write
	 * @param dryRun  when true, report what would change without touching any file
	 * @return array of [totalCorrections, filesWithCorrections, correctionErrors]
	 */
	@Nonnull
	private static int[] writeCorrectedFiles(
		@Nonnull Log log,
		@Nonnull List<LinkCorrectionResult> results,
		boolean dryRun
	) {
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
				if (dryRun) {
					filesWithCorrections++;
					totalCorrections += result.totalCorrections();
					log.info("[DRY-RUN] would correct " + result.totalCorrections() + " link(s) in "
						+ result.targetFile());
					continue;
				}
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

		if (dryRun) {
			log.info("[DRY-RUN] nothing was written; re-run without -Dcomenius.dryRun=true to apply");
		}

		log.info("Link corrections: " + totalCorrections + " in " + filesWithCorrections + " files");
		if (correctionErrors > 0) {
			log.error("Link correction errors: " + correctionErrors);
		}

		return new int[]{totalCorrections, filesWithCorrections, correctionErrors};
	}

	/**
	 * Validates corrected links and logs any remaining errors.
	 *
	 * @param log        Maven log for output
	 * @param targetDir  the target directory for relative path display
	 * @param results    the link correction results to validate
	 * @param gitService git service for validation
	 * @param gitRoot    git repository root
	 */
	private static void validateCorrectedLinks(
		@Nonnull Log log,
		@Nonnull Path targetDir,
		@Nonnull List<LinkCorrectionResult> results,
		@Nonnull GitService gitService,
		@Nonnull Path gitRoot
	) {
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
	void setCustomFrontMatter(@Nullable final Map<String, String> customFrontMatter) { this.customFrontMatter = customFrontMatter; }

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
