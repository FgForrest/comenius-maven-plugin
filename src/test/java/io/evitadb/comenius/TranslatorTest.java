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
	@DisplayName("shouldTranslateIncrementalJobSuccessfully")
	void shouldTranslateIncrementalJobSuccessfully() throws Exception {
		// For incremental jobs, the LLM returns a unified diff that transforms
		// the existing translation body to the new translation
		final String diffResponse = """
			--- a/translation
			+++ b/translation
			@@ -1 +1 @@
			-# Urspruenglicher Inhalt
			+# Aktualisierter Inhalt
			""";
		mockModel.setResponse(diffResponse, 200, 100);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Updated Content",
			"def456",
			null,
			null,
			"# Original Content",
			"# Urspruenglicher Inhalt",
			"@@ -1 +1 @@\n-Original\n+Updated",
			"abc123",
			2
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals("# Aktualisierter Inhalt", result.translatedContent());
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
		// For incremental jobs, the body LLM response is a unified diff
		final String diffResponse = """
			--- a/translation
			+++ b/translation
			@@ -1 +1 @@
			-# Urspruenglicher Inhalt
			+# Aktualisierter Inhalt
			""";
		mockModel.addResponse(diffResponse, 120, 60);

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
			"@@ -1 +1 @@\n-Old Title\n+New Title",
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Two calls should have been made (front matter + body)
		assertEquals(2, mockModel.getCallCount());
		// Result should contain both fields
		final String content = result.translatedContent();
		assertTrue(content.contains("[[title]]"));
		assertTrue(content.contains("[[perex]]"));
	}

	// ============== Diff-Based Incremental Translation Tests ==============

	@Test
	@DisplayName("shouldHandleEmptyDiffResponseCorrectly")
	void shouldHandleEmptyDiffResponseCorrectly() throws Exception {
		// Empty diff means no changes needed - return existing translation
		mockModel.setResponse("", 100, 10);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Same Content",
			"def456",
			null,
			null,
			"# Same Content",
			"# Gleicher Inhalt",
			"",  // No diff
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals("# Gleicher Inhalt", result.translatedContent());
	}

	@Test
	@DisplayName("shouldRetryWithCorrectionPromptOnInvalidDiff")
	void shouldRetryWithCorrectionPromptOnInvalidDiff() throws Exception {
		// First response is invalid diff
		mockModel.addResponse("This is not a valid diff", 100, 50);
		// Second response (retry) is valid diff
		final String validDiff = """
			--- a/translation
			+++ b/translation
			@@ -1 +1 @@
			-# Urspruenglicher Inhalt
			+# Korrigierter Inhalt
			""";
		mockModel.addResponse(validDiff, 150, 75);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Fixed Content",
			"def456",
			null,
			null,
			"# Original Content",
			"# Urspruenglicher Inhalt",
			"@@ -1 +1 @@\n-Original\n+Fixed",
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		// Should have made 2 calls (initial + retry)
		assertEquals(2, mockModel.getCallCount());
		assertEquals("# Korrigierter Inhalt", result.translatedContent());
		// Token counts should include both attempts
		assertEquals(250, result.inputTokens());
		assertEquals(125, result.outputTokens());
	}

	@Test
	@DisplayName("shouldFailJobAfterSecondInvalidDiff")
	void shouldFailJobAfterSecondInvalidDiff() throws Exception {
		// Both attempts return invalid diffs
		mockModel.addResponse("Invalid diff 1", 100, 50);
		mockModel.addResponse("Invalid diff 2", 100, 50);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Updated Content",
			"def456",
			null,
			null,
			"# Original Content",
			"# Urspruenglicher Inhalt",
			"@@ -1 +1 @@\n-Original\n+Updated",
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertFalse(result.success());
		// Should have made 2 calls (initial + retry)
		assertEquals(2, mockModel.getCallCount());
		assertTrue(result.errorMessage().contains("BODY_DIFF"));
		assertTrue(result.errorMessage().contains("failed after retry"));
	}

	@Test
	@DisplayName("shouldApplyValidDiffToExistingTranslation")
	void shouldApplyValidDiffToExistingTranslation() throws Exception {
		// Multi-line diff example
		final String validDiff = """
			--- a/translation
			+++ b/translation
			@@ -1,3 +1,3 @@
			 # Titel

			-Alter Absatz.
			+Neuer Absatz mit mehr Text.
			""";
		mockModel.setResponse(validDiff, 200, 100);

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"# Title\n\nNew paragraph with more text.",
			"def456",
			null,
			null,
			"# Title\n\nOld paragraph.",
			"# Titel\n\nAlter Absatz.",
			"@@ -3 +3 @@\n-Old paragraph.\n+New paragraph with more text.",
			"abc123",
			1
		);

		final CompletionStage<TranslationResult> stage = translator.translate(job);
		final TranslationResult result = stage.toCompletableFuture().get();

		assertTrue(result.success());
		assertEquals("# Titel\n\nNeuer Absatz mit mehr Text.", result.translatedContent());
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
