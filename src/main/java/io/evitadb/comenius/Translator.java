package io.evitadb.comenius;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.check.HeadingAnchorIndex;
import io.evitadb.comenius.diagnostics.TranslationFailureArtifacts;
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
import io.evitadb.comenius.model.StructuralMismatchException;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import io.evitadb.comenius.structure.MarkupScanner;
import io.evitadb.comenius.structure.StructuralComparator;
import io.evitadb.comenius.structure.TagBalance;
import io.evitadb.comenius.structure.TagCaseRepairer;
import io.evitadb.comenius.structure.TagVocabulary;
import io.evitadb.comenius.structure.UntranslatedContentChecker;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
	@Nullable
	private final Log log;
	@Nonnull
	private final DocumentSplitter documentSplitter;
	@Nullable
	private final TagVocabulary vocabulary;
	@Nullable
	private final TranslationFailureArtifacts failureArtifacts;
	private final AtomicLong inputTokenCount = new AtomicLong(0);
	private final AtomicLong outputTokenCount = new AtomicLong(0);

	/**
	 * Create a Translator using provided LlmClient, PromptLoader, Executor, and Maven Log.
	 * The LlmClient handles permanent failure detection.
	 * The executor is used for async operations; if null, ForkJoinPool.commonPool() is used.
	 *
	 * @param llmClient    non-null LLM client to use
	 * @param promptLoader non-null prompt loader for loading templates
	 * @param executor     executor for async operations; may be null to use common pool
	 * @param log          Maven log for diagnostic messages; may be null
	 */
	public Translator(
		@Nonnull LlmClient llmClient,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor,
		@Nullable Log log
	) {
		this(llmClient, promptLoader, executor, log, null);
	}

	/**
	 * Create a Translator using provided LlmClient, PromptLoader, Executor, Maven Log, and a
	 * corpus-derived tag vocabulary.
	 *
	 * The vocabulary enables tag-case repair, untranslated-leaf-content detection with a cheap
	 * autofix, and a structural comparison (tag sequence, inline tag multiset, blank line count)
	 * that catches a class of defect heading-count validation is blind to - a heading-free block
	 * (a language-switch span, an unclosed note) silently dropped by the model. Pass `null` to
	 * skip all three and keep exactly the prior behaviour.
	 *
	 * @param llmClient    non-null LLM client to use
	 * @param promptLoader non-null prompt loader for loading templates
	 * @param executor     executor for async operations; may be null to use common pool
	 * @param log          Maven log for diagnostic messages; may be null
	 * @param vocabulary   corpus-derived tag vocabulary; may be null to skip the new checks
	 */
	public Translator(
		@Nonnull LlmClient llmClient,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor,
		@Nullable Log log,
		@Nullable TagVocabulary vocabulary
	) {
		this(llmClient, promptLoader, executor, log, vocabulary, null);
	}

	/**
	 * Create a Translator that additionally keeps a copy of every translation its structural gates
	 * reject.
	 *
	 * A rejection is the point at which the model's output is discarded, and with it the only
	 * evidence of what went wrong; the next look at the problem then costs another paid run. Pass a
	 * writer to keep the source, the attempts and the reasons on disk instead, or `null` to keep
	 * exactly the prior behaviour.
	 *
	 * @param llmClient        non-null LLM client to use
	 * @param promptLoader     non-null prompt loader for loading templates
	 * @param executor         executor for async operations; may be null to use common pool
	 * @param log              Maven log for diagnostic messages; may be null
	 * @param vocabulary       corpus-derived tag vocabulary; may be null to skip the new checks
	 * @param failureArtifacts writer for rejected translations; may be null to discard them
	 */
	public Translator(
		@Nonnull LlmClient llmClient,
		@Nonnull PromptLoader promptLoader,
		@Nullable Executor executor,
		@Nullable Log log,
		@Nullable TagVocabulary vocabulary,
		@Nullable TranslationFailureArtifacts failureArtifacts
	) {
		this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
		this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader must not be null");
		this.executor = executor;
		this.log = log;
		this.documentSplitter = new DocumentSplitter();
		this.vocabulary = vocabulary;
		this.failureArtifacts = failureArtifacts;
	}

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
		this(llmClient, promptLoader, executor, null);
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

				String llmResponse = response.aiMessage().text();

				// Validate heading structure for new translations
				if (job instanceof TranslateNewJob) {
					if (this.vocabulary != null) {
						llmResponse = applyStructuralChecks(job, llmResponse);
					}
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
	 * Repairs tag-case drift, autofixes untranslated leaf content, and hard-fails on any
	 * structural mismatch that remains - the three checks validated during the scope-tree
	 * probing work, applied here to the production new-job path.
	 *
	 * Order matters: case must be repaired first, because a case-drifted tag is invisible to the
	 * case-sensitive vocabulary (it does not read as a mismatch, it reads as nothing), which would
	 * otherwise desynchronize both the untranslated-content scan and the structural comparison
	 * that follow it.
	 *
	 * @param job         the translation job, for source content and target locale
	 * @param llmResponse the model's raw response
	 * @return the repaired response
	 * @throws StructuralMismatchException if the structure still does not match after repair
	 */
	@Nonnull
	private String applyStructuralChecks(
		@Nonnull TranslationJob job,
		@Nonnull String llmResponse
	) throws StructuralMismatchException {
		final TagVocabulary tagVocabulary = Objects.requireNonNull(this.vocabulary);
		final String sourceBody = new MarkdownDocument(job.getSourceContent()).getBodyContent();

		final String caseRepaired = TagCaseRepairer.repair(tagVocabulary, sourceBody, llmResponse);
		final String autofixed = autofixUntranslatedContent(
			tagVocabulary, sourceBody, caseRepaired, job.getLocale()
		);

		final List<String> problems = StructuralComparator.compare(tagVocabulary, sourceBody, autofixed);
		if (!problems.isEmpty()) {
			final Map<String, String> attachments = new LinkedHashMap<>();
			attachments.put("source.md", sourceBody);
			attachments.put("response.md", llmResponse);
			attachments.put("after-repair.md", autofixed);
			recordFailure(job, "body", problems, attachments);
			throw new StructuralMismatchException(problems);
		}
		return autofixed;
	}

	/**
	 * Keeps a body that was rejected only after its sections had been joined back together.
	 *
	 * These failures are the interesting ones: every individual section passed its own gate, so
	 * whatever went wrong was introduced by the join or is only visible across section boundaries.
	 * The joined body next to its source is exactly what is needed to tell those apart.
	 *
	 * @param job        the job whose translation was rejected
	 * @param sourceBody the source body the translation was compared against
	 * @param joinedBody the rejected translation, with all sections joined
	 * @param reason     why the translation was rejected
	 */
	private void recordJoinedBodyFailure(
		@Nonnull TranslationJob job,
		@Nonnull String sourceBody,
		@Nonnull String joinedBody,
		@Nonnull String reason
	) {
		final Map<String, String> attachments = new LinkedHashMap<>();
		attachments.put("source.md", sourceBody);
		attachments.put("joined.md", joinedBody);
		recordFailure(job, "joined-body", List.of(reason), attachments);
	}

	/**
	 * Keeps a rejected translation on disk, when a failure-artifact writer is configured.
	 *
	 * Diagnostics must never be the reason a translation run dies, so a failure to write them is
	 * reported and then swallowed - reported, though, never silently dropped: an artifact that was
	 * expected and is not there would send the next investigation down the wrong path.
	 *
	 * @param job         the job whose translation was rejected
	 * @param unit        identifies the failing unit within the document
	 * @param reasons     why the translation was rejected
	 * @param attachments the texts to keep, keyed by file name
	 */
	private void recordFailure(
		@Nonnull TranslationJob job,
		@Nonnull String unit,
		@Nonnull List<String> reasons,
		@Nonnull Map<String, String> attachments
	) {
		if (this.failureArtifacts == null) {
			return;
		}
		try {
			final Path written = this.failureArtifacts.record(
				job.getLocale(), job.getSourceFile(), unit, reasons, attachments
			);
			if (this.log != null) {
				this.log.warn(
					"Rejected translation of " + job.getSourceFile() + " [" + unit + "] kept at " + written
				);
			}
		} catch (IOException e) {
			if (this.log != null) {
				this.log.warn(
					"Could not keep the rejected translation of " + job.getSourceFile() +
						" [" + unit + "]: " + e.getMessage()
				);
			}
		}
	}

	/**
	 * Detects untranslated leaf content and repairs it with small, targeted follow-up requests -
	 * only the flagged phrase is sent back, not the whole body.
	 *
	 * A dedicated, single-phrase request is a stronger signal than the heuristic that flagged it:
	 * if the model, asked to translate only this phrase with no surrounding distraction, still
	 * returns it unchanged, that is accepted as the model's considered answer (a loanword, a
	 * proper noun), not re-flagged as a survivor.
	 *
	 * @param tagVocabulary the vocabulary to scan with
	 * @param sourceBody    the source document body
	 * @param translated    the translated body, case already repaired
	 * @param locale        the target locale
	 * @return the translated body with every fixable suspect replaced
	 */
	@Nonnull
	private String autofixUntranslatedContent(
		@Nonnull TagVocabulary tagVocabulary,
		@Nonnull String sourceBody,
		@Nonnull String translated,
		@Nonnull Locale locale
	) {
		final List<UntranslatedContentChecker.Suspect> suspects =
			UntranslatedContentChecker.find(tagVocabulary, sourceBody, translated);
		if (suspects.isEmpty()) {
			return translated;
		}
		// applied back to front so that earlier offsets stay valid as later ones are spliced in
		final List<UntranslatedContentChecker.Suspect> sorted = new ArrayList<>(suspects);
		sorted.sort(Comparator.comparingInt(UntranslatedContentChecker.Suspect::start).reversed());
		final StringBuilder fixed = new StringBuilder(translated);
		for (final UntranslatedContentChecker.Suspect suspect : sorted) {
			final String fix = translatePhrase(suspect.text(), locale);
			if (!fix.equalsIgnoreCase(suspect.text().strip())) {
				fixed.replace(suspect.start(), suspect.end(), fix);
			}
		}
		return fixed.toString();
	}

	/**
	 * Translates a single short phrase in isolation - the follow-up request the untranslated-
	 * content autofix sends.
	 *
	 * @param phrase the phrase to translate
	 * @param locale the target locale
	 * @return the model's answer, whitespace-trimmed
	 */
	@Nonnull
	private String translatePhrase(@Nonnull String phrase, @Nonnull Locale locale) {
		final String systemPrompt = this.promptLoader.loadAndInterpolate("translate-phrase-system.txt", Map.of(
			"locale", locale.getDisplayLanguage(Locale.ENGLISH),
			"localeTag", locale.toLanguageTag()
		));
		final String userPrompt = this.promptLoader.loadAndInterpolate(
			"translate-phrase-user.txt", Map.of("phrase", phrase)
		);
		final ChatResponse response = this.llmClient.chat(List.of(
			SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)
		));
		final TokenUsage usage = response.tokenUsage();
		if (usage != null) {
			this.inputTokenCount.addAndGet(usage.inputTokenCount());
			this.outputTokenCount.addAndGet(usage.outputTokenCount());
		}
		return response.aiMessage().text().strip();
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
					if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
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

		final List<DocumentSection> oldSections = DocumentSectionSplitter.split(oldSourceDoc.getBodyContent(), this.vocabulary);
		final List<DocumentSection> newSections = DocumentSectionSplitter.split(newSourceDoc.getBodyContent(), this.vocabulary);
		final List<DocumentSection> translationSections = DocumentSectionSplitter.split(existingTranslationBody, this.vocabulary);

		// Verify that old source and existing translation have matching heading structures.
		// Compare only heading sections (not intros) — intro presence may legitimately differ
		// (e.g., translation has a "TODO" note before the first heading).
		// If heading structures don't match (e.g., translation was created before heading
		// validation existed, or the LLM added/removed headings during original translation),
		// section-based mapping is unreliable — fall back to full retranslation.
		final List<DocumentSection> oldHeadings = filterHeadingSections(oldSections);
		final List<DocumentSection> translationHeadings = filterHeadingSections(translationSections);
		try {
			DocumentSectionSplitter.validateHeadingStructure(oldHeadings, translationHeadings);
		} catch (HeadingStructureMismatchException e) {
			if (this.log != null) {
				this.log.warn(
					"Heading structure mismatch between old source and existing translation" +
					" for " + job.getSourceFile() + ": " + e.getMessage() +
					" — falling back to full retranslation."
				);
			}
			return translateFullBodyFallback(currentResult, job, executor);
		}

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
			final String joinedBody = state.getJoinedResult();

			// Validate heading structure for incremental translations
			try {
				validateHeadingStructure(job, joinedBody);
			} catch (HeadingStructureMismatchException e) {
				recordJoinedBodyFailure(job, newSourceDoc.getBodyContent(), joinedBody, e.getMessage());
				return currentResult.withFailure("BODY", e.getMessage(), state.totalElapsedMillis);
			}

			// Safety net: even with tag-aware section boundaries, an LLM can still perturb
			// balance within a section it was asked to translate. Heading-structure validation
			// alone does not catch this - see the 2026-07-28 incident where 10 files came out
			// with dropped <Note>/<LS> opening tags despite matching heading levels.
			if (this.vocabulary != null) {
				final TagBalance.Result balance = TagBalance.match(
					new MarkupScanner(this.vocabulary).scan(joinedBody)
				);
				if (!balance.isBalanced()) {
					if (this.log != null) {
						this.log.error(
							"Incremental translation for " + job.getSourceFile() + " produced unbalanced" +
								" markup after joining sections (tags: " + balance.pairedNames() +
								") - failing rather than writing corrupted output."
						);
					}
					final String unbalanced =
						"Joined incremental translation has unbalanced tags: " + balance.pairedNames();
					recordJoinedBodyFailure(job, newSourceDoc.getBodyContent(), joinedBody, unbalanced);
					return currentResult.withFailure("BODY", unbalanced, state.totalElapsedMillis);
				}
			}

			// Safety net: a heading can be visually present yet invisible to a real markdown
			// parser when a blank line is missing before it - it silently becomes part of the
			// preceding block instead of a heading. TagBalance cannot see this (it is a
			// whitespace defect, not a tag-pairing one); see the 2026-07-28 incident where a
			// join seam dropped exactly this blank line.
			//
			// The source body is the ground truth: a translation must expose exactly as many
			// parsed headings as its source does. Both sides go through commonmark, so a "#"
			// line inside a fenced code block is counted (or ignored) identically on both sides
			// and can never raise a false alarm - unlike a raw regex scan, which mistakes shell
			// comments such as "# run in foreground" for headings.
			{
				final int sourceHeadingCount = HeadingAnchorIndex.fromDocument(
					new MarkdownDocument(newSourceDoc.getBodyContent()).getDocument()
				).size();
				final int translatedHeadingCount = HeadingAnchorIndex.fromDocument(
					new MarkdownDocument(joinedBody).getDocument()
				).size();
				if (sourceHeadingCount != translatedHeadingCount) {
					if (this.log != null) {
						this.log.error(
							"Incremental translation for " + job.getSourceFile() + " exposes " +
								translatedHeadingCount + " parsed headings while its source has " +
								sourceHeadingCount + " - a heading was most likely swallowed by the block" +
								" preceding it because a blank line went missing at a section seam." +
								" Failing rather than writing corrupted output."
						);
					}
					final String swallowed =
						"Joined incremental translation lost a heading to the preceding block (" +
							sourceHeadingCount + " in source vs " + translatedHeadingCount + " parsed)";
					recordJoinedBodyFailure(job, newSourceDoc.getBodyContent(), joinedBody, swallowed);
					return currentResult.withFailure("BODY", swallowed, state.totalElapsedMillis);
				}
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
	 * Falls back to full body retranslation when the existing translation's heading
	 * structure doesn't match the old source (e.g., translation was created before
	 * heading validation existed, or the LLM added/removed headings during original
	 * translation). Creates a temporary {@link TranslateNewJob} and delegates to the
	 * standard body translation pipeline, which handles chunking if needed.
	 *
	 * @param currentResult the current phase result (with front matter already translated)
	 * @param incrementalJob the original incremental translation job
	 * @param executor       the executor for async operations
	 * @return CompletionStage with updated PhaseResult containing the full retranslation
	 */
	@Nonnull
	private CompletionStage<PhaseResult> translateFullBodyFallback(
		@Nonnull PhaseResult currentResult,
		@Nonnull TranslateIncrementalJob incrementalJob,
		@Nonnull Executor executor
	) {
		// Create a new-translation job with the current source content
		final TranslateNewJob fullJob = new TranslateNewJob(
			incrementalJob.getSourceFile(),
			incrementalJob.getTargetFile(),
			incrementalJob.getLocale(),
			incrementalJob.getSourceContent(),
			incrementalJob.getCurrentCommit(),
			incrementalJob.getInstructions(),
			incrementalJob.getTranslatableFrontMatterFields()
		);

		// Check if document needs chunked translation
		final MarkdownDocument sourceDoc = new MarkdownDocument(fullJob.getSourceContent());
		final String bodyContent = sourceDoc.getBodyContent();
		final List<DocumentChunk> chunks = this.documentSplitter.split(bodyContent);

		if (chunks.size() > 1) {
			return translateChunkedBody(currentResult, fullJob, chunks, executor);
		}

		return translateSingleBody(currentResult, fullJob, executor);
	}

	/**
	 * Filters a list of document sections, returning only heading sections (non-intro).
	 *
	 * @param sections the sections to filter
	 * @return list containing only sections with heading level > 0
	 */
	@Nonnull
	private static List<DocumentSection> filterHeadingSections(
		@Nonnull List<DocumentSection> sections
	) {
		final List<DocumentSection> headings = new ArrayList<>(sections.size());
		for (final DocumentSection section : sections) {
			if (!section.isIntro()) {
				headings.add(section);
			}
		}
		return headings;
	}

	/**
	 * Finds the translated content corresponding to an old section index.
	 * Uses heading-aware mapping: intro sections match intro sections, and heading
	 * sections are matched by their position among heading sections only. This handles
	 * cases where old source and translation have different intro section presence
	 * (e.g., translation has a "TODO" note before the first heading).
	 *
	 * Precondition: old source and translation have matching heading structures
	 * (validated by {@link DocumentSectionSplitter#validateHeadingStructure} before calling).
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
		final DocumentSection oldSection = oldSections.get(oldIndex);

		if (oldSection.isIntro()) {
			// Find intro section in translation (if any)
			for (final DocumentSection ts : translationSections) {
				if (ts.isIntro()) {
					return ts.content();
				}
			}
			// No intro in translation — use old source content as fallback
			return oldSection.content();
		}

		// Count position among heading (non-intro) sections in old list
		int headingPosition = 0;
		for (int i = 0; i < oldIndex; i++) {
			if (!oldSections.get(i).isIntro()) {
				headingPosition++;
			}
		}

		// Find the heading section at the same position in translation
		int count = 0;
		for (final DocumentSection ts : translationSections) {
			if (!ts.isIntro()) {
				if (count == headingPosition) {
					return ts.content();
				}
				count++;
			}
		}

		// Fallback: no matching translation section found — use old source content
		return oldSection.content();
	}

	/**
	 * Translates a single section with surrounding context, validates heading structure,
	 * and retries once if the heading structure is wrong.
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

		// Determine expected heading level from source section
		final List<DocumentSection> sourceParts = DocumentSectionSplitter.split(task.content(), this.vocabulary);
		final int expectedHeadingLevel = sourceParts.isEmpty() ? 0 : sourceParts.get(0).headingLevel();

		return CompletableFuture.supplyAsync(() -> {
			final long startTime = System.currentTimeMillis();
			try {
				final SectionTranslationResult result = translateAndValidateSection(
					systemPrompt, userPrompt, task.content(), expectedHeadingLevel,
					job, "section-" + taskIndex
				);

				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return state.withTranslatedSection(
					taskIndex, result.translatedContent(),
					result.inputTokens(), result.outputTokens(), elapsedMillis
				);

			} catch (NonRetriableException e) {
				throw e;
			} catch (Exception e) {
				final long elapsedMillis = System.currentTimeMillis() - startTime;
				return state.withFailure("BODY_SECTION_" + taskIndex, e.getMessage(), elapsedMillis);
			}
		}, executor);
	}

	/**
	 * Translates a section, validates heading structure, and retries once on mismatch.
	 * Accumulates token counts from both the first attempt and the retry (if any).
	 *
	 * @param systemPrompt        the system prompt for translation
	 * @param userPrompt          the user prompt for the section
	 * @param sourceContent       the source section content (for validation)
	 * @param expectedHeadingLevel the expected heading level (0 for intro)
	 * @param job                 the job this section belongs to, for failure artifacts
	 * @param unit                identifies this section within the document, for failure artifacts
	 * @return the translation result with accumulated tokens
	 * @throws HeadingStructureMismatchException if retry also fails validation
	 */
	@Nonnull
	private SectionTranslationResult translateAndValidateSection(
		@Nonnull String systemPrompt,
		@Nonnull String userPrompt,
		@Nonnull String sourceContent,
		int expectedHeadingLevel,
		@Nonnull TranslationJob job,
		@Nonnull String unit
	) throws HeadingStructureMismatchException {
		// First attempt
		final ChatResponse firstResponse = this.llmClient.chat(List.of(
			SystemMessage.from(systemPrompt),
			UserMessage.from(userPrompt)
		));
		final long[] firstTokens = accumulateTokens(firstResponse);
		final String firstResult = firstResponse.aiMessage().text();

		// Validate heading structure
		try {
			validateSectionHeadingStructure(sourceContent, firstResult);
			return new SectionTranslationResult(
				firstResult, firstTokens[0], firstTokens[1]
			);
		} catch (HeadingStructureMismatchException e) {
			// Retry once with corrective prompt
			final String correctionPrompt = buildHeadingCorrectionPrompt(
				sourceContent, expectedHeadingLevel, e.getMessage()
			);

			final ChatResponse retryResponse = this.llmClient.chat(List.of(
				SystemMessage.from(systemPrompt),
				UserMessage.from(correctionPrompt)
			));
			final long[] retryTokens = accumulateTokens(retryResponse);
			final String retryResult = retryResponse.aiMessage().text();

			// Validate retry — if this throws, it propagates to the caller
			try {
				validateSectionHeadingStructure(sourceContent, retryResult);
			} catch (HeadingStructureMismatchException retryFailure) {
				// both attempts are gone the moment this propagates; keep them while we still can
				final Map<String, String> attachments = new LinkedHashMap<>();
				attachments.put("source.md", sourceContent);
				attachments.put("attempt-1.md", firstResult);
				attachments.put("attempt-2.md", retryResult);
				recordFailure(
					job, unit, List.of(e.getMessage(), retryFailure.getMessage()), attachments
				);
				throw retryFailure;
			}

			return new SectionTranslationResult(
				retryResult,
				firstTokens[0] + retryTokens[0],
				firstTokens[1] + retryTokens[1]
			);
		}
	}

	/**
	 * Extracts token counts from a chat response and accumulates them into the global counters.
	 * Returns a two-element array: [inputTokens, outputTokens].
	 *
	 * @param response the chat response to extract tokens from
	 * @return array of [inputTokens, outputTokens]
	 */
	@Nonnull
	private long[] accumulateTokens(@Nonnull ChatResponse response) {
		final TokenUsage usage = response.tokenUsage();
		final long inputTokens = usage != null ? usage.inputTokenCount() : 0;
		final long outputTokens = usage != null ? usage.outputTokenCount() : 0;
		this.inputTokenCount.addAndGet(inputTokens);
		this.outputTokenCount.addAndGet(outputTokens);
		return new long[]{inputTokens, outputTokens};
	}

	/**
	 * Validates that a translated section preserves the heading structure of the source section.
	 * A section should produce the same number of sub-sections with matching heading levels
	 * when split by {@link DocumentSectionSplitter}.
	 *
	 * Deliberately uses the plain, non-merging {@link DocumentSectionSplitter#split(String)}
	 * rather than the vocabulary-aware overload. The vocabulary-aware merge is right for
	 * *partitioning* content (it must not tear a tag in half), but wrong for *comparing* it: the
	 * merge is content-dependent, so a section whose translation lost content re-merges
	 * differently than its source, and re-splitting both sides with the same lossy merge can hide
	 * exactly the mismatch this check exists to catch - see the 2026-08-01 incident where a 64KB
	 * merged section came back truncated and both sides still collapsed to the same section count.
	 *
	 * @param sourceContent     the original source section content
	 * @param translatedContent the LLM-translated section content
	 * @throws HeadingStructureMismatchException if the heading structure does not match
	 */
	private void validateSectionHeadingStructure(
		@Nonnull String sourceContent,
		@Nonnull String translatedContent
	) throws HeadingStructureMismatchException {
		final List<DocumentSection> sourceSections = DocumentSectionSplitter.split(sourceContent);
		final List<DocumentSection> translatedSections = DocumentSectionSplitter.split(translatedContent);

		DocumentSectionSplitter.validateHeadingStructure(sourceSections, translatedSections);
	}

	/**
	 * Builds a corrective user prompt for retrying a section translation
	 * where heading structure validation failed.
	 *
	 * @param sourceContent       the original source section to translate
	 * @param expectedHeadingLevel the expected heading level (0 for intro)
	 * @param errorDescription    description of what was wrong
	 * @return the corrective user prompt
	 */
	@Nonnull
	private String buildHeadingCorrectionPrompt(
		@Nonnull String sourceContent,
		int expectedHeadingLevel,
		@Nonnull String errorDescription
	) {
		final StringBuilder prompt = new StringBuilder();
		prompt.append("Your previous translation had a heading structure error: ");
		prompt.append(errorDescription).append("\n\n");

		if (expectedHeadingLevel == 0) {
			prompt.append("RULE: This section has NO heading. ");
			prompt.append("Your translation must NOT introduce any headings.\n\n");
		} else {
			final String hashes = "#".repeat(expectedHeadingLevel);
			prompt.append("RULE: This section starts with exactly ONE heading at level ");
			prompt.append(expectedHeadingLevel).append(" (").append(hashes).append("). ");
			prompt.append("Your translation MUST start with exactly ONE heading at the same level (");
			prompt.append(hashes).append("), MUST NOT change the heading level, ");
			prompt.append("and MUST NOT add any additional headings.\n\n");
		}

		prompt.append("Please translate this section again correctly:\n\n");
		prompt.append(sourceContent);

		return prompt.toString();
	}

	/**
	 * Internal record for the result of translating and validating a single section,
	 * including token counts accumulated across any retry attempt.
	 *
	 * @param translatedContent the translated section content
	 * @param inputTokens       total input tokens used (including retry)
	 * @param outputTokens      total output tokens used (including retry)
	 */
	private record SectionTranslationResult(
		@Nonnull String translatedContent,
		long inputTokens,
		long outputTokens
	) {}

	/**
	 * Validates that the translated body has the same heading structure as the source.
	 *
	 * Deliberately uses the plain, non-merging {@link DocumentSectionSplitter#split(String)}
	 * rather than the vocabulary-aware overload - see {@link #validateSectionHeadingStructure}
	 * for why the vocabulary-aware merge must not be used for comparison.
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
					if (sb.length() > 0) {
						// Normalize to exactly one blank line, regardless of how many
						// trailing newlines the previous section happened to end with. A
						// seam with only a single newline before a heading is not a blank
						// line under CommonMark, so the heading silently stops being one -
						// see the 2026-07-28 incident where this desynced heading counts
						// between source and translation without touching any tag balance.
						int end = sb.length();
						while (end > 0 && sb.charAt(end - 1) == '\n') {
							end--;
						}
						sb.setLength(end);
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
