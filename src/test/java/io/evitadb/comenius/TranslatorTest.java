package io.evitadb.comenius;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
		promptLoader = new PromptLoader();
		translator = new Translator(mockModel, promptLoader);
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
		mockModel.setResponse("# Aktualisierter Inhalt", 200, 100);

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

	/**
	 * Simple mock implementation of ChatModel for testing.
	 */
	private static class MockChatModel implements ChatModel {
		private String responseText = "default response";
		private TokenUsage tokenUsage = new TokenUsage(0, 0);
		private RuntimeException exception = null;

		void setResponse(String text, int inputTokens, int outputTokens) {
			this.responseText = text;
			this.tokenUsage = new TokenUsage(inputTokens, outputTokens);
			this.exception = null;
		}

		void setResponse(String text, TokenUsage usage) {
			this.responseText = text;
			this.tokenUsage = usage;
			this.exception = null;
		}

		void setException(RuntimeException e) {
			this.exception = e;
		}

		@Override
		public ChatResponse chat(List<ChatMessage> messages) {
			if (exception != null) {
				throw exception;
			}
			return ChatResponse.builder()
				.aiMessage(AiMessage.from(responseText))
				.tokenUsage(tokenUsage)
				.build();
		}
	}
}
