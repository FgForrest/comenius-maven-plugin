package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PhaseResult intermediate translation result")
public class PhaseResultTest {

	@Test
	@DisplayName("creates initial result with empty fields and no body")
	void shouldCreateInitialResultWithEmptyFields() {
		final TranslateNewJob job = createTestJob();
		final PhaseResult result = PhaseResult.initial(job);

		assertSame(job, result.job());
		assertTrue(result.translatedFields().isEmpty());
		assertNull(result.translatedBody());
		assertEquals(0, result.inputTokens());
		assertEquals(0, result.outputTokens());
		assertEquals(0, result.elapsedMillis());
		assertTrue(result.success());
		assertNull(result.errorMessage());
	}

	@Test
	@DisplayName("accumulates tokens across front matter and body phases")
	void shouldAccumulateTokensAcrossPhases() {
		final TranslateNewJob job = createTestJob();
		final PhaseResult initial = PhaseResult.initial(job);

		// Phase 1: Front matter
		final Map<String, String> fields = Map.of("title", "Titel");
		final PhaseResult afterFrontMatter = initial.withFrontMatter(fields, 100, 50, 1000);

		assertEquals(100, afterFrontMatter.inputTokens());
		assertEquals(50, afterFrontMatter.outputTokens());
		assertEquals(1000, afterFrontMatter.elapsedMillis());

		// Phase 2: Body
		final PhaseResult afterBody = afterFrontMatter.withBody("Translated body", 200, 150, 2000);

		assertEquals(300, afterBody.inputTokens());
		assertEquals(200, afterBody.outputTokens());
		assertEquals(3000, afterBody.elapsedMillis());
	}

	@Test
	@DisplayName("formats combined content with field tags and body")
	void shouldFormatCombinedContentCorrectly() {
		final TranslateNewJob job = createTestJob();
		final Map<String, String> fields = new LinkedHashMap<>();
		fields.put("title", "Der Titel");
		fields.put("perex", "Die Beschreibung");

		final PhaseResult result = PhaseResult.initial(job)
			.withFrontMatter(fields, 100, 50, 1000)
			.withBody("# Translated Body\n\nSome content.", 200, 150, 2000);

		final TranslationResult translationResult = result.toTranslationResult();

		assertTrue(translationResult.success());
		final String content = translationResult.translatedContent();

		// Verify field blocks are present
		assertTrue(content.contains("[[title]]"));
		assertTrue(content.contains("Der Titel"));
		assertTrue(content.contains("[[/title]]"));
		assertTrue(content.contains("[[perex]]"));
		assertTrue(content.contains("Die Beschreibung"));
		assertTrue(content.contains("[[/perex]]"));

		// Verify body is present
		assertTrue(content.contains("# Translated Body"));
		assertTrue(content.contains("Some content."));

		// Verify tokens are aggregated
		assertEquals(300, translationResult.inputTokens());
		assertEquals(200, translationResult.outputTokens());
	}

	@Test
	@DisplayName("formats combined content with body only when no fields present")
	void shouldFormatCombinedContentWhenNoFieldsPresent() {
		final TranslateNewJob job = createTestJob();

		final PhaseResult result = PhaseResult.initial(job)
			.withBody("# Just Body Content", 200, 150, 2000);

		final TranslationResult translationResult = result.toTranslationResult();

		assertTrue(translationResult.success());
		final String content = translationResult.translatedContent();

		// Verify no field blocks
		assertFalse(content.contains("[["));
		assertFalse(content.contains("]]"));

		// Verify body is present
		assertEquals("# Just Body Content", content);
	}

	@Test
	@DisplayName("propagates failure with phase prefix for front matter")
	void shouldPropagateFailureWithFrontMatterPrefix() {
		final TranslateNewJob job = createTestJob();
		final PhaseResult initial = PhaseResult.initial(job);

		final PhaseResult failed = initial.withFailure("FRONT_MATTER", "Connection timeout", 500);

		assertFalse(failed.success());
		assertEquals("[FRONT_MATTER] Connection timeout", failed.errorMessage());
		assertEquals(500, failed.elapsedMillis());

		final TranslationResult translationResult = failed.toTranslationResult();
		assertFalse(translationResult.success());
		assertEquals("[FRONT_MATTER] Connection timeout", translationResult.errorMessage());
	}

	@Test
	@DisplayName("propagates failure with phase prefix for body")
	void shouldPropagateFailureWithBodyPrefix() {
		final TranslateNewJob job = createTestJob();
		final Map<String, String> fields = Map.of("title", "Titel");

		final PhaseResult afterFrontMatter = PhaseResult.initial(job)
			.withFrontMatter(fields, 100, 50, 1000);

		final PhaseResult failed = afterFrontMatter.withFailure("BODY", "Rate limit exceeded", 500);

		assertFalse(failed.success());
		assertEquals("[BODY] Rate limit exceeded", failed.errorMessage());
		// Elapsed time should accumulate
		assertEquals(1500, failed.elapsedMillis());
		// Tokens from successful phase should be preserved (in elapsedMillis, not tokens since failure)
		assertEquals(100, failed.inputTokens());
		assertEquals(50, failed.outputTokens());
	}

	@Test
	@DisplayName("preserves field order in combined content")
	void shouldPreserveFieldOrderInCombinedContent() {
		final TranslateNewJob job = createTestJob();
		final Map<String, String> fields = new LinkedHashMap<>();
		fields.put("title", "First");
		fields.put("perex", "Second");
		fields.put("description", "Third");

		final PhaseResult result = PhaseResult.initial(job)
			.withFrontMatter(fields, 100, 50, 1000)
			.withBody("Body", 200, 150, 2000);

		final TranslationResult translationResult = result.toTranslationResult();
		final String content = translationResult.translatedContent();

		// Verify order
		final int titleIdx = content.indexOf("[[title]]");
		final int perexIdx = content.indexOf("[[perex]]");
		final int descIdx = content.indexOf("[[description]]");
		final int bodyIdx = content.indexOf("Body");

		assertTrue(titleIdx < perexIdx, "title should come before perex");
		assertTrue(perexIdx < descIdx, "perex should come before description");
		assertTrue(descIdx < bodyIdx, "description should come before body");
	}

	@Test
	@DisplayName("throws on null job for initial")
	void shouldThrowOnNullJobForInitial() {
		assertThrows(NullPointerException.class, () -> PhaseResult.initial(null));
	}

	@Test
	@DisplayName("throws on null fields for withFrontMatter")
	void shouldThrowOnNullFieldsForWithFrontMatter() {
		final TranslateNewJob job = createTestJob();
		final PhaseResult initial = PhaseResult.initial(job);

		assertThrows(NullPointerException.class,
			() -> initial.withFrontMatter(null, 100, 50, 1000));
	}

	@Test
	@DisplayName("throws on null body for withBody")
	void shouldThrowOnNullBodyForWithBody() {
		final TranslateNewJob job = createTestJob();
		final PhaseResult initial = PhaseResult.initial(job);

		assertThrows(NullPointerException.class,
			() -> initial.withBody(null, 100, 50, 1000));
	}

	private TranslateNewJob createTestJob() {
		return new TranslateNewJob(
			Path.of("/source/doc.md"),
			Path.of("/target/de/doc.md"),
			Locale.GERMAN,
			"---\ntitle: Test\n---\n# Content",
			"abc123",
			null,
			null
		);
	}
}
