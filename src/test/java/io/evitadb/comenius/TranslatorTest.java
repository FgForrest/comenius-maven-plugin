package io.evitadb.comenius;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Translator should translate using ChatModel")
public class TranslatorTest {

	private MockChatModel mockModel;
	private Translator translator;
	private PromptLoader promptLoader;

	@BeforeEach
	void setUp() {
		mockModel = new MockChatModel();
		mockModel.reset();
		promptLoader = new PromptLoader();
		// LangChain4j handles retry logic internally
		final LlmClient llmClient = new LlmClient(mockModel);
		translator = new Translator(llmClient, promptLoader);
	}

	@Test
	@DisplayName("shouldTranslateNewJobSuccessfully")
	void shouldTranslateNewJobSuccessfully() throws Exception {
		mockModel.setResponse("# Hallo Welt", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Hello World",
			"abc123",
			null,
			null
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals("# Hallo Welt", result.translatedContent());
		assertEquals(100, result.inputTokens());
		assertEquals(50, result.outputTokens());
	}

	@Test
	@DisplayName("shouldTranslateIncrementalJobWithSectionApproach")
	void shouldTranslateIncrementalJobWithSectionApproach() throws Exception {
		// Section-based: only the changed section is translated
		// The LLM returns the translated section content
		mockModel.setResponse("# Aktualisierter Inhalt\n\nNeuer Text hier.", 200, 100);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Updated Content\n\nNew text here.",       // current source
			"def456",
			null,
			null,
			"# Original Content\n\nOld text here.",       // original source
			"# Urspruenglicher Inhalt\n\nAlter Text hier.", // existing translation
			"abc123",
			2
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertNotNull(result.translatedContent());
	}

	@Test
	@DisplayName("shouldPreserveUnchangedSectionsInIncrementalTranslation")
	void shouldPreserveUnchangedSectionsInIncrementalTranslation() throws Exception {
		// Two sections: first unchanged, second modified
		// Only the modified section should trigger LLM call
		mockModel.setResponse("## Neuer Abschnitt\n\nUebersetzter neuer Text.", 200, 100);

		final String oldSource = "# Title\n\nIntro text.\n\n## Section One\n\nOriginal content.";
		final String newSource = "# Title\n\nIntro text.\n\n## Section Two\n\nNew content.";
		final String existingTranslation = "# Titel\n\nEinfuehrungstext.\n\n## Abschnitt Eins\n\nUrspruenglicher Inhalt.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// The unchanged intro section should be preserved verbatim
		final String content = result.translatedContent();
		assertTrue(content.contains("# Titel"), "Unchanged section should be preserved");
		assertTrue(content.contains("Einfuehrungstext"), "Unchanged intro content should be preserved");
	}

	@Test
	@DisplayName("shouldReturnFailureResultOnException")
	void shouldReturnFailureResultOnException() throws Exception {
		mockModel.setException(new RuntimeException("API Error"));

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Content",
			"abc123",
			null,
			null
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success());
		assertNotNull(result.errorMessage());
		assertTrue(result.errorMessage().contains("API Error"));
		assertEquals(0, result.inputTokens());
		assertEquals(0, result.outputTokens());
	}

	@Test
	@DisplayName("shouldTrackInputTokenCount")
	void shouldTrackInputTokenCount() throws Exception {
		mockModel.setResponse("translated", 150, 75);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		translator.translate(job).toCompletableFuture().get();

		assertEquals(150, translator.getInputTokenCount());
	}

	@Test
	@DisplayName("shouldTrackOutputTokenCount")
	void shouldTrackOutputTokenCount() throws Exception {
		mockModel.setResponse("translated", 150, 75);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		translator.translate(job).toCompletableFuture().get();

		assertEquals(75, translator.getOutputTokenCount());
	}

	@Test
	@DisplayName("shouldAccumulateTokenCounts")
	void shouldAccumulateTokenCounts() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final TranslateNewJob job1 = new TranslateNewJob(
			Path.of("/source/doc1.md"),
			Path.of("/target/de/doc1.md"),
			Locale.GERMAN,
			"content1",
			"abc123",
			null,
			null
		);

		final TranslateNewJob job2 = new TranslateNewJob(
			Path.of("/source/doc2.md"),
			Path.of("/target/de/doc2.md"),
			Locale.GERMAN,
			"content2",
			"def456",
			null,
			null
		);

		translator.translate(job1).toCompletableFuture().get();
		translator.translate(job2).toCompletableFuture().get();

		assertEquals(200, translator.getInputTokenCount());
		assertEquals(100, translator.getOutputTokenCount());
	}

	@Test
	@DisplayName("shouldHandleNullTokenUsage")
	void shouldHandleNullTokenUsage() throws Exception {
		mockModel.setResponse("translated", null);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals(0, result.inputTokens());
		assertEquals(0, result.outputTokens());
	}

	@Test
	@DisplayName("shouldResetTokenCounts")
	void shouldResetTokenCounts() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		translator.translate(job).toCompletableFuture().get();
		assertEquals(100, translator.getInputTokenCount());

		translator.resetTokenCounts();

		assertEquals(0, translator.getInputTokenCount());
		assertEquals(0, translator.getOutputTokenCount());
	}

	@Test
	@DisplayName("shouldPreserveJobInResult")
	void shouldPreserveJobInResult() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		final TranslationResult result = translator.translate(job).toCompletableFuture().get();

		assertSame(job, result.job());
	}

	// ============== Two-Phase Translation Tests ==============

	@Test
	@DisplayName("shouldTranslateFrontMatterSeparatelyWhenFieldsExist")
	void shouldTranslateFrontMatterSeparatelyWhenFieldsExist() throws Exception {
		// Configure mock to return different responses for each call
		mockModel.addResponse("[[title]]\nDer Titel\n[[/title]]", 50, 25);
		mockModel.addResponse("# Ubersetzter Inhalt", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: The Title\n---\n# Content",
			"abc123",
			null,
			List.of("title")
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Two calls should have been made
		assertEquals(2, mockModel.getCallCount());
		// Tokens should be aggregated from both phases
		assertEquals(150, result.inputTokens());
		assertEquals(75, result.outputTokens());
		// Result should contain both front matter fields and body
		final String content = result.translatedContent();
		assertTrue(content.contains("[[title]]"));
		assertTrue(content.contains("Der Titel"));
		assertTrue(content.contains("[[/title]]"));
		assertTrue(content.contains("# Ubersetzter Inhalt"));
	}

	@Test
	@DisplayName("shouldSkipFrontMatterPhaseWhenNoFieldsConfigured")
	void shouldSkipFrontMatterPhaseWhenNoFieldsConfigured() throws Exception {
		mockModel.addResponse("# Translated Content", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: The Title\n---\n# Content",
			"abc123",
			null,
			null  // No translatable fields configured
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Only one call should have been made (body only)
		assertEquals(1, mockModel.getCallCount());
		assertEquals(100, result.inputTokens());
		assertEquals(50, result.outputTokens());
	}

	@Test
	@DisplayName("shouldFailFastWhenFrontMatterPhaseFails")
	void shouldFailFastWhenFrontMatterPhaseFails() throws Exception {
		// First call (front matter) fails
		mockModel.addException(new RuntimeException("Front matter API error"));
		// Second call (body) should not be reached
		mockModel.addResponse("# Translated Content", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: The Title\n---\n# Content",
			"abc123",
			null,
			List.of("title")
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success());
		// Only one call should have been made
		assertEquals(1, mockModel.getCallCount());
		// Error message should indicate front matter phase
		assertTrue(result.errorMessage().contains("[FRONT_MATTER]"));
		assertTrue(result.errorMessage().contains("Front matter API error"));
	}

	@Test
	@DisplayName("shouldAggregateTokensFromBothPhases")
	void shouldAggregateTokensFromBothPhases() throws Exception {
		mockModel.addResponse("[[title]]\nTitel\n[[/title]]\n[[perex]]\nBeschreibung\n[[/perex]]", 80, 40);
		mockModel.addResponse("# Inhalt", 120, 60);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: Title\nperex: Description\n---\n# Content",
			"abc123",
			null,
			List.of("title", "perex")
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Tokens should be sum of both phases
		assertEquals(200, result.inputTokens());
		assertEquals(100, result.outputTokens());
		// Global counters should also be updated
		assertEquals(200, translator.getInputTokenCount());
		assertEquals(100, translator.getOutputTokenCount());
	}

	@Test
	@DisplayName("shouldAlwaysTranslateAllFrontMatterFieldsForIncrementalJob")
	void shouldAlwaysTranslateAllFrontMatterFieldsForIncrementalJob() throws Exception {
		// Front matter response should contain ALL fields, not just changed ones
		mockModel.addResponse("[[title]]\nNeuer Titel\n[[/title]]\n[[perex]]\nBeschreibung\n[[/perex]]", 80, 40);
		// Section-based body translation returns translated section
		mockModel.addResponse("# Aktualisierter Inhalt", 120, 60);

		// Create incremental job where only title changed
		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: New Title\nperex: Description\n---\n# Updated Content",  // current
			"def456",
			null,
			List.of("title", "perex"),
			"---\ntitle: Old Title\nperex: Description\n---\n# Original Content",  // original
			"---\ntitle: Alter Titel\nperex: Beschreibung\n---\n# Urspruenglicher Inhalt",  // existing translation
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Two calls should have been made (front matter + body section)
		assertEquals(2, mockModel.getCallCount());
		// Result should contain both fields
		final String content = result.translatedContent();
		assertTrue(content.contains("[[title]]"));
		assertTrue(content.contains("[[perex]]"));
	}

	// ============== Section-Based Incremental Translation Tests ==============

	@Test
	@DisplayName("shouldKeepExistingTranslationWhenAllSectionsUnchanged")
	void shouldKeepExistingTranslationWhenAllSectionsUnchanged() throws Exception {
		// If source is identical, no LLM calls needed for body
		final String source = "# Title\n\nContent here.";
		final String translation = "# Titel\n\nInhalt hier.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			source,    // same as original
			"def456",
			null,
			null,
			source,    // original source
			translation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// No LLM calls should be made (all sections unchanged)
		assertEquals(0, mockModel.getCallCount());
		// Existing translation should be preserved
		assertEquals(translation, result.translatedContent());
	}

	@Test
	@DisplayName("shouldTranslateOnlyModifiedSections")
	void shouldTranslateOnlyModifiedSections() throws Exception {
		// LLM called only for the modified section
		mockModel.setResponse("## Neuer Abschnitt\n\nNeuer uebersetzter Inhalt.", 100, 50);

		final String oldSource = "# Title\n\nIntro.\n\n## Section A\n\nOriginal.";
		final String newSource = "# Title\n\nIntro.\n\n## Section A\n\nModified.";
		final String existingTranslation = "# Titel\n\nEinleitung.\n\n## Abschnitt A\n\nUrspruenglich.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Only 1 LLM call for the modified section
		assertEquals(1, mockModel.getCallCount());
		// Unchanged intro section should be preserved
		final String content = result.translatedContent();
		assertTrue(content.contains("# Titel"), "Unchanged title section preserved");
		assertTrue(content.contains("Einleitung"), "Unchanged intro content preserved");
	}

	@Test
	@DisplayName("handles translation with intro when source has none")
	void shouldHandleTranslationWithIntroWhenSourceHasNone() throws Exception {
		// The exact reported bug: translation has "TODO" intro text before headings,
		// but English source starts directly with a heading — off-by-one in positional mapping
		mockModel.setResponse("## Zakladni typy\n\nAktualizovany obsah.", 100, 50);

		final String oldSource = "## Basic File Types\n\nOriginal content.\n\n## Storage Model\n\nStorage info.";
		final String newSource = "## Basic File Types\n\nUpdated content.\n\n## Storage Model\n\nStorage info.";
		final String existingTranslation =
			"TODO JNO: prelozit\n\n## Zakladni typy\n\nPuvodni obsah.\n\n## Model uloziste\n\nInfo o ulozisti.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/storage-model.md"),
			Path.of("/target/cs/storage-model.md"),
			Locale.forLanguageTag("cs"),
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals(1, mockModel.getCallCount());
		final String content = result.translatedContent();
		// The unchanged "Storage Model" section should be preserved from translation
		assertTrue(content.contains("Model uloziste"), "Unchanged heading section should be preserved");
		// The "TODO" intro should NOT appear as content of a heading section
		assertFalse(content.contains("TODO"), "Intro content should not leak into heading sections");
	}

	@Test
	@DisplayName("handles source with intro when translation has none")
	void shouldHandleSourceWithIntroWhenTranslationHasNone() throws Exception {
		// Reverse case: old source has intro but translation doesn't
		mockModel.setResponse("## Neuer Inhalt\n\nAktualisiert.", 100, 50);

		final String oldSource = "Note: draft\n\n## Section A\n\nOriginal.\n\n## Section B\n\nContent.";
		final String newSource = "Note: draft\n\n## Section A\n\nModified.\n\n## Section B\n\nContent.";
		final String existingTranslation = "## Abschnitt A\n\nUrspruenglich.\n\n## Abschnitt B\n\nInhalt.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals(1, mockModel.getCallCount());
		final String content = result.translatedContent();
		assertTrue(content.contains("Abschnitt B"), "Unchanged section B should be preserved");
	}

	@Test
	@DisplayName("handles both source and translation having intro sections")
	void shouldHandleBothHavingIntroSections() throws Exception {
		// Both have intro — positional mapping among heading sections should still work
		mockModel.setResponse("## Neuer Abschnitt\n\nAktualisierter Inhalt.", 100, 50);

		final String oldSource = "Intro text.\n\n## Section A\n\nOriginal.\n\n## Section B\n\nContent.";
		final String newSource = "Intro text.\n\n## Section A\n\nModified.\n\n## Section B\n\nContent.";
		final String existingTranslation =
			"Einleitungstext.\n\n## Abschnitt A\n\nUrspruenglich.\n\n## Abschnitt B\n\nInhalt.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals(1, mockModel.getCallCount());
		final String content = result.translatedContent();
		assertTrue(content.contains("Einleitungstext"), "Unchanged intro should be preserved");
		assertTrue(content.contains("Abschnitt B"), "Unchanged heading section should be preserved");
	}

	@Test
	@DisplayName("validates heading structure in section-based translation")
	void shouldValidateHeadingStructureInSectionBasedTranslation() throws Exception {
		// LLM returns response with extra heading — both first attempt and retry fail
		mockModel.setResponse("## Extra Heading\n\nContent.\n\n## Another\n\nMore.", 100, 50);

		final String oldSource = "## Section A\n\nOriginal.";
		final String newSource = "## Section A\n\nModified.";
		final String existingTranslation = "## Abschnitt A\n\nUrspruenglich.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success(), "Should fail due to heading structure mismatch");
		assertTrue(
			result.errorMessage().contains("Section count mismatch") ||
				result.errorMessage().contains("Heading level mismatch"),
			"Error should mention heading structure mismatch"
		);
		// Both first attempt and retry should have been called
		assertEquals(2, mockModel.getCallCount());
	}

	@Test
	@DisplayName("retries once when section heading level is wrong, then succeeds")
	void shouldRetryOnceWhenSectionHeadingLevelIsWrong() throws Exception {
		// First call: wrong heading level (H4 instead of H2)
		mockModel.addResponse("#### Abschnitt A\n\nModifizierter Inhalt.", 100, 50);
		// Second call (retry): correct heading level
		mockModel.addResponse("## Abschnitt A\n\nModifizierter Inhalt.", 100, 50);

		final String oldSource = "## Section A\n\nOriginal content.";
		final String newSource = "## Section A\n\nModified content.";
		final String existingTranslation = "## Abschnitt A\n\nUrspruenglicher Inhalt.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success(), "Should succeed after retry with correct heading");
		assertTrue(result.translatedContent().contains("## Abschnitt A"));
		// Two LLM calls: first attempt (wrong level) + retry (correct)
		assertEquals(2, mockModel.getCallCount());
	}

	@Test
	@DisplayName("fails when retry also has wrong heading structure")
	void shouldFailWhenRetryAlsoHasWrongHeadingStructure() throws Exception {
		// Both attempts produce extra headings
		mockModel.addResponse("## Extra\n\nContent.\n\n## Another\n\nMore.", 100, 50);
		mockModel.addResponse("## Still Extra\n\nContent.\n\n## Still Another\n\nMore.", 100, 50);

		final String oldSource = "## Section A\n\nOriginal.";
		final String newSource = "## Section A\n\nModified.";
		final String existingTranslation = "## Abschnitt A\n\nUrspruenglich.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success(), "Should fail when both attempts have wrong headings");
		assertEquals(2, mockModel.getCallCount());
	}

	@Test
	@DisplayName("does not retry when section heading is correct on first attempt")
	void shouldNotRetryWhenSectionHeadingIsCorrect() throws Exception {
		mockModel.setResponse("## Abschnitt A\n\nModifizierter Inhalt.", 100, 50);

		final String oldSource = "## Section A\n\nOriginal.";
		final String newSource = "## Section A\n\nModified.";
		final String existingTranslation = "## Abschnitt A\n\nUrspruenglich.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Only 1 LLM call — no retry needed
		assertEquals(1, mockModel.getCallCount());
	}

	@Test
	@DisplayName("falls back to full retranslation when old source and translation have different heading structure")
	void shouldFallBackToFullRetranslationWhenHeadingStructuresDiffer() throws Exception {
		// LLM response for the full retranslation
		mockModel.setResponse(
			"## Zakladni typy\n\nObsah.\n\n### Podnadpis\n\nDalsi obsah.\n\n#### WAL format\n\nWAL obsah.",
			200, 100
		);

		// Old source has 2 heading sections (no WAL subsection)
		final String oldSource = "## Basic File Types\n\nContent.\n\n### Subsection\n\nMore content.";
		// New source has 3 heading sections (WAL subsection added)
		final String newSource = "## Basic File Types\n\nContent.\n\n### Subsection\n\nMore content.\n\n#### WAL Format\n\nWAL content.";
		// Existing translation has 3 heading sections (LLM added the extra heading during original full translation)
		final String existingTranslation = "## Zakladni typy\n\nPuvodni obsah.\n\n### Podnadpis\n\nDalsi.\n\n#### WAL format\n\nWAL.";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			newSource,
			"def456",
			null,
			null,
			oldSource,
			existingTranslation,
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success(), "Should succeed via full retranslation fallback");
		// Full retranslation should produce one LLM call for the entire body
		assertEquals(1, mockModel.getCallCount());
		// Result should contain the full retranslation
		assertTrue(result.translatedContent().contains("Zakladni typy"));
		assertTrue(result.translatedContent().contains("WAL format"));
	}

	/**
	 * Simple mock implementation of ChatModel for testing.
	 * Supports queuing multiple responses for sequential calls.
	 */
	private static class MockChatModel implements ChatModel {
		private String responseText = "default response";
		private TokenUsage tokenUsage = new TokenUsage(0, 0);
		private RuntimeException exception = null;
		private final List<ResponseConfig> queuedResponses = new ArrayList<>();
		private int callCount = 0;

		void setResponse(String text, int inputTokens, int outputTokens) {
			this.responseText = text;
			this.tokenUsage = new TokenUsage(inputTokens, outputTokens);
			this.exception = null;
			this.queuedResponses.clear();
		}

		void setResponse(String text, TokenUsage usage) {
			this.responseText = text;
			this.tokenUsage = usage;
			this.exception = null;
			this.queuedResponses.clear();
		}

		void setException(RuntimeException e) {
			this.exception = e;
			this.queuedResponses.clear();
		}

		void addResponse(String text, int inputTokens, int outputTokens) {
			this.queuedResponses.add(new ResponseConfig(text, new TokenUsage(inputTokens, outputTokens), null));
		}

		void addException(RuntimeException e) {
			this.queuedResponses.add(new ResponseConfig(null, null, e));
		}

		int getCallCount() {
			return this.callCount;
		}

		void reset() {
			this.responseText = "default response";
			this.tokenUsage = new TokenUsage(0, 0);
			this.exception = null;
			this.queuedResponses.clear();
			this.callCount = 0;
		}

		@Override
		public ChatResponse chat(List<ChatMessage> messages) {
			this.callCount++;

			// Use queued response if available
			if (!this.queuedResponses.isEmpty()) {
				final ResponseConfig config = this.queuedResponses.remove(0);
				if (config.exception != null) {
					throw config.exception;
				}
				return ChatResponse.builder()
					.aiMessage(AiMessage.from(config.text))
					.tokenUsage(config.tokenUsage)
					.build();
			}

			// Fall back to default behavior
			if (this.exception != null) {
				throw this.exception;
			}
			return ChatResponse.builder()
				.aiMessage(AiMessage.from(this.responseText))
				.tokenUsage(this.tokenUsage)
				.build();
		}

		private record ResponseConfig(String text, TokenUsage tokenUsage, RuntimeException exception) {}
	}
}
