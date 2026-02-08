package io.evitadb.comenius;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.DocumentChunk;
import io.evitadb.comenius.model.DocumentSection;
import io.evitadb.comenius.model.DocumentSectionSplitter;
import io.evitadb.comenius.model.DocumentSplitter;
import io.evitadb.comenius.model.FrontMatterTranslationHelper;
import io.evitadb.comenius.model.HeadingStructureMismatchException;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.PhaseResult;
import io.evitadb.comenius.model.SectionAligner;
import io.evitadb.comenius.model.SectionAlignment;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translator uses a LangChain4J ChatLanguageModel to translate text according to provided instructions
 * and target locale. Supports both simple text translation and structured TranslationJob-based translation.
 *
 * Uses {@link LlmClient} for LLM communication with permanent failure detection.
 * LangChain4j handles retry logic internally. Non-retriable failures (authentication, quota exceeded)
 * are propagated to allow immediate shutdown.
 *
 * For incremental translations, uses section-based approach: splits documents at heading boundaries,
 * compares sections by hash, and retranslates only changed/added sections.
 */
public class Translator {

	private static final String FRONTMATTER_SYSTEM_TEMPLATE = "translate-frontmatter-system.txt";
	private static final String FRONTMATTER_USER_TEMPLATE = "translate-frontmatter-user.txt";

	@Nonnull
	private final LlmClient llmClient;
	@Nonnull
	private final PromptLoader promptLoader;
	@Nullable
	private final Executor executor;
	@Nonnull
	private final DocumentSplitter documentSplitter;
	private final AtomicLong inputTokenCount = new AtomicLong(0);
	private final AtomicLong outputTokenCount = new AtomicLong(0);

	/**
	 * Create a Translator using provided LlmClient, PromptLoader, and Executor.
	 * The LlmClient handles permanent failure detection.
	 * The executor is used for async operations; if null, ForkJoinPool.commonPool() is used.
	 *
	 * @param llmClient    non-null LLM client to use
	 * @param promptLoader non-null prompt loader for loading templates
	 * @param executor     executor for async operations; may be null to use common pool
	 */
	public Translator(
		@Nonnull LlmClient llmClient,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor
	) {
		this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
		this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader must not be null");
		this.executor = executor;
		this.documentSplitter = new DocumentSplitter();
	}

	/**
	 * Create a Translator using provided LlmClient and PromptLoader.
	 * Uses ForkJoinPool.commonPool() for async operations.
	 *
	 * @param llmClient    non-null LLM client to use
	 * @param promptLoader non-null prompt loader for loading templates
	 */
	public Translator(@Nonnull LlmClient llmClient, @Nonnull PromptLoader promptLoader) {
		this(llmClient, promptLoader, null);
	}

	/**
	 * Create a Translator using provided ChatModel, PromptLoader, and Executor.
	 * Wraps the ChatModel in an LlmClient.
	 *
	 * @param model        non-null chat model to use
	 * @param promptLoader non-null prompt loader for loading templates
	 * @param executor     executor for async operations; may be null to use common pool
	 */
	public Translator(
		@Nonnull ChatModel model,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor
	) {
		this(new LlmClient(model), promptLoader, executor);
	}

	/**
	 * Create a Translator using provided ChatModel and PromptLoader.
	 * Wraps the ChatModel in an LlmClient.
	 * Uses ForkJoinPool.commonPool() for async operations.
	 *
	 * @param model        non-null chat model to use
	 * @param promptLoader non-null prompt loader for loading templates
	 */
	public Translator(@Nonnull ChatModel model, @Nonnull PromptLoader promptLoader) {
		this(new LlmClient(model), promptLoader, null);
	}

	/**
	 * Create a Translator using provided ChatModel with a default PromptLoader.
	 * Wraps the ChatModel in an LlmClient.
	 * Uses ForkJoinPool.commonPool() for async operations.
	 *
	 * @param model non-null chat model to use
	 */
	public Translator(@Nonnull ChatModel model) {
		this(new LlmClient(model), new PromptLoader(), null);
	}

	/**
	 * Returns the LlmClient used by this translator.
	 * Useful for checking permanent failure status.
	 *
	 * @return the LLM client
	 */
	@Nonnull
	public LlmClient getLlmClient() {
		return this.llmClient;
	}

	/**
	 * Translates using a TranslationJob with a two-phase approach:
	 * - Phase 1: Translate front matter fields (if any configured)
	 * - Phase 2: Translate article body
	 *
	 * The phases are chained using CompletableFuture for proper sequencing.
	 * For incremental jobs, front matter fields are ALWAYS retranslated
	 * (can't safely detect if changes were in front matter).
	 *
	 * @param job the translation job containing source content and metadata
	 * @return CompletionStage with TranslationResult containing the translated content or error
	 */
	@Nonnull
	public CompletionStage<TranslationResult> translate(@Nonnull TranslationJob job) {
		Objects.requireNonNull(job, "job must not be null");

		final Executor effectiveExecutor = this.executor != null ? this.executor : ForkJoinPool.commonPool();

		return CompletableFuture.completedFuture(PhaseResult.initial(job))
			// Phase 1: Translate front matter (if fields exist)
			.thenCompose(result -> translateFrontMatter(result, effectiveExecutor))
			// Phase 2: Translate body
			.thenCompose(result -> translateBody(result, effectiveExecutor))
			// Phase 3: Convert to final result
			.thenApply(PhaseResult::toTranslationResult);
	}

	/**
	 * Phase 1: Translates front matter fields.
	 * For both new and incremental jobs, ALWAYS translates ALL configured fields
	 * because we cannot safely detect if changes were in front matter.
	 * Skips this phase if no translatable fields are configured.
	 *
	 * @param currentResult the current phase result
	 * @param executor      the executor for async operations
	 * @return CompletionStage with updated PhaseResult
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateFrontMatter(
		@Nonnull PhaseResult currentResult,
		@Nonnull Executor executor
	) {
		final TranslationJob job = currentResult.job();

		// Extract ALL translatable fields (not just changed ones for incremental)
		final Map<String, String> fieldsToTranslate = extractAllTranslatableFields(job);

		// Skip phase if no fields to translate
		if (fieldsToTranslate.isEmpty()) {
			return CompletableFuture.completedFuture(currentResult);
		}

		// Build front matter prompts
		final Map<String, String> placeholders = new HashMap<>(job.getCommonPlaceholders());
		placeholders.put("frontMatterFields",
			FrontMatterTranslationHelper.formatFieldsForPrompt(fieldsToTranslate));

		final String systemPrompt = this.promptLoader.loadAndInterpolate(
			FRONTMATTER_SYSTEM_TEMPLATE, placeholders);
		final String userPrompt = this.promptLoader.loadAndInterpolate(
			FRONTMATTER_USER_TEMPLATE, placeholders);

		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final List<ChatMessage> messages = List.of(
					SystemMessage.from(systemPrompt),
					UserMessage.from(userPrompt)
				);

				final ChatResponse response = this.llmClient.chat(messages);
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				final TokenUsage tokenUsage = response.tokenUsage();

				final long inputTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
				final long outputTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

				this.inputTokenCount.addAndGet(inputTokens);
				this.outputTokenCount.addAndGet(outputTokens);

				// Parse translated fields from response
				final String responseText = response.aiMessage().text();
				final Map<String, String> translatedFields =
					FrontMatterTranslationHelper.parseTranslatedFields(responseText, fieldsToTranslate);

				return currentResult.withFrontMatter(translatedFields, inputTokens, outputTokens, elapsedMillis);

			} catch (NonRetriableException e) {
				// Propagate permanent failures for executor to handle shutdown
				throw e;
			} catch (Exception e) {
				// Transient failures - mark as failed
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return currentResult.withFailure("FRONT_MATTER", e.getMessage(), elapsedMillis);
			}
		}, executor);
	}

	/**
	 * Phase 2: Translates article body.
	 * For incremental jobs, uses section-based translation to only retranslate changed sections.
	 * For new jobs with large bodies, splits into chunks and translates sequentially.
	 * Skips this phase if Phase 1 failed.
	 *
	 * @param currentResult the current phase result
	 * @param executor      the executor for async operations
	 * @return CompletionStage with updated PhaseResult
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateBody(
		@Nonnull PhaseResult currentResult,
		@Nonnull Executor executor
	) {
		// Skip if previous phase failed
		if (!currentResult.success()) {
			return CompletableFuture.completedFuture(currentResult);
		}

		final TranslationJob job = currentResult.job();

		// For incremental jobs, use section-based translation
		if (job instanceof TranslateIncrementalJob incrementalJob) {
			return translateSectionBased(currentResult, incrementalJob, executor);
		}

		// For new jobs, check if document needs splitting
		if (job instanceof TranslateNewJob newJob) {
			final MarkdownDocument sourceDoc = new MarkdownDocument(newJob.getSourceContent());
			final String bodyContent = sourceDoc.getBodyContent();
			final List<DocumentChunk> chunks = this.documentSplitter.split(bodyContent);

			// If multiple chunks, translate them sequentially
			if (chunks.size() > 1) {
				return translateChunkedBody(currentResult, newJob, chunks, executor);
			}
		}

		// Standard single-body translation
		return translateSingleBody(currentResult, job, executor);
	}

	/**
	 * Translates a single body (non-chunked) for new jobs.
	 * After translation, validates heading structure matches the source.
	 *
	 * @param currentResult the current phase result
	 * @param job           the translation job
	 * @param executor      the executor for async operations
	 * @return CompletionStage with updated PhaseResult
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateSingleBody(
		@Nonnull PhaseResult currentResult,
		@Nonnull TranslationJob job,
		@Nonnull Executor executor
	) {
		final String systemPrompt = job.buildSystemPrompt(this.promptLoader);
		final String userPrompt = job.buildUserPrompt(this.promptLoader);

		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final List<ChatMessage> messages = List.of(
					SystemMessage.from(systemPrompt),
					UserMessage.from(userPrompt)
				);

				final ChatResponse response = this.llmClient.chat(messages);
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				final TokenUsage tokenUsage = response.tokenUsage();

				final long inputTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
				final long outputTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

				this.inputTokenCount.addAndGet(inputTokens);
				this.outputTokenCount.addAndGet(outputTokens);

				final String llmResponse = response.aiMessage().text();

				// Validate heading structure for new translations
				if (job instanceof TranslateNewJob) {
					validateHeadingStructure(job, llmResponse);
				}

				return currentResult.withBody(llmResponse, inputTokens, outputTokens, elapsedMillis);

			} catch (NonRetriableException e) {
				// Propagate permanent failures for executor to handle shutdown
				throw e;
			} catch (Exception e) {
				// Transient failures - mark as failed
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return currentResult.withFailure("BODY", e.getMessage(), elapsedMillis);
			}
		}, executor);
	}

	/**
	 * Translates a document body in chunks for large documents.
	 * Chunks are translated sequentially to respect rate limits and maintain consistency.
	 * After all chunks are joined, validates heading structure matches the source.
	 *
	 * @param currentResult the current phase result
	 * @param job           the new translation job
	 * @param chunks        the document chunks to translate
	 * @param executor      the executor for async operations
	 * @return CompletionStage with updated PhaseResult
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateChunkedBody(
		@Nonnull PhaseResult currentResult,
		@Nonnull TranslateNewJob job,
		@Nonnull List<DocumentChunk> chunks,
		@Nonnull Executor executor
	) {
		// Start with empty result that we'll build up
		CompletionStage<ChunkedTranslationState> stage =
			CompletableFuture.completedFuture(new ChunkedTranslationState(currentResult, chunks.size()));

		// Chain translations sequentially
		for (final DocumentChunk chunk : chunks) {
			stage = stage.thenCompose(state -> {
				if (!state.success()) {
					return CompletableFuture.completedFuture(state);
				}
				return translateChunk(state, job, chunk, executor);
			});
		}

		// Convert final state to PhaseResult
		return stage.thenApply(state -> {
			if (!state.success()) {
				return state.toFailedPhaseResult();
			}
			final String joinedBody = state.getJoinedTranslation();

			// Validate heading structure for chunked translations
			try {
				validateHeadingStructure(job, joinedBody);
			} catch (HeadingStructureMismatchException e) {
				return currentResult.withFailure("BODY", e.getMessage(), state.totalElapsedMillis);
			}

			return currentResult.withBody(
				joinedBody,
				state.totalInputTokens,
				state.totalOutputTokens,
				state.totalElapsedMillis
			);
		});
	}

	/**
	 * Translates a single chunk of a large document.
	 *
	 * @param state    the current chunked translation state
	 * @param job      the translation job
	 * @param chunk    the chunk to translate
	 * @param executor the executor for async operations
	 * @return CompletionStage with updated state
	 */
	@Nonnull
	private CompletionStage<ChunkedTranslationState> translateChunk(
		@Nonnull ChunkedTranslationState state,
		@Nonnull TranslateNewJob job,
		@Nonnull DocumentChunk chunk,
		@Nonnull Executor executor
	) {
		// Build prompts for this chunk
		final String systemPrompt = job.buildSystemPrompt(this.promptLoader);
		final Map<String, String> placeholders = new HashMap<>(job.getCommonPlaceholders());
		placeholders.put("sourceContent", chunk.content());
		if (job.getInstructions() != null && !job.getInstructions().isBlank()) {
			placeholders.put("customInstructions", job.getInstructions());
		} else {
			placeholders.put("customInstructions", "");
		}

		final String userPrompt = this.promptLoader.loadAndInterpolate(
			"translate-new-user.txt", placeholders
		);

		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final List<ChatMessage> messages = List.of(
					SystemMessage.from(systemPrompt),
					UserMessage.from(userPrompt)
				);

				final ChatResponse response = this.llmClient.chat(messages);
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				final TokenUsage tokenUsage = response.tokenUsage();

				final long inputTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
				final long outputTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

				this.inputTokenCount.addAndGet(inputTokens);
				this.outputTokenCount.addAndGet(outputTokens);

				final String translatedChunk = response.aiMessage().text();
				return state.withTranslatedChunk(chunk.index(), translatedChunk, inputTokens, outputTokens, elapsedMillis);

			} catch (NonRetriableException e) {
				throw e;
			} catch (Exception e) {
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return state.withFailure("BODY_CHUNK_" + chunk.index(), e.getMessage(), elapsedMillis);
			}
		}, executor);
	}

	/**
	 * Internal state for tracking chunked translation progress.
	 */
	private static class ChunkedTranslationState {
		private final PhaseResult originalResult;
		private final String[] translatedChunks;
		private long totalInputTokens = 0;
		private long totalOutputTokens = 0;
		private long totalElapsedMillis = 0;
		private String errorPhase = null;
		private String errorMessage = null;

		ChunkedTranslationState(PhaseResult originalResult, int chunkCount) {
			this.originalResult = originalResult;
			this.translatedChunks = new String[chunkCount];
		}

		boolean success() {
			return this.errorPhase == null;
		}

		ChunkedTranslationState withTranslatedChunk(
			int index, String translation, long inputTokens, long outputTokens, long elapsedMillis
		) {
			this.translatedChunks[index] = translation;
			this.totalInputTokens += inputTokens;
			this.totalOutputTokens += outputTokens;
			this.totalElapsedMillis += elapsedMillis;
			return this;
		}

		ChunkedTranslationState withFailure(String phase, String message, long elapsedMillis) {
			this.errorPhase = phase;
			this.errorMessage = message;
			this.totalElapsedMillis += elapsedMillis;
			return this;
		}

		String getJoinedTranslation() {
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < this.translatedChunks.length; i++) {
				final String chunk = this.translatedChunks[i];
				if (chunk != null) {
					// Add newline separator if previous chunk didn't end with newline
					if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
						sb.append("\n\n");
					}
					sb.append(chunk);
				}
			}
			return sb.toString();
		}

		PhaseResult toFailedPhaseResult() {
			return this.originalResult.withFailure(
				this.errorPhase,
				this.errorMessage,
				this.totalElapsedMillis
			);
		}
	}

	/**
	 * Translates an incremental job using section-based approach.
	 * Splits old source, new source, and existing translation into sections,
	 * aligns old/new sections via LCS, and retranslates only changed/added sections.
	 *
	 * @param currentResult the current phase result
	 * @param job           the incremental translation job
	 * @param executor      the executor for async operations
	 * @return CompletionStage with updated PhaseResult
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateSectionBased(
		@Nonnull PhaseResult currentResult,
		@Nonnull TranslateIncrementalJob job,
		@Nonnull Executor executor
	) {
		// Split old source, new source, and existing translation into sections
		final MarkdownDocument oldSourceDoc = new MarkdownDocument(job.getOriginalSource());
		final MarkdownDocument newSourceDoc = new MarkdownDocument(job.getSourceContent());
		final String existingTranslationBody = job.getExistingTranslationBody();

		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(oldSourceDoc.getBodyContent());
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(newSourceDoc.getBodyContent());
		final List<DocumentSection> translationSections = DocumentSectionSplitter.split(existingTranslationBody);

		// Align old ↔ new sections
		final List<SectionAlignment> alignments = SectionAligner.align(oldSections, newSections);

		// Build result: for each alignment, either keep existing translation or translate
		// Prepare the ordered list of result sections
		final List<SectionTranslationTask> tasks = new ArrayList<>();
		for (final SectionAlignment alignment : alignments) {
			switch (alignment.type()) {
				case UNCHANGED -> {
					// Find corresponding translation section
					final String translatedContent = findTranslationForOldSection(
						alignment.oldIndex(), oldSections, translationSections
					);
					tasks.add(new SectionTranslationTask(
						alignment.newIndex(), translatedContent, false
					));
				}
				case MODIFIED -> {
					// Needs retranslation — use new source section content
					tasks.add(new SectionTranslationTask(
						alignment.newIndex(), newSections.get(alignment.newIndex()).content(), true
					));
				}
				case ADDED -> {
					// New section — translate from scratch
					tasks.add(new SectionTranslationTask(
						alignment.newIndex(), newSections.get(alignment.newIndex()).content(), true
					));
				}
				case DELETED -> {
					// Drop from output — do nothing
				}
			}
		}

		// Build the result array for section ordering
		final String[] resultSections = new String[tasks.size()];

		// Collect context information for tasks that need translation
		// Build the pre-populated context from already-translated (UNCHANGED) sections
		for (int i = 0; i < tasks.size(); i++) {
			final SectionTranslationTask task = tasks.get(i);
			if (!task.needsTranslation()) {
				resultSections[i] = task.content();
			}
		}

		// Chain section translations sequentially
		CompletionStage<SectionBasedState> stage = CompletableFuture.completedFuture(
			new SectionBasedState(currentResult, resultSections, tasks)
		);

		for (int i = 0; i < tasks.size(); i++) {
			final int taskIndex = i;
			final SectionTranslationTask task = tasks.get(i);
			if (task.needsTranslation()) {
				stage = stage.thenCompose(state -> {
					if (!state.success()) {
						return CompletableFuture.completedFuture(state);
					}
					return translateSection(state, job, task, taskIndex, executor);
				});
			}
		}

		// Convert to PhaseResult
		return stage.thenApply(state -> {
			if (!state.success()) {
				return state.toFailedPhaseResult();
			}
			return currentResult.withBody(
				state.getJoinedResult(),
				state.totalInputTokens,
				state.totalOutputTokens,
				state.totalElapsedMillis
			);
		});
	}

	/**
	 * Finds the translated content corresponding to an old section index.
	 * Uses position-based mapping: if old section at index N, try to use translation section at
	 * same relative position. Falls back to empty string if no matching translation exists.
	 *
	 * @param oldIndex            index in old sections
	 * @param oldSections         the old source sections
	 * @param translationSections the existing translation sections
	 * @return the translated content for this section
	 */
	@Nonnull
	private String findTranslationForOldSection(
		int oldIndex,
		@Nonnull List<DocumentSection> oldSections,
		@Nonnull List<DocumentSection> translationSections
	) {
		// If translation has same number of sections, use direct index mapping
		if (oldIndex < translationSections.size()) {
			return translationSections.get(oldIndex).content();
		}
		// Fallback: use old source content (will need retranslation eventually)
		if (oldIndex < oldSections.size()) {
			return oldSections.get(oldIndex).content();
		}
		return "";
	}

	/**
	 * Translates a single section with surrounding context.
	 *
	 * @param state     the current section-based translation state
	 * @param job       the incremental translation job
	 * @param task      the section translation task
	 * @param taskIndex the index of this task in the result array
	 * @param executor  the executor for async operations
	 * @return CompletionStage with updated state
	 */
	@Nonnull
	private CompletionStage<SectionBasedState> translateSection(
		@Nonnull SectionBasedState state,
		@Nonnull TranslateIncrementalJob job,
		@Nonnull SectionTranslationTask task,
		int taskIndex,
		@Nonnull Executor executor
	) {
		// Build context from surrounding sections
		final String precedingContext = state.getPrecedingContext(taskIndex);
		final String followingContext = state.getFollowingContext(taskIndex);

		final String systemPrompt = job.buildSystemPrompt(this.promptLoader);
		final String userPrompt = job.buildSectionUserPrompt(
			this.promptLoader, task.content(), precedingContext, followingContext
		);

		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final List<ChatMessage> messages = List.of(
					SystemMessage.from(systemPrompt),
					UserMessage.from(userPrompt)
				);

				final ChatResponse response = this.llmClient.chat(messages);
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				final TokenUsage tokenUsage = response.tokenUsage();

				final long inputTokens = tokenUsage != null ? tokenUsage.inputTokenCount() : 0;
				final long outputTokens = tokenUsage != null ? tokenUsage.outputTokenCount() : 0;

				this.inputTokenCount.addAndGet(inputTokens);
				this.outputTokenCount.addAndGet(outputTokens);

				final String translatedSection = response.aiMessage().text();
				return state.withTranslatedSection(taskIndex, translatedSection, inputTokens, outputTokens, elapsedMillis);

			} catch (NonRetriableException e) {
				throw e;
			} catch (Exception e) {
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return state.withFailure("BODY_SECTION_" + taskIndex, e.getMessage(), elapsedMillis);
			}
		}, executor);
	}

	/**
	 * Validates that the translated body has the same heading structure as the source.
	 *
	 * @param job            the translation job
	 * @param translatedBody the translated body content
	 * @throws HeadingStructureMismatchException if structures don't match
	 */
	private void validateHeadingStructure(
		@Nonnull TranslationJob job,
		@Nonnull String translatedBody
	) throws HeadingStructureMismatchException {
		final MarkdownDocument sourceDoc = new MarkdownDocument(job.getSourceContent());
		final String sourceBody = sourceDoc.getBodyContent();

		final List<DocumentSection> sourceSections = DocumentSectionSplitter.split(sourceBody);
		final List<DocumentSection> translatedSections = DocumentSectionSplitter.split(translatedBody);

		DocumentSectionSplitter.validateHeadingStructure(sourceSections, translatedSections);
	}

	/**
	 * Internal record for a section that either keeps its existing translation or needs retranslation.
	 *
	 * @param newIndex          index in new section list
	 * @param content           either translated content (if unchanged) or source content to translate
	 * @param needsTranslation  true if this section needs LLM translation
	 */
	private record SectionTranslationTask(
		int newIndex,
		@Nonnull String content,
		boolean needsTranslation
	) {}

	/**
	 * Internal state for tracking section-based translation progress.
	 */
	private static class SectionBasedState {
		private final PhaseResult originalResult;
		private final String[] resultSections;
		private final List<SectionTranslationTask> tasks;
		private long totalInputTokens = 0;
		private long totalOutputTokens = 0;
		private long totalElapsedMillis = 0;
		private String errorPhase = null;
		private String errorMessage = null;

		SectionBasedState(
			PhaseResult originalResult,
			String[] resultSections,
			List<SectionTranslationTask> tasks
		) {
			this.originalResult = originalResult;
			this.resultSections = resultSections;
			this.tasks = tasks;
		}

		boolean success() {
			return this.errorPhase == null;
		}

		SectionBasedState withTranslatedSection(
			int taskIndex, String translation, long inputTokens, long outputTokens, long elapsedMillis
		) {
			this.resultSections[taskIndex] = translation;
			this.totalInputTokens += inputTokens;
			this.totalOutputTokens += outputTokens;
			this.totalElapsedMillis += elapsedMillis;
			return this;
		}

		SectionBasedState withFailure(String phase, String message, long elapsedMillis) {
			this.errorPhase = phase;
			this.errorMessage = message;
			this.totalElapsedMillis += elapsedMillis;
			return this;
		}

		/**
		 * Gets preceding translated context for a section at the given task index.
		 * Returns content of the immediately preceding section that is already translated.
		 */
		@Nonnull
		String getPrecedingContext(int taskIndex) {
			for (int i = taskIndex - 1; i >= 0; i--) {
				if (this.resultSections[i] != null) {
					return this.resultSections[i];
				}
			}
			return "";
		}

		/**
		 * Gets following translated context for a section at the given task index.
		 * Returns content of the next section that is already translated (UNCHANGED sections).
		 */
		@Nonnull
		String getFollowingContext(int taskIndex) {
			for (int i = taskIndex + 1; i < this.resultSections.length; i++) {
				if (this.resultSections[i] != null) {
					return this.resultSections[i];
				}
			}
			return "";
		}

		@Nonnull
		String getJoinedResult() {
			final StringBuilder sb = new StringBuilder();
			for (int i = 0; i < this.resultSections.length; i++) {
				final String section = this.resultSections[i];
				if (section != null) {
					if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
						sb.append("\n\n");
					}
					sb.append(section);
				}
			}
			return sb.toString();
		}

		PhaseResult toFailedPhaseResult() {
			return this.originalResult.withFailure(
				this.errorPhase,
				this.errorMessage,
				this.totalElapsedMillis
			);
		}
	}

	/**
	 * Extracts ALL translatable fields from the source document.
	 * For both new and incremental jobs, this returns all configured fields
	 * (not just changed ones) because we always retranslate all front matter fields.
	 *
	 * @param job the translation job
	 * @return map of field names to values that should be translated
	 */
	@Nonnull
	private Map<String, String> extractAllTranslatableFields(@Nonnull TranslationJob job) {
		final MarkdownDocument sourceDoc = new MarkdownDocument(job.getSourceContent());
		return FrontMatterTranslationHelper.extractTranslatableFields(
			sourceDoc, job.getTranslatableFrontMatterFields()
		);
	}

	/**
	 * Translates the given text according to the provided instructions into the target locale.
	 * This is the legacy method for simple text translation.
	 *
	 * Each call is stateless and does not reuse any memory; messages are created fresh per invocation.
	 *
	 * @param instructions system-level instructions for the LLM; may be null or blank
	 * @param text         text to translate; must not be null or blank
	 * @param locale       target locale; must not be null
	 * @return CompletionStage with translated text
	 * @throws IllegalArgumentException when text is null/blank or locale is null
	 */
	@Nonnull
	public CompletionStage<String> translate(
		@Nullable String instructions,
		@Nonnull String text,
		@Nonnull Locale locale
	) {
		Objects.requireNonNull(text, "text must not be null");
		Objects.requireNonNull(locale, "locale must not be null");

		// Build messages for a stateless call
		final List<ChatMessage> messages = new ArrayList<>(8);
		if (instructions != null && !instructions.isBlank()) {
			messages.add(SystemMessage.from(instructions.trim()));
		}
		messages.add(
			SystemMessage.from(
				"Translate the following text to " + locale.toLanguageTag() + ":"
			)
		);
		messages.add(UserMessage.from(text));

		// Offload to a separate thread as a minimal async behavior; in real usage, model may block
		final Executor effectiveExecutor = this.executor != null ? this.executor : ForkJoinPool.commonPool();
		return CompletableFuture.supplyAsync(() -> {
			final ChatResponse response = this.llmClient.chat(messages);
			final TokenUsage tokenUsage = response.tokenUsage();
			if (tokenUsage != null) {
				this.inputTokenCount.addAndGet(tokenUsage.inputTokenCount());
				this.outputTokenCount.addAndGet(tokenUsage.outputTokenCount());
			}
			return response.aiMessage().text();
		}, effectiveExecutor);
	}

	/**
	 * Returns the total input tokens used across all translations.
	 *
	 * @return total input token count
	 */
	public long getInputTokenCount() {
		return this.inputTokenCount.get();
	}

	/**
	 * Returns the total output tokens generated across all translations.
	 *
	 * @return total output token count
	 */
	public long getOutputTokenCount() {
		return this.outputTokenCount.get();
	}

	/**
	 * Resets the token counters to zero.
	 */
	public void resetTokenCounts() {
		this.inputTokenCount.set(0);
		this.outputTokenCount.set(0);
	}
}
