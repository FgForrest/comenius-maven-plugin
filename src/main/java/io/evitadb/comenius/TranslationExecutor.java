package io.evitadb.comenius;

import io.evitadb.comenius.model.FrontMatterTranslationHelper;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import io.evitadb.comenius.model.TranslationSummary;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes translation jobs in parallel using a configurable thread pool.
 * Handles writing successful translations and collecting summary statistics.
 */
public final class TranslationExecutor {

	private static final long SHUTDOWN_TIMEOUT_SECONDS = 60;

	private final ExecutorService executor;
	private final Translator translator;
	private final Writer writer;
	private final Log log;
	private final Path sourceDir;
	/**
	 * Tracks successfully translated files for post-processing (e.g., link correction).
	 * Key is the target file path, value is the translated content before writing.
	 */
	@Nonnull
	private final Map<Path, String> successfullyTranslatedFiles = new ConcurrentHashMap<>();
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
	 * Creates a translation executor with the specified parallelism.
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
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be at least 1");
		}
		this.executor = Executors.newFixedThreadPool(parallelism);
		this.translator = Objects.requireNonNull(translator, "translator must not be null");
		this.writer = Objects.requireNonNull(writer, "writer must not be null");
		this.log = Objects.requireNonNull(log, "log must not be null");
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null").toAbsolutePath().normalize();
	}

	/**
	 * Executes all translation jobs and returns a summary of results.
	 * Jobs are executed in parallel up to the configured parallelism limit.
	 * Individual failures do not stop other jobs from executing.
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

		// Initialize progress tracking
		this.totalJobs = jobs.size();
		this.completedJobs.set(0);

		// Submit all jobs and collect futures
		final List<CompletableFuture<TranslationResult>> futures = new ArrayList<>(jobs.size());
		for (final TranslationJob job : jobs) {
			// Log when translation is being issued
			final Path relativePath = this.sourceDir.relativize(job.getSourceFile().toAbsolutePath().normalize());
			this.log.info("Translating: " + relativePath + " -> " +
				job.getLocale().getDisplayName() + " (" + job.getLocale().toLanguageTag() + ")");

			final CompletableFuture<TranslationResult> future = this.translator.translate(job)
				.toCompletableFuture();
			futures.add(future);
		}

		// Wait for all to complete
		final CompletableFuture<Void> allFutures = CompletableFuture.allOf(
			futures.toArray(new CompletableFuture[0])
		);

		try {
			allFutures.join();
		} catch (Exception e) {
			this.log.error("Error waiting for translations to complete: " + e.getMessage());
		}

		// Process results
		TranslationSummary summary = TranslationSummary.empty();
		for (final CompletableFuture<TranslationResult> future : futures) {
			try {
				final TranslationResult result = future.get();
				summary = processResult(result, summary);
			} catch (Exception e) {
				this.log.error("Failed to get translation result: " + e.getMessage());
				summary = summary.withFailure();
			}
		}

		return summary;
	}

	/**
	 * Processes a single translation result: writes successful translations and updates summary.
	 *
	 * @param result  the translation result to process
	 * @param summary the current summary to update
	 * @return updated summary
	 */
	@Nonnull
	private TranslationSummary processResult(
		@Nonnull TranslationResult result,
		@Nonnull TranslationSummary summary
	) {
		final TranslationJob job = result.job();
		final Path relativePath = this.sourceDir.relativize(job.getSourceFile());
		final int completed = this.completedJobs.incrementAndGet();
		final String progressBar = formatProgressBar(completed, this.totalJobs);
		final String localeInfo = job.getLocale().getDisplayName() + " (" + job.getLocale().toLanguageTag() + ")";

		if (result.success()) {
			try {
				writeTranslation(result);
				// Track for post-processing (link correction)
				this.successfullyTranslatedFiles.put(
					job.getTargetFile(),
					result.translatedContent()
				);
				this.log.info(progressBar + " [" + job.getType() + "] " + relativePath + " -> " + localeInfo);
				return summary.withSuccess(result.inputTokens(), result.outputTokens());
			} catch (IOException e) {
				this.log.error(progressBar + " [" + job.getType() + "] Failed to write " + relativePath + ": " + e.getMessage());
				return summary.withFailure();
			}
		} else {
			this.log.error(progressBar + " [" + job.getType() + "] Translation failed for " + relativePath + ": " + result.errorMessage());
			return summary.withFailure();
		}
	}

	/**
	 * Writes a successful translation to the target file, adding the commit field.
	 * If front matter fields were translated, extracts and merges them from the LLM response.
	 *
	 * @param result the successful translation result
	 * @throws IOException if writing fails
	 */
	private void writeTranslation(@Nonnull TranslationResult result) throws IOException {
		final TranslationJob job = result.job();
		final String translatedContent = result.translatedContent();

		// Get the expected translatable fields from the job
		final Map<String, String> expectedFields = job.getExtractedTranslatableFields();

		// Parse translated field values from response and extract body
		final Map<String, String> translatedFields = FrontMatterTranslationHelper
			.parseTranslatedFields(translatedContent, expectedFields);
		final String bodyContent = FrontMatterTranslationHelper
			.extractBodyFromResponse(translatedContent, expectedFields);

		// Create document from body content (without field blocks)
		final MarkdownDocument doc = new MarkdownDocument(bodyContent);

		// Merge translated front matter fields
		for (final Map.Entry<String, String> entry : translatedFields.entrySet()) {
			doc.setProperty(entry.getKey(), entry.getValue());
		}

		// Add commit field to front matter
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
	 * Returns a copy of the successfully translated files map.
	 * Key is the target file path, value is the translated content before writing.
	 * This is useful for post-processing steps like link correction.
	 *
	 * @return immutable copy of successfully translated files
	 */
	@Nonnull
	public Map<Path, String> getSuccessfullyTranslatedFiles() {
		return Map.copyOf(this.successfullyTranslatedFiles);
	}
}
