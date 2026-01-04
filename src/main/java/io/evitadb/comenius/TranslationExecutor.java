package io.evitadb.comenius;

import dev.langchain4j.exception.NonRetriableException;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.model.FrontMatterTranslationHelper;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import io.evitadb.comenius.model.TranslationSummary;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes translation jobs in parallel using a ForkJoinPool.
 * Handles writing successful translations and collecting summary statistics.
 * The ForkJoinPool enables work-stealing for optimal parallelism.
 *
 * On permanent LLM failures (authentication, quota exceeded), immediately shuts down
 * the pool to prevent wasting resources on requests that cannot succeed.
 */
public final class TranslationExecutor {

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 60;

	@Nonnull
	private final ForkJoinPool executor;
	@Nonnull
	private final Translator translator;
	@Nonnull
	private final Writer writer;
	@Nonnull
	private final Log log;
	@Nonnull
	private final Path sourceDir;
	/**
	 * Tracks successfully translated files for post-processing (e.g., link correction).
	 */
	@Nonnull
	private final Set<Path> successfullyTranslatedFiles = ConcurrentHashMap.newKeySet();
	/**
	 * Total number of jobs in the current execution batch.
	 */
	private int totalJobs;
	/**
	 * Counter for completed jobs (successful or failed) for progress reporting.
	 */
	@Nonnull
	private final AtomicInteger completedJobs = new AtomicInteger(0);
	/**
	 * Flag indicating shutdown has been requested due to permanent failure.
	 */
	@Nonnull
	private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
	/**
	 * Stores the cause of permanent failure for reporting.
	 */
	@Nullable
	private volatile NonRetriableException permanentFailureCause = null;

	/**
	 * Creates a translation executor using an existing ForkJoinPool.
	 * This constructor allows sharing a pool between Translator and Executor.
	 *
	 * @param executor    the ForkJoinPool to use for parallel execution
	 * @param translator  the translator to use for LLM calls
	 * @param writer      the writer for output files
	 * @param log         Maven log for output
	 * @param sourceDir   the source root directory for relative path calculation
	 */
	public TranslationExecutor(
		@Nonnull ForkJoinPool executor,
		@Nonnull Translator translator,
		@Nonnull Writer writer,
		@Nonnull Log log,
		@Nonnull Path sourceDir
	) {
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
		this.translator = Objects.requireNonNull(translator, "translator must not be null");
		this.writer = Objects.requireNonNull(writer, "writer must not be null");
		this.log = Objects.requireNonNull(log, "log must not be null");
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null").toAbsolutePath().normalize();
	}

	/**
	 * Creates a translation executor with the specified parallelism.
	 * Creates a new ForkJoinPool with the given parallelism level.
	 *
	 * @param parallelism number of concurrent translations to run
	 * @param translator  the translator to use for LLM calls
	 * @param writer      the writer for output files
	 * @param log         Maven log for output
	 * @param sourceDir   the source root directory for relative path calculation
	 */
	public TranslationExecutor(
		int parallelism,
		@Nonnull Translator translator,
		@Nonnull Writer writer,
		@Nonnull Log log,
		@Nonnull Path sourceDir
	) {
		this(
			createPool(parallelism),
			translator,
			writer,
			log,
			sourceDir
		);
	}

	/**
	 * Creates a ForkJoinPool with the specified parallelism.
	 *
	 * @param parallelism number of threads
	 * @return new ForkJoinPool
	 */
	@Nonnull
	private static ForkJoinPool createPool(int parallelism) {
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be at least 1");
		}
		return new ForkJoinPool(parallelism);
	}

	/**
	 * Executes all translation jobs and returns a summary of results.
	 * Jobs are executed in parallel up to the configured parallelism limit.
	 * Each result is processed immediately when ready, allowing request/response
	 * strings to be garbage collected after disk write.
	 *
	 * If a permanent LLM failure occurs (authentication, quota exceeded), the pool
	 * is immediately shut down and remaining jobs are cancelled.
	 *
	 * @param jobs the list of translation jobs to execute
	 * @return summary with success/failure counts and token usage
	 */
	@Nonnull
	public TranslationSummary executeAll(@Nonnull List<TranslationJob> jobs) {
		Objects.requireNonNull(jobs, "jobs must not be null");

		if (jobs.isEmpty()) {
			return TranslationSummary.empty();
		}

		this.totalJobs = jobs.size();
		this.completedJobs.set(0);
		this.shutdownRequested.set(false);
		this.permanentFailureCause = null;

		// Submit all jobs with result processing chained - allows early GC of content
		final List<CompletableFuture<TranslationSummary>> futures = new ArrayList<>(jobs.size());
		for (final TranslationJob job : jobs) {
			final Path relativePath = this.sourceDir.relativize(job.getSourceFile().toAbsolutePath().normalize());
			this.log.info("Translating: " + relativePath + " -> " +
				job.getLocale().getDisplayName() + " (" + job.getLocale().toLanguageTag() + ")");

			final CompletableFuture<TranslationSummary> future = this.translator.translate(job)
				.handle((result, throwable) -> handleTranslationResult(job, result, throwable))
				.toCompletableFuture();
			futures.add(future);
		}

		// Wait for all to complete (some may complete early due to shutdown)
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} catch (Exception e) {
			// If pool was shut down, some futures may throw CancellationException
			this.log.debug("Some futures did not complete: " + e.getMessage());
		}

		// Aggregate summaries
		TranslationSummary summary = TranslationSummary.empty();
		for (final CompletableFuture<TranslationSummary> future : futures) {
			try {
				if (future.isDone() && !future.isCancelled()) {
					summary = summary.add(future.get());
				} else {
					// Job was cancelled due to shutdown
					summary = summary.withFailure();
				}
			} catch (Exception e) {
				this.log.error("Failed to get translation summary: " + e.getMessage());
				summary = summary.withFailure();
			}
		}

		// Report shutdown status if it occurred
		if (this.shutdownRequested.get() && this.permanentFailureCause != null) {
			final int remainingJobs = this.totalJobs - this.completedJobs.get();
			if (remainingJobs > 0) {
				this.log.error(remainingJobs + " remaining translations cancelled due to permanent failure");
			}
		}

		return summary;
	}

	/**
	 * Handles the result of a translation, including exceptions.
	 * Detects permanent failures and triggers shutdown if needed.
	 *
	 * @param job       the translation job
	 * @param result    the translation result, or null if exception occurred
	 * @param throwable the exception, or null if successful
	 * @return summary for this translation
	 */
	@Nonnull
	private TranslationSummary handleTranslationResult(
		@Nonnull TranslationJob job,
		@Nullable TranslationResult result,
		@Nullable Throwable throwable
	) {
		if (throwable != null) {
			// Unwrap CompletionException
			final Throwable cause = throwable instanceof CompletionException
				? throwable.getCause() : throwable;

			// Check for permanent failure
			final NonRetriableException permanent = findPermanentException(cause);
			if (permanent != null) {
				handlePermanentFailure(permanent, job);
				return TranslationSummary.empty().withFailure();
			}

			// Other exception - just count as failed
			final Path relativePath = this.sourceDir.relativize(job.getSourceFile());
			final int completed = this.completedJobs.incrementAndGet();
			final String progressBar = formatProgressBar(completed, this.totalJobs);
			this.log.error(progressBar + " [" + job.getType() + "] Translation failed for " +
				relativePath + ": " + cause.getMessage());
			return TranslationSummary.empty().withFailure();
		}

		// No exception - process result normally
		return processResult(result);
	}

	/**
	 * Searches the exception cause chain for a NonRetriableException.
	 *
	 * @param throwable the exception to search
	 * @return the NonRetriableException if found, or null
	 */
	@Nullable
	private NonRetriableException findPermanentException(@Nullable Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof NonRetriableException permanent) {
				return permanent;
			}
			current = current.getCause();
		}
		return null;
	}

	/**
	 * Handles a permanent LLM failure by shutting down the pool.
	 * Only the first permanent failure triggers shutdown; subsequent ones are ignored.
	 *
	 * @param exception the permanent failure exception
	 * @param job       the job that caused the failure
	 */
	private void handlePermanentFailure(
		@Nonnull NonRetriableException exception,
		@Nonnull TranslationJob job
	) {
		// Only trigger shutdown once
		if (this.shutdownRequested.compareAndSet(false, true)) {
			this.permanentFailureCause = exception;

			this.log.error("");
			this.log.error("=".repeat(70));
			this.log.error("PERMANENT LLM ERROR - Shutting down all translations");
			this.log.error("Error: " + exception.getClass().getSimpleName() + " - " + exception.getMessage());
			this.log.error("=".repeat(70));
			this.log.error("");

			// Signal the LLM client to reject new requests
			final LlmClient llmClient = this.translator.getLlmClient();
			llmClient.signalShutdown(exception);

			// Shutdown the pool immediately - cancel pending tasks
			this.executor.shutdownNow();
		}

		// Count this job as completed/failed
		this.completedJobs.incrementAndGet();
	}

	/**
	 * Processes a single translation result: writes successful translations and returns summary.
	 * This method is called immediately when a translation completes, allowing the translation
	 * content to be garbage collected after the file is written.
	 *
	 * @param result the translation result to process
	 * @return summary for this single result
	 */
	@Nonnull
	private TranslationSummary processResult(@Nonnull TranslationResult result) {
		final TranslationJob job = result.job();
		final Path relativePath = this.sourceDir.relativize(job.getSourceFile());
		final int completed = this.completedJobs.incrementAndGet();
		final String progressBar = formatProgressBar(completed, this.totalJobs);
		final String localeInfo = job.getLocale().getDisplayName() + " (" + job.getLocale().toLanguageTag() + ")";
		final String elapsedInfo = formatElapsedTime(result.elapsedMillis());

		if (result.success()) {
			try {
				writeTranslation(result);
				this.successfullyTranslatedFiles.add(job.getTargetFile());
				this.log.info(progressBar + " [" + job.getType() + "] " + relativePath + " -> " + localeInfo + " (" + elapsedInfo + ")");
				return new TranslationSummary(1, 0, 0, result.inputTokens(), result.outputTokens());
			} catch (IOException e) {
				this.log.error(progressBar + " [" + job.getType() + "] Failed to write " + relativePath + ": " + e.getMessage());
				return new TranslationSummary(0, 1, 0, 0, 0);
			}
		} else {
			this.log.error(progressBar + " [" + job.getType() + "] Translation failed for " + relativePath + " (" + elapsedInfo + "): " + result.errorMessage());
			return new TranslationSummary(0, 1, 0, 0, 0);
		}
	}

	/**
	 * Writes a successful translation to the target file, copying all front matter
	 * fields from the source and merging translated fields.
	 *
	 * The process:
	 * 1. Parse source document to get all properties (preserving order via LinkedHashMap)
	 * 2. Create new document from LLM response body (no front matter)
	 * 3. Copy ALL properties from source (non-translatable fields are preserved)
	 * 4. Overwrite with translated fields (translatable fields get new values)
	 * 5. Add commit field at end
	 *
	 * @param result the successful translation result
	 * @throws IOException if writing fails
	 */
	private void writeTranslation(@Nonnull TranslationResult result) throws IOException {
		final TranslationJob job = result.job();
		final String translatedContent = result.translatedContent();

		// Parse source document to get all properties (order preserved via LinkedHashMap)
		final MarkdownDocument sourceDoc = new MarkdownDocument(job.getSourceContent());

		// Get the expected translatable fields from the job
		final Map<String, String> expectedFields = job.getExtractedTranslatableFields();

		// Parse translated field values from response and extract body
		final Map<String, String> translatedFields = FrontMatterTranslationHelper
			.parseTranslatedFields(translatedContent, expectedFields);
		final String bodyContent = FrontMatterTranslationHelper
			.extractBodyFromResponse(translatedContent, expectedFields);

		// Create document from translated body content (empty properties initially)
		final MarkdownDocument doc = new MarkdownDocument(bodyContent);

		// Copy ALL properties from source (preserves order and non-translatable fields)
		doc.mergeFrontMatterProperties(sourceDoc.getProperties());

		// Overwrite with translated fields (updates value, keeps position in LinkedHashMap)
		for (final Map.Entry<String, String> entry : translatedFields.entrySet()) {
			doc.setProperty(entry.getKey(), entry.getValue());
		}

		// Add commit field (added at end if not in source)
		doc.setProperty("commit", job.getCurrentCommit());

		this.writer.write(doc, job.getTargetFile());
	}

	/**
	 * Formats a progress bar string showing completion status.
	 *
	 * @param completed number of completed jobs
	 * @param total     total number of jobs
	 * @return formatted progress bar string, e.g., "[=====               ]  25%"
	 */
	@Nonnull
	private String formatProgressBar(int completed, int total) {
		final int percentage = (completed * 100) / total;
		final int barWidth = 20;
		final int filled = (completed * barWidth) / total;
		final StringBuilder bar = new StringBuilder("[");
		for (int i = 0; i < barWidth; i++) {
			bar.append(i < filled ? "=" : " ");
		}
		bar.append("] ").append(String.format("%3d", percentage)).append("%");
		return bar.toString();
	}

	/**
	 * Formats elapsed time in a human-readable format.
	 * Shows seconds with one decimal place, or minutes and seconds for longer durations.
	 *
	 * @param millis elapsed time in milliseconds
	 * @return formatted elapsed time string, e.g., "2.5s" or "1m 30s"
	 */
	@Nonnull
	private String formatElapsedTime(long millis) {
		if (millis < 60000) {
			return String.format("%.1fs", millis / 1000.0);
		} else {
			final long minutes = millis / 60000;
			final long seconds = (millis % 60000) / 1000;
			return minutes + "m " + seconds + "s";
		}
	}

	/**
	 * Shuts down the executor gracefully, waiting for pending tasks to complete.
	 */
	public void shutdown() {
		this.executor.shutdown();
		try {
			if (!this.executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				this.log.warn("Executor did not terminate in time, forcing shutdown");
				this.executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.executor.shutdownNow();
		}
	}

	/**
	 * Returns a copy of the successfully translated file paths.
	 * This is useful for post-processing steps like link correction.
	 *
	 * @return immutable copy of successfully translated file paths
	 */
	@Nonnull
	public Set<Path> getSuccessfullyTranslatedFiles() {
		return Set.copyOf(this.successfullyTranslatedFiles);
	}

	/**
	 * Returns the ForkJoinPool used by this executor.
	 * This can be used to submit additional tasks that should share the same parallelism,
	 * such as link correction after translations complete.
	 *
	 * @return the ForkJoinPool used for parallel execution
	 */
	@Nonnull
	public ForkJoinPool getExecutor() {
		return this.executor;
	}

	/**
	 * Returns true if a permanent failure triggered shutdown.
	 *
	 * @return true if shutdown was requested due to permanent failure
	 */
	public boolean wasShutdownDueToPermanentFailure() {
		return this.shutdownRequested.get() && this.permanentFailureCause != null;
	}

	/**
	 * Returns the cause of the permanent failure, if any.
	 *
	 * @return the permanent failure exception or null
	 */
	@Nullable
	public NonRetriableException getPermanentFailureCause() {
		return this.permanentFailureCause;
	}
}
