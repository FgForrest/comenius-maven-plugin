package io.evitadb.comenius;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.evitadb.comenius.llm.PromptLoader;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import io.evitadb.comenius.model.TranslationSummary;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TranslationExecutor should execute translations in parallel")
public class TranslationExecutorTest {

	private Path tempDir;
	private Path sourceDir;
	private TranslationExecutor executor;
	private TestLog testLog;
	private MockChatModel mockModel;
	private Writer writer;

	@BeforeEach
	void setUp() throws Exception {
		tempDir = Files.createTempDirectory("executor-test-");
		sourceDir = tempDir.resolve("source");
		Files.createDirectories(sourceDir);

		testLog = new TestLog();
		mockModel = new MockChatModel();
		writer = new Writer();

		final Translator translator = new Translator(mockModel, new PromptLoader());
		executor = new TranslationExecutor(4, translator, writer, testLog, sourceDir);
	}

	@AfterEach
	void tearDown() throws Exception {
		executor.shutdown();
		deleteRecursively(tempDir);
	}

	@Test
	@DisplayName("shouldExecuteAllJobsSuccessfully")
	void shouldExecuteAllJobsSuccessfully() throws Exception {
		mockModel.setResponse("# Translated", 100, 50);

		final List<TranslationJob> jobs = createJobs(5);

		final TranslationSummary summary = executor.executeAll(jobs);

		assertEquals(5, summary.successCount());
		assertEquals(0, summary.failedCount());
	}

	@Test
	@DisplayName("shouldContinueOnIndividualFailure")
	void shouldContinueOnIndividualFailure() throws Exception {
		// Alternate between success and failure
		mockModel.setAlternatingBehavior(true);

		final List<TranslationJob> jobs = createJobs(4);

		final TranslationSummary summary = executor.executeAll(jobs);

		// Some should succeed, some should fail
		assertTrue(summary.successCount() > 0);
		assertTrue(summary.failedCount() > 0);
		assertEquals(4, summary.successCount() + summary.failedCount());
	}

	@Test
	@DisplayName("shouldReturnCorrectSummary")
	void shouldReturnCorrectSummary() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final List<TranslationJob> jobs = createJobs(3);

		final TranslationSummary summary = executor.executeAll(jobs);

		assertEquals(3, summary.successCount());
		assertEquals(0, summary.failedCount());
		assertEquals(300, summary.inputTokens());
		assertEquals(150, summary.outputTokens());
	}

	@Test
	@DisplayName("shouldWriteSuccessfulTranslations")
	void shouldWriteSuccessfulTranslations() throws Exception {
		mockModel.setResponse("# Translated Content", 100, 50);

		final Path targetFile = tempDir.resolve("target/doc.md");
		final TranslateNewJob job = new TranslateNewJob(
			sourceDir.resolve("doc.md"),
			targetFile,
			Locale.GERMAN,
			"# Original",
			"abc123",
			null,
			null
		);

		final TranslationSummary summary = executor.executeAll(List.of(job));

		assertEquals(1, summary.successCount());
		assertTrue(Files.exists(targetFile));
		final String content = Files.readString(targetFile);
		assertTrue(content.contains("# Translated Content"));
	}

	@Test
	@DisplayName("shouldAddCommitFieldToTranslation")
	void shouldAddCommitFieldToTranslation() throws Exception {
		mockModel.setResponse("# Inhalt", 100, 50);

		final Path targetFile = tempDir.resolve("target/doc.md");
		final String commitHash = "abc123def456789";
		final TranslateNewJob job = new TranslateNewJob(
			sourceDir.resolve("doc.md"),
			targetFile,
			Locale.GERMAN,
			"# Content",
			commitHash,
			null,
			null
		);

		executor.executeAll(List.of(job));

		final String content = Files.readString(targetFile);
		assertTrue(content.contains("commit:"));
		assertTrue(content.contains(commitHash));
	}

	@Test
	@DisplayName("shouldHandleEmptyJobList")
	void shouldHandleEmptyJobList() {
		final TranslationSummary summary = executor.executeAll(List.of());

		assertEquals(0, summary.successCount());
		assertEquals(0, summary.failedCount());
		assertEquals(0, summary.getTotalCount());
	}

	@Test
	@DisplayName("shouldLogTranslationResults")
	void shouldLogTranslationResults() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final TranslateNewJob job = new TranslateNewJob(
			sourceDir.resolve("log-test.md"),
			tempDir.resolve("target/log-test.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		executor.executeAll(List.of(job));

		// Check for progress bar format: "[====================] 100% [NEW] log-test.md -> German (de)"
		assertTrue(testLog.hasInfo("[NEW]"));
		assertTrue(testLog.hasInfo("log-test.md"));
		assertTrue(testLog.hasInfo("German"));
	}

	@Test
	@DisplayName("shouldLogFailures")
	void shouldLogFailures() throws Exception {
		mockModel.setException(new RuntimeException("API Error"));

		final TranslateNewJob job = new TranslateNewJob(
			sourceDir.resolve("fail.md"),
			tempDir.resolve("target/fail.md"),
			Locale.GERMAN,
			"content",
			"abc123",
			null,
			null
		);

		executor.executeAll(List.of(job));

		assertTrue(testLog.hasError("failed") || testLog.hasError("error"));
	}

	@Test
	@DisplayName("shouldShutdownExecutorGracefully")
	void shouldShutdownExecutorGracefully() throws Exception {
		mockModel.setResponse("translated", 100, 50);

		final List<TranslationJob> jobs = createJobs(2);
		executor.executeAll(jobs);

		// Should not throw
		executor.shutdown();
	}

	@Test
	@DisplayName("shouldRejectInvalidParallelism")
	void shouldRejectInvalidParallelism() {
		assertThrows(IllegalArgumentException.class, () ->
			new TranslationExecutor(0, new Translator(mockModel), writer, testLog, sourceDir)
		);

		assertThrows(IllegalArgumentException.class, () ->
			new TranslationExecutor(-1, new Translator(mockModel), writer, testLog, sourceDir)
		);
	}

	@Test
	@DisplayName("shouldRejectNullDependencies")
	void shouldRejectNullDependencies() {
		assertThrows(NullPointerException.class, () ->
			new TranslationExecutor(4, null, writer, testLog, sourceDir)
		);

		assertThrows(NullPointerException.class, () ->
			new TranslationExecutor(4, new Translator(mockModel), null, testLog, sourceDir)
		);

		assertThrows(NullPointerException.class, () ->
			new TranslationExecutor(4, new Translator(mockModel), writer, null, sourceDir)
		);

		assertThrows(NullPointerException.class, () ->
			new TranslationExecutor(4, new Translator(mockModel), writer, testLog, null)
		);
	}

	private List<TranslationJob> createJobs(int count) throws IOException {
		final List<TranslationJob> jobs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final Path sourceFile = sourceDir.resolve("doc" + i + ".md");
			Files.writeString(sourceFile, "# Content " + i);
			final Path targetFile = tempDir.resolve("target/doc" + i + ".md");

			jobs.add(new TranslateNewJob(
				sourceFile,
				targetFile,
				Locale.GERMAN,
				"# Content " + i,
				"commit" + i,
				null,
				null
			));
		}
		return jobs;
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.exists(path)) {
			Files.walk(path)
				.sorted(Comparator.reverseOrder())
				.forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						// ignore
					}
				});
		}
	}

	private static class MockChatModel implements ChatModel {
		private String responseText = "default response";
		private TokenUsage tokenUsage = new TokenUsage(0, 0);
		private RuntimeException exception = null;
		private boolean alternating = false;
		private final AtomicInteger callCount = new AtomicInteger(0);

		void setResponse(String text, int inputTokens, int outputTokens) {
			this.responseText = text;
			this.tokenUsage = new TokenUsage(inputTokens, outputTokens);
			this.exception = null;
			this.alternating = false;
		}

		void setException(RuntimeException e) {
			this.exception = e;
			this.alternating = false;
		}

		void setAlternatingBehavior(boolean alternating) {
			this.alternating = alternating;
			this.exception = null;
		}

		@Override
		public ChatResponse chat(List<ChatMessage> messages) {
			final int count = callCount.incrementAndGet();

			if (alternating && count % 2 == 0) {
				throw new RuntimeException("Alternating failure");
			}

			if (exception != null) {
				throw exception;
			}

			return ChatResponse.builder()
				.aiMessage(AiMessage.from(responseText))
				.tokenUsage(tokenUsage)
				.build();
		}
	}

	private static class TestLog implements Log {
		private final List<String> infos = Collections.synchronizedList(new ArrayList<>());
		private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

		@Override public boolean isDebugEnabled() { return true; }
		@Override public void debug(CharSequence content) {}
		@Override public void debug(CharSequence content, Throwable error) {}
		@Override public void debug(Throwable error) {}
		@Override public boolean isInfoEnabled() { return true; }
		@Override public void info(CharSequence content) { infos.add(content.toString()); }
		@Override public void info(CharSequence content, Throwable error) { infos.add(content.toString()); }
		@Override public void info(Throwable error) {}
		@Override public boolean isWarnEnabled() { return true; }
		@Override public void warn(CharSequence content) {}
		@Override public void warn(CharSequence content, Throwable error) {}
		@Override public void warn(Throwable error) {}
		@Override public boolean isErrorEnabled() { return true; }
		@Override public void error(CharSequence content) { errors.add(content.toString()); }
		@Override public void error(CharSequence content, Throwable error) { errors.add(content.toString()); }
		@Override public void error(Throwable error) {}

		boolean hasInfo(String substring) {
			return infos.stream().anyMatch(s -> s.toLowerCase().contains(substring.toLowerCase()));
		}

		boolean hasError(String substring) {
			return errors.stream().anyMatch(s -> s.toLowerCase().contains(substring.toLowerCase()));
		}
	}
}
