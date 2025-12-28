package io.evitadb.comenius;

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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

		// Submit all jobs and collect futures
		final List<CompletableFuture<TranslationResult>> futures = new ArrayList<>(jobs.size());
		for (final TranslationJob job : jobs) {
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

		if (result.success()) {
			try {
				writeTranslation(result);
				this.log.info("[" + job.getType() + "] Translated: " + relativePath + " -> " + job.getTargetFile());
				return summary.withSuccess(result.inputTokens(), result.outputTokens());
			} catch (IOException e) {
				this.log.error("[" + job.getType() + "] Failed to write " + relativePath + ": " + e.getMessage());
				return summary.withFailure();
			}
		} else {
			this.log.error("[" + job.getType() + "] Translation failed for " + relativePath + ": " + result.errorMessage());
			return summary.withFailure();
		}
	}

	/**
	 * Writes a successful translation to the target file, adding the commit field.
	 *
	 * @param result the successful translation result
	 * @throws IOException if writing fails
	 */
	private void writeTranslation(@Nonnull TranslationResult result) throws IOException {
		final TranslationJob job = result.job();
		final MarkdownDocument doc = new MarkdownDocument(result.translatedContent());

		// Add commit field to front matter
		doc.setProperty("commit", job.getCurrentCommit());

		this.writer.write(doc, job.getTargetFile());
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
}
