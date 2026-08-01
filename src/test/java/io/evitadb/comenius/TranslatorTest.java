package io.evitadb.comenius;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.check.HeadingAnchorIndex;
import io.evitadb.comenius.diagnostics.TranslationFailureArtifacts;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.DocumentChunk;
import io.evitadb.comenius.model.DocumentSplitter;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import io.evitadb.comenius.structure.TagVocabulary;
import io.evitadb.comenius.structure.TranslationUnit;
import io.evitadb.comenius.structure.UnitPacker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
	@DisplayName("shouldInsertBlankLineWhenJoiningSectionsThatLackOne")
	void shouldInsertBlankLineWhenJoiningSectionsThatLackOne() throws Exception {
		// The reused (UNCHANGED) section ends with only a single trailing newline - the exact
		// seam that silently swallowed a heading into the preceding block in the 2026-07-28
		// incident (no blank line before a heading means CommonMark does not treat it as one).
		mockModel.setResponse("## Neuer Abschnitt\n\nUebersetzter neuer Text.", 200, 100);

		final String oldSource = "# Title\n\nIntro text.\n\n## Section One\n\nOriginal content.";
		final String newSource = "# Title\n\nIntro text.\n\n## Section Two\n\nNew content.";
		final String existingTranslation = "# Titel\n\nEinfuehrungstext.\n## Abschnitt Eins\n\nInhalt.";

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

		assertTrue(result.success(), "expected success, got: " + result.errorMessage());
		final String content = result.translatedContent();
		assertTrue(
			content.contains("Einfuehrungstext.\n\n## Neuer Abschnitt"),
			"expected a blank line before the joined heading, got: " + content
		);

		final MarkdownDocument doc = new MarkdownDocument(content);
		final HeadingAnchorIndex headings = HeadingAnchorIndex.fromDocument(doc.getDocument());
		assertEquals(
			2, headings.size(),
			"both headings must be recognised by a real markdown parser, not just visually present"
		);
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

	/**
	 * A tag that wraps more than one heading forces {@code DocumentSectionSplitter}'s
	 * vocabulary-aware split to merge those headings into a single section (it must not tear the
	 * tag in half). That merge is the right call for *partitioning* content, but a validation gate
	 * must not re-split both sides with that same merge to *compare* them: a translation that
	 * silently drops one of the merged headings still re-merges into "1 section" on both sides,
	 * since the tag itself stays balanced around whatever content survived. This mirrors the
	 * 2026-08-01 incident where a 64KB multi-heading section came back truncated and the
	 * vocabulary-aware comparison saw no mismatch at all.
	 *
	 * Any incremental job with a real vocabulary now goes through {@code translateUnitBased}
	 * instead of {@code translateSectionBased} (see {@code translateBody}), so this scenario
	 * exercises that path's own defense - {@code StructuralComparator}, via
	 * {@code applyStructuralChecks} - rather than the section splitter's comparison directly.
	 * {@code translateSectionBased}'s own comparison fix from the same incident is still exercised
	 * by every other section-based test in this class: those all run with {@code vocabulary ==
	 * null}, where {@code DocumentSectionSplitter.split(text, null)} already equals
	 * {@code split(text)} - the merge that hid the original bug cannot happen there regardless,
	 * which is exactly why a real vocabulary always routes here instead of to that path now.
	 */
	@Test
	@DisplayName("catches a dropped heading inside a tag-wrapped unit via unit-based translation")
	void shouldCatchDroppedHeadingInWideTagSpan() throws Exception {
		final TagVocabulary wrapVocabulary = TagVocabulary.of(Set.of("Wrap"), Set.of(), Set.of(), false);
		final Translator vocabularyAwareTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, wrapVocabulary
		);

		// the model drops "Section B" entirely but still closes </Wrap> correctly, so the
		// response stays internally tag-balanced despite the missing heading
		mockModel.setResponse(
			"<Wrap>\n\n## Abschnitt A\n\nGeaendert A.\n\n</Wrap>", 100, 50
		);

		final String oldSource =
			"<Wrap>\n\n## Section A\n\nOriginal A.\n\n## Section B\n\nOriginal B.\n\n</Wrap>";
		final String newSource =
			"<Wrap>\n\n## Section A\n\nModified A.\n\n## Section B\n\nOriginal B.\n\n</Wrap>";
		final String existingTranslation =
			"<Wrap>\n\n## Abschnitt A\n\nUrspruenglich A.\n\n## Abschnitt B\n\nUrspruenglich B.\n\n</Wrap>";

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

		final CompletionStage<TranslationResult> stage = vocabularyAwareTranslator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success(), "Should fail: 'Section B' was silently dropped");
		assertTrue(
			result.errorMessage().contains("block structure changed"),
			"Error should report the structural mismatch, got: " + result.errorMessage()
		);
		// unit-based translation does not retry on a structural mismatch, unlike the heading-level
		// retry the old section-based path used - one call is all this scenario should cost
		assertEquals(1, mockModel.getCallCount());
	}

	/**
	 * Mirrors the real shape of the 2026-08-01 incident at unit granularity: a tag ({@code Wrap})
	 * wraps several headings, one of which was modified and two of which ({@code New A}/{@code New
	 * B}) are genuinely new. Uses a tiny {@link UnitPacker.Settings} (the production default, 32kB,
	 * would pack this whole fixture into one unit and make per-unit alignment unobservable - see
	 * the constructor this test uses).
	 *
	 * This pins the seam flagged during design: {@code ScopeTreeReconstructor#reconstructUnits} is
	 * keyed by node identity against the tree it renders (the *new* source's tree), so an
	 * UNCHANGED unit's replacement value must be the *old translation's* text pulled by position -
	 * never an old-translation {@link TranslationUnit} used as a key, which belongs to a different
	 * tree entirely. "Section Two" and "Section Three" are unchanged and must come
	 * back byte-identical to the existing Czech translation; "Section One" is modified and "New
	 * A"/"New B" are added, so exactly those three - and only those three - must reach the model.
	 */
	@Test
	@DisplayName("reuses unchanged units from the existing translation by position, translates only changed/added ones")
	void shouldReuseUnchangedUnitsAndTranslateOnlyChangedOnes() throws Exception {
		final TagVocabulary wrapVocabulary = TagVocabulary.of(Set.of("Wrap"), Set.of(), Set.of(), false);
		// production always uses UnitPacker.Settings.defaults() (32kB) - this fixture is
		// deliberately far too small to exceed that, so a tiny target is injected instead to make
		// per-unit alignment observable at unit-test scale; see the constructor's own javadoc
		final UnitPacker.Settings tinySettings = new UnitPacker.Settings(60, 300, 10);
		final Translator packedTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, wrapVocabulary, null, tinySettings
		);

		final String oldSource = "Intro paragraph.\n\n"
			+ "## Section One\n\nOriginal content one.\n\n"
			+ "<Wrap>\n\n"
			+ "## Section Two\n\nOriginal content two.\n\n"
			+ "## Section Three\n\nOriginal content three.\n\n"
			+ "</Wrap>\n";
		final String newSource = "Intro paragraph.\n\n"
			+ "## Section One\n\nModified content one.\n\n"
			+ "<Wrap>\n\n"
			+ "## Section Two\n\nOriginal content two.\n\n"
			+ "## New A\n\nBrand new content A.\n\n"
			+ "## New B\n\nBrand new content B.\n\n"
			+ "## Section Three\n\nOriginal content three.\n\n"
			+ "</Wrap>\n";
		final String existingTranslation = "Uvodni odstavec.\n\n"
			+ "## Oddil Jedna\n\nPuvodni obsah jedna.\n\n"
			+ "<Wrap>\n\n"
			+ "## Oddil Dva\n\nPuvodni obsah dva.\n\n"
			+ "## Oddil Tri\n\nPuvodni obsah tri.\n\n"
			+ "</Wrap>\n";

		// queued in the order tasks are built: document order of the new source
		mockModel.addResponse("## Oddil Jedna\n\nZmeneny obsah jedna.", 50, 30);
		mockModel.addResponse("## Novy A\n\nZcela novy obsah A.", 50, 30);
		mockModel.addResponse("## Novy B\n\nZcela novy obsah B.", 50, 30);

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

		final TranslationResult result = packedTranslator.translate(job).toCompletableFuture().get();

		assertTrue(result.success(), "expected success, got: " + result.errorMessage());
		// exactly the modified and added units reach the model - not the unchanged ones
		assertEquals(3, mockModel.getCallCount());

		final String body = result.translatedContent();
		assertTrue(body.contains("Uvodni odstavec."), "unchanged intro should be preserved");
		assertTrue(body.contains("## Oddil Jedna\n\nZmeneny obsah jedna."), "modified section should be translated");
		assertTrue(
			body.contains("## Oddil Dva\n\nPuvodni obsah dva."),
			"unchanged 'Section Two' must come back byte-identical to the OLD translation, not "
				+ "re-derived from the new source - got: " + body
		);
		assertTrue(body.contains("## Novy A\n\nZcela novy obsah A."), "added section 'New A' should be translated");
		assertTrue(body.contains("## Novy B\n\nZcela novy obsah B."), "added section 'New B' should be translated");
		assertTrue(
			body.contains("## Oddil Tri\n\nPuvodni obsah tri."),
			"unchanged 'Section Three' must come back byte-identical to the OLD translation - got: " + body
		);

		// the ancestor tag must be re-attached exactly once around the whole run it wraps, not
		// once per inner unit
		assertEquals(1, body.split("<Wrap>", -1).length - 1, "expected exactly one <Wrap> open");
		assertEquals(1, body.split("</Wrap>", -1).length - 1, "expected exactly one </Wrap> close");

		final MarkdownDocument doc = new MarkdownDocument(body);
		final HeadingAnchorIndex headings = HeadingAnchorIndex.fromDocument(doc.getDocument());
		assertEquals(5, headings.size(), "every heading from the new source must be present exactly once");
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

	/**
	 * The section gate is the one that rejected `fetching.md` in the real corpus, and the one whose
	 * verdict was impossible to investigate because both attempts were discarded with it. It is
	 * also the trickiest recording site - two attempts, two reasons - so it gets its own test.
	 */
	@Test
	@DisplayName("shouldKeepBothSectionAttemptsWhenTheRetryAlsoFails")
	void shouldKeepBothSectionAttemptsWhenTheRetryAlsoFails(@TempDir Path failureDir) throws Exception {
		final Translator recordingTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, null,
			new TranslationFailureArtifacts(failureDir)
		);

		mockModel.addResponse("## Extra\n\nContent.\n\n## Another\n\nMore.", 100, 50);
		mockModel.addResponse("## Still Extra\n\nContent.\n\n## Still Another\n\nMore.", 100, 50);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/documentation/en/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"## Section A\n\nModified.",
			"def456",
			null,
			null,
			"## Section A\n\nOriginal.",
			"## Abschnitt A\n\nUrspruenglich.",
			"abc123",
			1
		);

		final TranslationResult result = recordingTranslator.translate(job).toCompletableFuture().get();

		assertFalse(result.success());
		final Path unit = failureDir.resolve("de").resolve("documentation_en_doc.md").resolve("section-0");
		assertTrue(Files.isDirectory(unit), "expected artifacts under " + unit);
		assertTrue(Files.readString(unit.resolve("source.md")).contains("## Section A"));
		assertTrue(
			Files.readString(unit.resolve("attempt-1.md")).contains("## Another"),
			"the first rejected attempt must be kept, not just the last"
		);
		assertTrue(
			Files.readString(unit.resolve("attempt-2.md")).contains("## Still Another"),
			"the retry must be kept too"
		);
		// both verdicts, not just the retry's - what changed between the attempts is the evidence
		final String reason = Files.readString(unit.resolve("reason.txt"));
		assertEquals(2, reason.lines().filter(line -> line.startsWith("- ")).count(), reason);
		assertTrue(reason.contains("Section count mismatch"), reason);
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

	@Test
	@DisplayName("shouldFailNewJobOnStructuralMismatchWhenVocabularyProvided")
	void shouldFailNewJobOnStructuralMismatchWhenVocabularyProvided() throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final Translator vocabAwareTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary
		);

		// the model drops the whole <LS> block - a heading-free block invisible to the
		// pre-existing heading-only validation, exactly the defect this gate exists to catch
		mockModel.setResponse("# Titulek\n\nUvod.\n", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish text.\n\n</LS>\n",
			"abc123",
			null,
			null
		);

		final CompletionStage<TranslationResult> stage = vocabAwareTranslator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success());
		assertTrue(
			result.errorMessage() != null && result.errorMessage().contains("structural problem"),
			"expected a structural-mismatch failure, got: " + result.errorMessage()
		);
	}

	/**
	 * The gate that refuses a bad translation is also the point at which the model's output is
	 * thrown away - so the failure that costs money to produce is the one that leaves nothing
	 * behind to explain it. This checks that the refusal keeps its evidence.
	 */
	@Test
	@DisplayName("shouldKeepTheRejectedTranslationWhenAFailureDirIsConfigured")
	void shouldKeepTheRejectedTranslationWhenAFailureDirIsConfigured(@TempDir Path failureDir) throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final Translator vocabAwareTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary,
			new TranslationFailureArtifacts(failureDir)
		);

		mockModel.setResponse("# Titulek\n\nUvod.\n", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/documentation/en/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish text.\n\n</LS>\n",
			"abc123",
			null,
			null
		);

		final TranslationResult result = vocabAwareTranslator.translate(job).toCompletableFuture().get();

		assertFalse(result.success());
		final Path unit = failureDir.resolve("cs").resolve("documentation_en_doc.md").resolve("body");
		assertTrue(Files.isDirectory(unit), "expected artifacts under " + unit);
		assertTrue(
			Files.readString(unit.resolve("response.md")).contains("Uvod."),
			"the rejected response itself must be kept"
		);
		assertTrue(
			Files.readString(unit.resolve("source.md")).contains("<LS to=\"e\">"),
			"the source it was compared against must be kept"
		);
		assertTrue(
			Files.readString(unit.resolve("reason.txt")).contains("block structure changed"),
			"the reason must name the problem"
		);
	}

	@Test
	@DisplayName("shouldNotWriteAnythingWhenNoFailureDirIsConfigured")
	void shouldNotWriteAnythingWhenNoFailureDirIsConfigured(@TempDir Path failureDir) throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final Translator vocabAwareTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary
		);

		mockModel.setResponse("# Titulek\n\nUvod.\n", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/documentation/en/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish text.\n\n</LS>\n",
			"abc123",
			null,
			null
		);

		assertFalse(vocabAwareTranslator.translate(job).toCompletableFuture().get().success());
		try (var entries = Files.list(failureDir)) {
			assertEquals(0, entries.count(), "recording must stay opt-in");
		}
	}

	@Test
	@DisplayName("shouldRepairTagCaseAndSucceedWhenStructureIntact")
	void shouldRepairTagCaseAndSucceedWhenStructureIntact() throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final Translator vocabAwareTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary
		);

		// the model closes <LS> as lowercase </ls> - invisible to the case-sensitive vocabulary
		// as a mismatch, since it simply isn't recognised as a tag at all
		mockModel.setResponse("# Titulek\n\n<LS to=\"e\">\n\nCesky text.\n\n</ls>\n", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			"# Title\n\n<LS to=\"e\">\n\nEnglish text.\n\n</LS>\n",
			"abc123",
			null,
			null
		);

		final CompletionStage<TranslationResult> stage = vocabAwareTranslator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success(), "expected success, got: " + result.errorMessage());
		assertTrue(result.translatedContent().contains("</LS>"), "closing tag case should be repaired");
		assertFalse(result.translatedContent().contains("</ls>"));
	}

	/**
	 * Reproduces the 2026-07-28 write-data.md incident at the smallest scale that reaches the
	 * chunked new-job path: {@link io.evitadb.comenius.model.DocumentSplitter} is heading-based, so
	 * a headingless block (an {@code <LS>} span with no heading inside it, exactly like the real
	 * "warm-up mode termination" section) can be silently dropped by the model within a single
	 * chunk without changing that chunk's heading count. Before this fix, only the *joined* body's
	 * heading count was checked - invisible to a heading-free loss. Uses a tiny target size (the
	 * production default, 32kB, would never split a test-sized fixture - see the constructor this
	 * test uses, added for the same testability reason as {@code UnitPacker.Settings}).
	 */
	@Test
	@DisplayName("fails a chunked new-job translation when a chunk silently drops a headingless block")
	void shouldFailChunkedNewJobWhenAChunkDropsAHeadinglessBlock() throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final Translator chunkedTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary, null,
			UnitPacker.Settings.defaults(), new DocumentSplitter(30)
		);

		final String source =
			"# Chapter One\n\n" +
			"Intro text for chapter one, padded so this document comfortably exceeds the tiny" +
			" target chunk size used to force splitting for this test.\n\n" +
			"<LS to=\"e\">\n\nEnglish-only content that must survive translation.\n\n</LS>\n\n" +
			"# Chapter Two\n\n" +
			"Content for chapter two, also padded so the split lands cleanly at this heading.\n";

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			source,
			"abc123",
			null,
			null
		);

		// chunk 1 (Chapter One): the model drops the whole <LS> block, keeping the heading intact -
		// invisible to heading-only validation, exactly the defect this gate exists to catch
		mockModel.addResponse("# Kapitola jedna\n\nUvodni text ke kapitole jedna.\n", 100, 50);
		// chunk 2 (Chapter Two): would be a normal translation, never reached
		mockModel.addResponse("# Kapitola dva\n\nObsah kapitoly dva.\n", 100, 50);

		final TranslationResult result = chunkedTranslator.translate(job).toCompletableFuture().get();

		assertFalse(result.success(), "the dropped <LS> block must fail the translation");
		assertTrue(
			result.errorMessage() != null && result.errorMessage().contains("structural problem"),
			"expected a structural-mismatch failure, got: " + result.errorMessage()
		);
		assertEquals(1, mockModel.getCallCount(), "chunk 2 must never be attempted once chunk 1 fails");
	}

	/**
	 * {@link io.evitadb.comenius.model.DocumentSplitter} picks chunk boundaries purely by heading
	 * position, with no awareness of tag nesting - so a tag that spans more than one heading is
	 * torn across chunks in the source itself. When every chunk's response faithfully preserves its
	 * own half of the tear, the joined body must reassemble into valid, balanced markup - this is
	 * the non-regression counterpart to the next test.
	 */
	@Test
	@DisplayName("joins a tag legitimately torn across chunks back into balanced markup")
	void shouldJoinChunksWithATagTornAcrossTheBoundaryIntoBalancedMarkup() throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final DocumentSplitter splitter = new DocumentSplitter(30);
		final Translator chunkedTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary, null,
			UnitPacker.Settings.defaults(), splitter
		);

		final String source =
			"<LS to=\"e\">\n\n" +
			"# Chapter One\n\n" +
			"English content for chapter one, padded well beyond the tiny target chunk size so" +
			" the splitter is forced to break this document into pieces for the test.\n\n" +
			"# Chapter Two\n\n" +
			"More English content for chapter two, also padded, before the language switch" +
			" finally closes below.\n\n</LS>\n";

		final List<DocumentChunk> chunks = splitter.split(source);
		assertEquals(2, chunks.size(), "fixture must force exactly two chunks for this test to be meaningful");
		assertTrue(chunks.get(0).content().contains("<LS") && !chunks.get(0).content().contains("</LS>"),
			"chunk 1 must hold the dangling open half of the torn tag");
		assertTrue(chunks.get(1).content().contains("</LS>") && !chunks.get(1).content().contains("<LS to"),
			"chunk 2 must hold the dangling close half of the torn tag");

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			source,
			"abc123",
			null,
			null
		);

		// each response preserves its own chunk's tag structure exactly, only the prose changes
		mockModel.addResponse(
			"<LS to=\"e\">\n\n# Kapitola jedna\n\nCesky text pro kapitolu jedna.\n\n", 100, 50
		);
		mockModel.addResponse("# Kapitola dva\n\nDalsi cesky text pro kapitolu dva.\n\n</LS>\n", 100, 50);

		final TranslationResult result = chunkedTranslator.translate(job).toCompletableFuture().get();

		assertTrue(result.success(), "a faithfully-preserved torn tag must still join cleanly, got: " + result.errorMessage());
		assertEquals(1, result.translatedContent().split("<LS", -1).length - 1);
		assertEquals(1, result.translatedContent().split("</LS>", -1).length - 1);
	}

	/**
	 * The failure counterpart to the previous test: chunk 2's response drops its half of the same
	 * torn tag. Whether this is caught by the per-chunk comparison or by the joined-body
	 * {@code TagBalance} safety net, the outcome that matters is that the translation is rejected
	 * rather than silently written out with a dangling, never-closed {@code <LS>}.
	 */
	@Test
	@DisplayName("fails rather than writing a chunked translation that leaves a torn tag unclosed")
	void shouldFailChunkedNewJobWhenATornTagIsNotReclosed() throws Exception {
		final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);
		final DocumentSplitter splitter = new DocumentSplitter(30);
		final Translator chunkedTranslator = new Translator(
			new LlmClient(mockModel), promptLoader, null, null, vocabulary, null,
			UnitPacker.Settings.defaults(), splitter
		);

		final String source =
			"<LS to=\"e\">\n\n" +
			"# Chapter One\n\n" +
			"English content for chapter one, padded well beyond the tiny target chunk size so" +
			" the splitter is forced to break this document into pieces for the test.\n\n" +
			"# Chapter Two\n\n" +
			"More English content for chapter two, also padded, before the language switch" +
			" finally closes below.\n\n</LS>\n";

		final TranslateNewJob job = new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/cs/doc.md"),
			Locale.forLanguageTag("cs"),
			source,
			"abc123",
			null,
			null
		);

		mockModel.addResponse(
			"<LS to=\"e\">\n\n# Kapitola jedna\n\nCesky text pro kapitolu jedna.\n\n", 100, 50
		);
		// chunk 2 translates the heading faithfully but drops the closing </LS>, as if the model
		// treated the dangling tag as an artifact rather than the other half of a legitimate
		// language-switch span
		mockModel.addResponse("# Kapitola dva\n\nDalsi cesky text pro kapitolu dva.\n\n", 100, 50);

		final TranslationResult result = chunkedTranslator.translate(job).toCompletableFuture().get();

		assertFalse(result.success(), "a translation with a never-closed <LS> must not be written out");
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
