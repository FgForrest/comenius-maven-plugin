package io.evitadb.comenius.git;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GitService should execute git commands for translation workflow")
public class GitServiceTest {

	private static Path tempDir;
	private GitService gitService;

	@BeforeEach
	void setUp() throws Exception {
		tempDir = Files.createTempDirectory("git-service-test-");
		initGitRepo();
		gitService = new GitService(tempDir);
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
	@DisplayName("shouldReturnCommitHashWhenFileIsTracked")
	void shouldReturnCommitHashWhenFileIsTracked() throws Exception {
		final Path file = tempDir.resolve("tracked.md");
		Files.writeString(file, "content");
		runGit("add", "tracked.md");
		runGit("commit", "-m", "Add tracked file");

		final Optional<String> hash = gitService.getCurrentCommitHash(file);

		assertTrue(hash.isPresent());
		assertEquals(40, hash.get().length(), "Commit hash should be 40 characters");
		assertTrue(hash.get().matches("[0-9a-f]+"), "Commit hash should be hex");
	}

	@Test
	@DisplayName("shouldReturnEmptyWhenFileIsUntracked")
	void shouldReturnEmptyWhenFileIsUntracked() throws Exception {
		// Create an initial commit first so git log doesn't fail
		final Path initialFile = tempDir.resolve("initial.md");
		Files.writeString(initialFile, "initial content");
		runGit("add", "initial.md");
		runGit("commit", "-m", "Initial commit");

		// Now test with an untracked file
		final Path file = tempDir.resolve("untracked.md");
		Files.writeString(file, "content");

		final Optional<String> hash = gitService.getCurrentCommitHash(file);

		assertTrue(hash.isEmpty());
	}

	@Test
	@DisplayName("shouldReturnFalseWhenFileHasUncommittedChanges")
	void shouldReturnFalseWhenFileHasUncommittedChanges() throws Exception {
		final Path file = tempDir.resolve("modified.md");
		Files.writeString(file, "original content");
		runGit("add", "modified.md");
		runGit("commit", "-m", "Add file");

		// Modify file without committing
		Files.writeString(file, "modified content");

		assertFalse(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldReturnTrueWhenFileIsClean")
	void shouldReturnTrueWhenFileIsClean() throws Exception {
		final Path file = tempDir.resolve("clean.md");
		Files.writeString(file, "content");
		runGit("add", "clean.md");
		runGit("commit", "-m", "Add clean file");

		assertTrue(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldReturnFalseWhenFileIsUntracked")
	void shouldReturnFalseWhenFileIsUntracked() throws Exception {
		final Path file = tempDir.resolve("new-file.md");
		Files.writeString(file, "content");

		assertFalse(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldReturnFalseWhenFileIsStaged")
	void shouldReturnFalseWhenFileIsStaged() throws Exception {
		final Path file = tempDir.resolve("staged.md");
		Files.writeString(file, "content");
		runGit("add", "staged.md");
		// Not committed yet

		assertFalse(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldReturnDiffWhenChangesExist")
	void shouldReturnDiffWhenChangesExist() throws Exception {
		final Path file = tempDir.resolve("diff-test.md");
		Files.writeString(file, "line1\nline2\n");
		runGit("add", "diff-test.md");
		runGit("commit", "-m", "First commit");
		final String firstCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		Files.writeString(file, "line1\nmodified\nline3\n");
		runGit("add", "diff-test.md");
		runGit("commit", "-m", "Second commit");
		final String secondCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		final Optional<String> diff = gitService.getDiff(file, firstCommit, secondCommit);

		assertTrue(diff.isPresent());
		assertTrue(diff.get().contains("-line2"));
		assertTrue(diff.get().contains("+modified"));
		assertTrue(diff.get().contains("+line3"));
	}

	@Test
	@DisplayName("shouldReturnEmptyDiffWhenNoChanges")
	void shouldReturnEmptyDiffWhenNoChanges() throws Exception {
		final Path file = tempDir.resolve("no-change.md");
		Files.writeString(file, "content");
		runGit("add", "no-change.md");
		runGit("commit", "-m", "Commit");
		final String commit = gitService.getCurrentCommitHash(file).orElseThrow();

		final Optional<String> diff = gitService.getDiff(file, commit, commit);

		assertTrue(diff.isEmpty());
	}

	@Test
	@DisplayName("shouldReturnCommitCountBetweenCommits")
	void shouldReturnCommitCountBetweenCommits() throws Exception {
		final Path file = tempDir.resolve("count-test.md");
		Files.writeString(file, "v1");
		runGit("add", "count-test.md");
		runGit("commit", "-m", "Commit 1");
		final String first = gitService.getCurrentCommitHash(file).orElseThrow();

		Files.writeString(file, "v2");
		runGit("add", "count-test.md");
		runGit("commit", "-m", "Commit 2");

		Files.writeString(file, "v3");
		runGit("add", "count-test.md");
		runGit("commit", "-m", "Commit 3");
		final String third = gitService.getCurrentCommitHash(file).orElseThrow();

		final int count = gitService.getCommitCount(file, first, third);

		assertEquals(2, count);
	}

	@Test
	@DisplayName("shouldReturnZeroCommitCountWhenSameCommit")
	void shouldReturnZeroCommitCountWhenSameCommit() throws Exception {
		final Path file = tempDir.resolve("same-commit.md");
		Files.writeString(file, "content");
		runGit("add", "same-commit.md");
		runGit("commit", "-m", "Commit");
		final String commit = gitService.getCurrentCommitHash(file).orElseThrow();

		final int count = gitService.getCommitCount(file, commit, commit);

		assertEquals(0, count);
	}

	@Test
	@DisplayName("shouldReturnFileContentAtSpecificCommit")
	void shouldReturnFileContentAtSpecificCommit() throws Exception {
		final Path file = tempDir.resolve("historical.md");
		Files.writeString(file, "old content");
		runGit("add", "historical.md");
		runGit("commit", "-m", "Old version");
		final String oldCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		Files.writeString(file, "new content");
		runGit("add", "historical.md");
		runGit("commit", "-m", "New version");

		final Optional<String> content = gitService.getFileAtCommit(file, oldCommit);

		assertTrue(content.isPresent());
		assertEquals("old content", content.get());
	}

	@Test
	@DisplayName("shouldReturnEmptyWhenFileNotExistsAtCommit")
	void shouldReturnEmptyWhenFileNotExistsAtCommit() throws Exception {
		// Create and commit a file
		final Path otherFile = tempDir.resolve("other.md");
		Files.writeString(otherFile, "content");
		runGit("add", "other.md");
		runGit("commit", "-m", "Commit");
		final String commit = gitService.getCurrentCommitHash(otherFile).orElseThrow();

		// Try to get a different file at that commit
		final Path nonExistent = tempDir.resolve("does-not-exist.md");

		final Optional<String> content = gitService.getFileAtCommit(nonExistent, commit);

		assertTrue(content.isEmpty());
	}

	@Test
	@DisplayName("shouldHandleSpacesInFilePath")
	void shouldHandleSpacesInFilePath() throws Exception {
		final Path file = tempDir.resolve("file with spaces.md");
		Files.writeString(file, "content");
		runGit("add", "file with spaces.md");
		runGit("commit", "-m", "Add file with spaces");

		final Optional<String> hash = gitService.getCurrentCommitHash(file);
		assertTrue(hash.isPresent());

		assertTrue(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldHandleNestedDirectories")
	void shouldHandleNestedDirectories() throws Exception {
		final Path dir = tempDir.resolve("a/b/c");
		Files.createDirectories(dir);
		final Path file = dir.resolve("nested.md");
		Files.writeString(file, "content");
		runGit("add", "a/b/c/nested.md");
		runGit("commit", "-m", "Add nested file");

		final Optional<String> hash = gitService.getCurrentCommitHash(file);
		assertTrue(hash.isPresent());

		assertTrue(gitService.isFileCommitted(file));
	}

	@Test
	@DisplayName("shouldRejectNullPath")
	void shouldRejectNullPath() {
		assertThrows(NullPointerException.class, () ->
			gitService.getCurrentCommitHash(null)
		);
	}

	@Test
	@DisplayName("shouldRejectNullCommitInGetFileAtCommit")
	void shouldRejectNullCommitInGetFileAtCommit() {
		final Path file = tempDir.resolve("test.md");
		assertThrows(NullPointerException.class, () ->
			gitService.getFileAtCommit(file, null)
		);
	}

	@Test
	@DisplayName("shouldRejectNullCommitsInGetDiff")
	void shouldRejectNullCommitsInGetDiff() {
		final Path file = tempDir.resolve("test.md");
		assertThrows(NullPointerException.class, () ->
			gitService.getDiff(file, null, "abc")
		);
		assertThrows(NullPointerException.class, () ->
			gitService.getDiff(file, "abc", null)
		);
	}

	// Tests for buildCommitInfo method

	@Test
	@DisplayName("shouldBuildCommitInfoForNewFile")
	void shouldBuildCommitInfoForNewFile() throws Exception {
		final Path file = tempDir.resolve("new-file.md");
		Files.writeString(file, "content");
		runGit("add", "new-file.md");
		runGit("commit", "-m", "Add new file");

		final Optional<CommitInfo> infoOpt = gitService.buildCommitInfo(file, null);

		assertTrue(infoOpt.isPresent());
		final CommitInfo info = infoOpt.get();
		assertTrue(info.isNewFile());
		assertFalse(info.needsUpdate());
		assertFalse(info.isUpToDate());
		assertNotNull(info.currentCommit());
		assertNull(info.translatedCommit());
		assertNull(info.diff());
		assertNull(info.originalSource());
		assertEquals(0, info.commitCount());
	}

	@Test
	@DisplayName("shouldBuildCommitInfoForUpToDateFile")
	void shouldBuildCommitInfoForUpToDateFile() throws Exception {
		final Path file = tempDir.resolve("uptodate.md");
		Files.writeString(file, "content");
		runGit("add", "uptodate.md");
		runGit("commit", "-m", "Add file");
		final String currentCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		final Optional<CommitInfo> infoOpt = gitService.buildCommitInfo(file, currentCommit);

		assertTrue(infoOpt.isPresent());
		final CommitInfo info = infoOpt.get();
		assertFalse(info.isNewFile());
		assertFalse(info.needsUpdate());
		assertTrue(info.isUpToDate());
		assertEquals(currentCommit, info.currentCommit());
		assertEquals(currentCommit, info.translatedCommit());
	}

	@Test
	@DisplayName("shouldBuildCommitInfoForFileNeedingUpdate")
	void shouldBuildCommitInfoForFileNeedingUpdate() throws Exception {
		final Path file = tempDir.resolve("needs-update.md");
		Files.writeString(file, "old content");
		runGit("add", "needs-update.md");
		runGit("commit", "-m", "Old version");
		final String oldCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		Files.writeString(file, "new content");
		runGit("add", "needs-update.md");
		runGit("commit", "-m", "New version");
		final String newCommit = gitService.getCurrentCommitHash(file).orElseThrow();

		final Optional<CommitInfo> infoOpt = gitService.buildCommitInfo(file, oldCommit);

		assertTrue(infoOpt.isPresent());
		final CommitInfo info = infoOpt.get();
		assertFalse(info.isNewFile());
		assertTrue(info.needsUpdate());
		assertFalse(info.isUpToDate());
		assertEquals(newCommit, info.currentCommit());
		assertEquals(oldCommit, info.translatedCommit());
		assertNotNull(info.diff());
		assertTrue(info.diff().contains("-old content"));
		assertTrue(info.diff().contains("+new content"));
		assertEquals("old content", info.originalSource());
		assertEquals(1, info.commitCount());
	}

	@Test
	@DisplayName("shouldReturnEmptyCommitInfoForUntrackedFile")
	void shouldReturnEmptyCommitInfoForUntrackedFile() throws Exception {
		// Create initial commit first
		final Path initialFile = tempDir.resolve("initial.md");
		Files.writeString(initialFile, "initial");
		runGit("add", "initial.md");
		runGit("commit", "-m", "Initial commit");

		// Create untracked file
		final Path untracked = tempDir.resolve("untracked.md");
		Files.writeString(untracked, "content");

		final Optional<CommitInfo> infoOpt = gitService.buildCommitInfo(untracked, null);

		assertTrue(infoOpt.isEmpty());
	}

	private static void deleteRecursively(Path path) throws IOException {
		if (Files.exists(path)) {
			Files.walk(path)
				.sorted(Comparator.reverseOrder())
				.forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						// ignore in cleanup
					}
				});
		}
	}
}
