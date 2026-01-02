package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TranslationResult should correctly represent translation outcomes")
public class TranslationResultTest {

	private static final TranslateNewJob SAMPLE_JOB = new TranslateNewJob(
		Path.of("/source/doc.md"),
		Path.of("/target/de/doc.md"),
		Locale.GERMAN,
		"# Content",
		"abc123",
		null,
		null
	);

	@Test
	@DisplayName("shouldCreateSuccessResult")
	void shouldCreateSuccessResult() {
		final String translated = "# Inhalt";
		final long inputTokens = 100;
		final long outputTokens = 50;

		final TranslationResult result = TranslationResult.success(
			SAMPLE_JOB, translated, inputTokens, outputTokens
		);

		assertTrue(result.success());
		assertEquals(translated, result.translatedContent());
		assertNull(result.errorMessage());
		assertEquals(inputTokens, result.inputTokens());
		assertEquals(outputTokens, result.outputTokens());
		assertEquals(SAMPLE_JOB, result.job());
	}

	@Test
	@DisplayName("shouldCreateFailureResult")
	void shouldCreateFailureResult() {
		final String errorMessage = "API timeout";

		final TranslationResult result = TranslationResult.failure(SAMPLE_JOB, errorMessage);

		assertFalse(result.success());
		assertNull(result.translatedContent());
		assertEquals(errorMessage, result.errorMessage());
		assertEquals(0, result.inputTokens());
		assertEquals(0, result.outputTokens());
		assertEquals(SAMPLE_JOB, result.job());
	}

	@Test
	@DisplayName("shouldReturnJobType")
	void shouldReturnJobType() {
		final TranslationResult newResult = TranslationResult.success(
			SAMPLE_JOB, "content", 0, 0
		);
		assertEquals("NEW", newResult.getType());

		final TranslateIncrementalJob incrementalJob = new TranslateIncrementalJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"current",
			"abc123",
			null,
			null,
			"original",
			"translated",
			"diff",
			"old123",
			3
		);
		final TranslationResult updateResult = TranslationResult.success(
			incrementalJob, "content", 0, 0
		);
		assertEquals("UPDATE", updateResult.getType());
	}

	@Test
	@DisplayName("shouldRecordTokensCorrectly")
	void shouldRecordTokensCorrectly() {
		final long inputTokens = 12345;
		final long outputTokens = 6789;

		final TranslationResult result = TranslationResult.success(
			SAMPLE_JOB, "translated", inputTokens, outputTokens
		);

		assertEquals(inputTokens, result.inputTokens());
		assertEquals(outputTokens, result.outputTokens());
	}

	@Test
	@DisplayName("shouldHandleZeroTokens")
	void shouldHandleZeroTokens() {
		final TranslationResult result = TranslationResult.success(
			SAMPLE_JOB, "translated", 0, 0
		);

		assertEquals(0, result.inputTokens());
		assertEquals(0, result.outputTokens());
	}

	@Test
	@DisplayName("shouldPreserveOriginalJob")
	void shouldPreserveOriginalJob() {
		final TranslationResult successResult = TranslationResult.success(
			SAMPLE_JOB, "content", 100, 50
		);
		final TranslationResult failureResult = TranslationResult.failure(
			SAMPLE_JOB, "error"
		);

		assertSame(SAMPLE_JOB, successResult.job());
		assertSame(SAMPLE_JOB, failureResult.job());
	}
}
