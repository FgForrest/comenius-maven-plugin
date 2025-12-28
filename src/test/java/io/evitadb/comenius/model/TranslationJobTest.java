package io.evitadb.comenius.model;

import io.evitadb.comenius.llm.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TranslationJob hierarchy should build prompts polymorphically")
public class TranslationJobTest {

	private PromptLoader promptLoader;
	private static final Path SOURCE_FILE = Path.of("/source/doc.md");
	private static final Path TARGET_FILE = Path.of("/target/de/doc.md");
	private static final Locale GERMAN = Locale.GERMAN;
	private static final String SOURCE_CONTENT = "# Hello World\n\nThis is content.";
	private static final String CURRENT_COMMIT = "abc1234567890";
	private static final String INSTRUCTIONS = "Preserve all code blocks.";

	@BeforeEach
	void setUp() {
		promptLoader = new PromptLoader();
	}

	@Test
	@DisplayName("shouldBuildSystemPromptForNewJob")
	void shouldBuildSystemPromptForNewJob() {
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS
		);

		final String systemPrompt = job.buildSystemPrompt(promptLoader);

		assertNotNull(systemPrompt);
		assertTrue(systemPrompt.contains("German"), "Should contain locale display name");
		assertTrue(systemPrompt.contains("de"), "Should contain locale tag");
	}

	@Test
	@DisplayName("shouldBuildUserPromptForNewJob")
	void shouldBuildUserPromptForNewJob() {
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS
		);

		final String userPrompt = job.buildUserPrompt(promptLoader);

		assertNotNull(userPrompt);
		assertTrue(userPrompt.contains(SOURCE_CONTENT), "Should contain source content");
		assertTrue(userPrompt.contains(INSTRUCTIONS), "Should contain custom instructions");
	}

	@Test
	@DisplayName("shouldBuildSystemPromptForIncrementalJob")
	void shouldBuildSystemPromptForIncrementalJob() {
		final TranslateIncrementalJob job = createIncrementalJob();

		final String systemPrompt = job.buildSystemPrompt(promptLoader);

		assertNotNull(systemPrompt);
		assertTrue(systemPrompt.contains("German"), "Should contain locale display name");
	}

	@Test
	@DisplayName("shouldBuildUserPromptWithDiff")
	void shouldBuildUserPromptWithDiff() {
		final String originalSource = "# Hello\n\nOriginal content.";
		final String existingTranslation = "# Hallo\n\nOriginaler Inhalt.";
		final String diff = "@@ -1,2 +1,2 @@\n-Original\n+Modified";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS,
			originalSource, existingTranslation, diff, "oldcommit123", 3
		);

		final String userPrompt = job.buildUserPrompt(promptLoader);

		assertNotNull(userPrompt);
		assertTrue(userPrompt.contains(originalSource), "Should contain original source");
		assertTrue(userPrompt.contains(existingTranslation), "Should contain existing translation");
		assertTrue(userPrompt.contains(diff), "Should contain diff");
	}

	@Test
	@DisplayName("shouldIncludeCustomInstructions")
	void shouldIncludeCustomInstructions() {
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS
		);

		final String userPrompt = job.buildUserPrompt(promptLoader);

		assertTrue(userPrompt.contains(INSTRUCTIONS), "User prompt should include custom instructions");
	}

	@Test
	@DisplayName("shouldHandleNullInstructions")
	void shouldHandleNullInstructions() {
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, null
		);

		// Should not throw
		final String userPrompt = job.buildUserPrompt(promptLoader);
		assertNotNull(userPrompt);
	}

	@Test
	@DisplayName("shouldIncludeLocaleInPrompt")
	void shouldIncludeLocaleInPrompt() {
		final Locale french = Locale.FRENCH;
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, french, SOURCE_CONTENT, CURRENT_COMMIT, null
		);

		final String systemPrompt = job.buildSystemPrompt(promptLoader);

		assertTrue(systemPrompt.contains("French") || systemPrompt.contains("french"),
			"Should contain French locale");
		assertTrue(systemPrompt.contains("fr"), "Should contain French locale tag");
	}

	@Test
	@DisplayName("shouldReturnCorrectJobType")
	void shouldReturnCorrectJobType() {
		final TranslateNewJob newJob = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, null
		);
		assertEquals("NEW", newJob.getType());

		final TranslateIncrementalJob incrementalJob = createIncrementalJob();
		assertEquals("UPDATE", incrementalJob.getType());
	}

	@Test
	@DisplayName("shouldReturnShortCommitHashes")
	void shouldReturnShortCommitHashes() {
		final String longCurrentCommit = "1234567890abcdef1234567890abcdef12345678";
		final String longTranslatedCommit = "abcdef1234567890abcdef1234567890abcdef12";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, longCurrentCommit, null,
			"original", "translated", "diff", longTranslatedCommit, 5
		);

		assertEquals("1234567", job.getCurrentCommitShort());
		assertEquals("abcdef1", job.getTranslatedCommitShort());
	}

	@Test
	@DisplayName("shouldHandleShortCommitHashes")
	void shouldHandleShortCommitHashes() {
		final String shortCommit = "abc";

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, shortCommit, null,
			"original", "translated", "diff", "xyz", 1
		);

		assertEquals("abc", job.getCurrentCommitShort());
		assertEquals("xyz", job.getTranslatedCommitShort());
	}

	@Test
	@DisplayName("shouldReturnCorrectGetters")
	void shouldReturnCorrectGetters() {
		final TranslateNewJob job = new TranslateNewJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS
		);

		assertEquals(SOURCE_FILE, job.getSourceFile());
		assertEquals(TARGET_FILE, job.getTargetFile());
		assertEquals(GERMAN, job.getLocale());
		assertEquals(SOURCE_CONTENT, job.getSourceContent());
		assertEquals(CURRENT_COMMIT, job.getCurrentCommit());
		assertEquals(INSTRUCTIONS, job.getInstructions());
	}

	@Test
	@DisplayName("shouldReturnIncrementalJobGetters")
	void shouldReturnIncrementalJobGetters() {
		final String originalSource = "original";
		final String existingTranslation = "translated";
		final String diff = "diff content";
		final String translatedCommit = "oldcommit123";
		final int commitCount = 5;

		final TranslateIncrementalJob job = new TranslateIncrementalJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, null,
			originalSource, existingTranslation, diff, translatedCommit, commitCount
		);

		assertEquals(originalSource, job.getOriginalSource());
		assertEquals(existingTranslation, job.getExistingTranslation());
		assertEquals(diff, job.getDiff());
		assertEquals(translatedCommit, job.getTranslatedCommit());
		assertEquals(commitCount, job.getCommitCount());
	}

	private TranslateIncrementalJob createIncrementalJob() {
		return new TranslateIncrementalJob(
			SOURCE_FILE, TARGET_FILE, GERMAN, SOURCE_CONTENT, CURRENT_COMMIT, INSTRUCTIONS,
			"original source", "existing translation", "diff", "oldcommit", 2
		);
	}
}
