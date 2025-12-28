package io.evitadb.comenius;

import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.TranslateIncrementalJob;
import io.evitadb.comenius.model.TranslateNewJob;
import io.evitadb.comenius.model.TranslationJob;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TranslationOrchestrator should coordinate translation workflow")
public class TranslationOrchestratorTest {

	private Path tempDir;
	private Path sourceDir;
	private Path targetDir;
	private GitService gitService;
	private TranslationOrchestrator orchestrator;
	private TestLog testLog;

	@BeforeEach
	void setUp() throws Exception {
		tempDir = Files.createTempDirectory("orchestrator-test-");
		sourceDir = tempDir.resolve("source");
		targetDir = tempDir.resolve("target");
		Files.createDirectories(sourceDir);
		Files.createDirectories(targetDir);

		initGitRepo();
		gitService = new GitService(tempDir);
		testLog = new TestLog();
		orchestrator = new TranslationOrchestrator(gitService, sourceDir, testLog);
	}

	@AfterEach
	void tearDown() throws Exception {
		deleteRecursively(tempDir);
	}

	private void initGitRepo() throws Exception {
		runGit("init");
		runGit("config", "user.email", "test@example.com");
		runGit("config", "user.name", "Test User");
	}

	private void runGit(String... args) throws Exception {
		final ProcessBuilder pb = new ProcessBuilder();
		pb.directory(tempDir.toFile());
		pb.command("git");
		for (String arg : args) {
			pb.command().add(arg);
		}
		final Process process = pb.start();
		final int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IOException("Git command failed: " + String.join(" ", args));
		}
	}

	@Test
	@DisplayName("shouldCreateNewJobWhenNoTargetExists")
	void shouldCreateNewJobWhenNoTargetExists() throws Exception {
		final Path sourceFile = sourceDir.resolve("new-doc.md");
		Files.writeString(sourceFile, "# Hello\n\nContent here.");
		runGit("add", "source/new-doc.md");
		runGit("commit", "-m", "Add source file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		assertInstanceOf(TranslateNewJob.class, jobOpt.get());
		assertEquals(targetDir.resolve("new-doc.md"), jobOpt.get().getTargetFile());
	}

	@Test
	@DisplayName("shouldCreateIncrementalJobWhenTargetOutdated")
	void shouldCreateIncrementalJobWhenTargetOutdated() throws Exception {
		// Create and commit source file
		final Path sourceFile = sourceDir.resolve("update-doc.md");
		Files.writeString(sourceFile, "# Original\n\nOriginal content.");
		runGit("add", "source/update-doc.md");
		runGit("commit", "-m", "Add source file");
		final String oldCommit = gitService.getCurrentCommitHash(sourceFile).orElseThrow();

		// Create target with old commit in front matter
		final Path translatedFile = targetDir.resolve("update-doc.md");
		Files.writeString(translatedFile, "---\ntitle: Test\ncommit: '" + oldCommit + "'\n---\n\n# Urspruenglich\n\nUrspruenglicher Inhalt.");

		// Update source file
		Files.writeString(sourceFile, "# Updated\n\nUpdated content.");
		runGit("add", "source/update-doc.md");
		runGit("commit", "-m", "Update source file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		assertInstanceOf(TranslateIncrementalJob.class, jobOpt.get());

		final TranslateIncrementalJob job = (TranslateIncrementalJob) jobOpt.get();
		assertEquals(1, job.getCommitCount());
		assertNotNull(job.getDiff());
		assertEquals(oldCommit, job.getTranslatedCommit());
	}

	@Test
	@DisplayName("shouldReturnEmptyWhenTargetUpToDate")
	void shouldReturnEmptyWhenTargetUpToDate() throws Exception {
		final Path sourceFile = sourceDir.resolve("up-to-date.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/up-to-date.md");
		runGit("commit", "-m", "Add file");
		final String currentCommit = gitService.getCurrentCommitHash(sourceFile).orElseThrow();

		// Target has same commit
		final Path translatedFile = targetDir.resolve("up-to-date.md");
		Files.writeString(translatedFile, "---\ncommit: '" + currentCommit + "'\n---\n\n# Inhalt");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isEmpty());
	}

	@Test
	@DisplayName("shouldSkipWhenFileHasUncommittedChanges")
	void shouldSkipWhenFileHasUncommittedChanges() throws Exception {
		final Path sourceFile = sourceDir.resolve("uncommitted.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/uncommitted.md");
		runGit("commit", "-m", "Add file");

		// Modify without committing
		Files.writeString(sourceFile, "# Modified Content");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isEmpty());
		assertTrue(testLog.hasError("uncommitted changes"));
	}

	@Test
	@DisplayName("shouldSkipWhenFileIsUntracked")
	void shouldSkipWhenFileIsUntracked() throws Exception {
		final Path sourceFile = sourceDir.resolve("untracked.md");
		Files.writeString(sourceFile, "# Content");
		// Not added to git

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isEmpty());
		assertTrue(testLog.hasError("untracked file") || testLog.hasError("uncommitted changes"));
	}

	@Test
	@DisplayName("shouldCalculateCorrectCommitCount")
	void shouldCalculateCorrectCommitCount() throws Exception {
		final Path sourceFile = sourceDir.resolve("multi-commit.md");
		Files.writeString(sourceFile, "v1");
		runGit("add", "source/multi-commit.md");
		runGit("commit", "-m", "v1");
		final String firstCommit = gitService.getCurrentCommitHash(sourceFile).orElseThrow();

		// Create target
		final Path translatedFile = targetDir.resolve("multi-commit.md");
		Files.writeString(translatedFile, "---\ncommit: '" + firstCommit + "'\n---\n\nv1-de");

		// Make 3 more commits
		for (int i = 2; i <= 4; i++) {
			Files.writeString(sourceFile, "v" + i);
			runGit("add", "source/multi-commit.md");
			runGit("commit", "-m", "v" + i);
		}

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		final TranslateIncrementalJob job = (TranslateIncrementalJob) jobOpt.get();
		assertEquals(3, job.getCommitCount());
	}

	@Test
	@DisplayName("shouldLoadAndCombineInstructionFiles")
	void shouldLoadAndCombineInstructionFiles() throws Exception {
		final Path sourceFile = sourceDir.resolve("with-instructions.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/with-instructions.md");
		runGit("commit", "-m", "Add file");

		// Create instruction files
		final Path instr1 = tempDir.resolve("instr1.txt");
		final Path instr2 = tempDir.resolve("instr2.txt");
		Files.writeString(instr1, "Instruction 1");
		Files.writeString(instr2, "Instruction 2");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			List.of(instr1, instr2)
		);

		assertTrue(jobOpt.isPresent());
		final String instructions = jobOpt.get().getInstructions();
		assertNotNull(instructions);
		assertTrue(instructions.contains("Instruction 1"));
		assertTrue(instructions.contains("Instruction 2"));
	}

	@Test
	@DisplayName("shouldHandleEmptyInstructionFiles")
	void shouldHandleEmptyInstructionFiles() throws Exception {
		final Path sourceFile = sourceDir.resolve("no-instructions.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/no-instructions.md");
		runGit("commit", "-m", "Add file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		assertNull(jobOpt.get().getInstructions());
	}

	@Test
	@DisplayName("shouldCalculateCorrectTargetPath")
	void shouldCalculateCorrectTargetPath() throws Exception {
		final Path nestedDir = sourceDir.resolve("a/b/c");
		Files.createDirectories(nestedDir);
		final Path sourceFile = nestedDir.resolve("nested.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/a/b/c/nested.md");
		runGit("commit", "-m", "Add nested file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		assertEquals(targetDir.resolve("a/b/c/nested.md"), jobOpt.get().getTargetFile());
	}

	@Test
	@DisplayName("shouldTreatAsNewWhenTargetHasNoCommitField")
	void shouldTreatAsNewWhenTargetHasNoCommitField() throws Exception {
		final Path sourceFile = sourceDir.resolve("no-commit-field.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/no-commit-field.md");
		runGit("commit", "-m", "Add file");

		// Target without commit field
		final Path translatedFile = targetDir.resolve("no-commit-field.md");
		Files.writeString(translatedFile, "---\ntitle: Test\n---\n\n# Inhalt");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		assertInstanceOf(TranslateNewJob.class, jobOpt.get());
		assertTrue(testLog.hasWarning("no commit field"));
	}

	@Test
	@DisplayName("shouldReportNewJob")
	void shouldReportNewJob() throws Exception {
		final Path sourceFile = sourceDir.resolve("report-new.md");
		Files.writeString(sourceFile, "# Content");
		runGit("add", "source/report-new.md");
		runGit("commit", "-m", "Add file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		orchestrator.reportJob(jobOpt.get(), Path.of("report-new.md"));
		assertTrue(testLog.hasInfo("[NEW]"));
	}

	@Test
	@DisplayName("shouldReportUpdateJob")
	void shouldReportUpdateJob() throws Exception {
		final Path sourceFile = sourceDir.resolve("report-update.md");
		Files.writeString(sourceFile, "# Original");
		runGit("add", "source/report-update.md");
		runGit("commit", "-m", "Add file");
		final String oldCommit = gitService.getCurrentCommitHash(sourceFile).orElseThrow();

		final Path translatedFile = targetDir.resolve("report-update.md");
		Files.writeString(translatedFile, "---\ncommit: '" + oldCommit + "'\n---\n\n# Original-de");

		Files.writeString(sourceFile, "# Updated");
		runGit("add", "source/report-update.md");
		runGit("commit", "-m", "Update file");

		final Optional<TranslationJob> jobOpt = orchestrator.createJob(
			sourceFile,
			Files.readString(sourceFile),
			targetDir,
			Locale.GERMAN,
			Collections.emptyList()
		);

		assertTrue(jobOpt.isPresent());
		orchestrator.reportJob(jobOpt.get(), Path.of("report-update.md"));
		assertTrue(testLog.hasInfo("[UPDATE]"));
	}

	@Test
	@DisplayName("shouldReportUpToDate")
	void shouldReportUpToDate() {
		orchestrator.reportUpToDate(Path.of("up-to-date.md"));
		assertTrue(testLog.hasInfo("[SKIP]"));
		assertTrue(testLog.hasInfo("up to date"));
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

	/**
	 * Test log implementation that captures log messages.
	 */
	private static class TestLog implements Log {
		private final List<String> infos = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();
		private final List<String> errors = new ArrayList<>();

		@Override public boolean isDebugEnabled() { return true; }
		@Override public void debug(CharSequence content) {}
		@Override public void debug(CharSequence content, Throwable error) {}
		@Override public void debug(Throwable error) {}
		@Override public boolean isInfoEnabled() { return true; }
		@Override public void info(CharSequence content) { infos.add(content.toString()); }
		@Override public void info(CharSequence content, Throwable error) { infos.add(content.toString()); }
		@Override public void info(Throwable error) {}
		@Override public boolean isWarnEnabled() { return true; }
		@Override public void warn(CharSequence content) { warnings.add(content.toString()); }
		@Override public void warn(CharSequence content, Throwable error) { warnings.add(content.toString()); }
		@Override public void warn(Throwable error) {}
		@Override public boolean isErrorEnabled() { return true; }
		@Override public void error(CharSequence content) { errors.add(content.toString()); }
		@Override public void error(CharSequence content, Throwable error) { errors.add(content.toString()); }
		@Override public void error(Throwable error) {}

		boolean hasInfo(String substring) {
			return infos.stream().anyMatch(s -> s.toLowerCase().contains(substring.toLowerCase()));
		}

		boolean hasWarning(String substring) {
			return warnings.stream().anyMatch(s -> s.toLowerCase().contains(substring.toLowerCase()));
		}

		boolean hasError(String substring) {
			return errors.stream().anyMatch(s -> s.toLowerCase().contains(substring.toLowerCase()));
		}
	}
}
