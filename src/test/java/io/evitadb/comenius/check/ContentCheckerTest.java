package io.evitadb.comenius.check;

import io.evitadb.comenius.git.GitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ContentChecker validates Git status and link integrity")
public class ContentCheckerTest {

	private Path tempDir;
	private GitService gitService;
	private ContentChecker checker;

	@BeforeEach
	void setUp() throws Exception {
		this.tempDir = Files.createTempDirectory("content-checker-test-");
		initGitRepo();
		this.gitService = new GitService(this.tempDir);
		this.checker = new ContentChecker(this.gitService, this.tempDir, this.tempDir);
	}

	@AfterEach
	void tearDown() throws Exception {
		deleteRecursively(this.tempDir);
	}

	private void initGitRepo() throws Exception {
		runGit("init");
		runGit("config", "user.email", "test@example.com");
		runGit("config", "user.name", "Test User");
	}

	private void runGit(String... args) throws Exception {
		final ProcessBuilder pb = new ProcessBuilder();
		pb.directory(this.tempDir.toFile());
		pb.command("git");
		for (final String arg : args) {
			pb.command().add(arg);
		}
		final Process process = pb.start();
		final int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IOException("Git command failed: " + String.join(" ", args));
		}
	}

	private Path writeFile(String relativePath, String content) throws IOException {
		final Path file = this.tempDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private void deleteRecursively(Path path) throws IOException {
		if (Files.notExists(path)) {
			return;
		}
		Files.walk(path)
			.sorted(Comparator.reverseOrder())
			.forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					// ignore in cleanup
				}
			});
	}

	// === Git Status Tests ===

	@Test
	@DisplayName("passes for committed files")
	public void shouldPassForCommittedFiles() throws Exception {
		final Path file = writeFile("docs/test.md", "# Hello\n\nSome content.");
		runGit("add", "docs/test.md");
		runGit("commit", "-m", "Add test file");

		this.checker.checkFile(file, "# Hello\n\nSome content.");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.gitErrors().isEmpty(), "Should have no git errors");
	}

	@Test
	@DisplayName("reports uncommitted changes")
	public void shouldReportUncommittedChanges() throws Exception {
		final Path file = writeFile("docs/test.md", "original");
		runGit("add", "docs/test.md");
		runGit("commit", "-m", "Add test file");

		// Modify the file
		Files.writeString(file, "modified content");

		this.checker.checkFile(file, "modified content");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.gitErrors().size());
		assertEquals(GitError.GitErrorType.UNCOMMITTED_CHANGES, result.gitErrors().get(0).type());
	}

	@Test
	@DisplayName("reports untracked files")
	public void shouldReportUntrackedFiles() throws Exception {
		// Create initial commit so git log works
		final Path initial = writeFile("initial.md", "init");
		runGit("add", "initial.md");
		runGit("commit", "-m", "Initial");

		// Create untracked file
		final Path file = writeFile("docs/untracked.md", "content");

		this.checker.checkFile(file, "content");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.gitErrors().size());
		assertEquals(GitError.GitErrorType.UNTRACKED, result.gitErrors().get(0).type());
	}

	@Test
	@DisplayName("reports staged but uncommitted files")
	public void shouldReportStagedButUncommittedFiles() throws Exception {
		// Create initial commit
		final Path initial = writeFile("initial.md", "init");
		runGit("add", "initial.md");
		runGit("commit", "-m", "Initial");

		// Create and stage new file
		final Path file = writeFile("docs/staged.md", "staged content");
		runGit("add", "docs/staged.md");
		// Don't commit!

		this.checker.checkFile(file, "staged content");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.gitErrors().size());
		// Staged but not committed - file has no commit history yet
		assertEquals(GitError.GitErrorType.UNTRACKED, result.gitErrors().get(0).type());
	}

	// === Link Validation Tests ===

	@Test
	@DisplayName("passes for valid relative path links")
	public void shouldPassForValidRelativePathLinks() throws Exception {
		final Path file1 = writeFile("docs/source.md", "See [other](other.md).");
		final Path file2 = writeFile("docs/other.md", "# Other\n\nContent.");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(file1, "See [other](other.md).");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should have no link errors");
	}

	@Test
	@DisplayName("reports missing file links")
	public void shouldReportMissingFileLinks() throws Exception {
		final Path file = writeFile("docs/source.md", "See [missing](missing.md).");
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(file, "See [missing](missing.md).");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.linkErrors().size());
		assertEquals(LinkError.LinkErrorType.FILE_NOT_FOUND, result.linkErrors().get(0).type());
		assertEquals("missing.md", result.linkErrors().get(0).linkDestination());
	}

	@Test
	@DisplayName("passes for valid anchor links in same document")
	public void shouldPassForValidAnchorLinksInSameDocument() throws Exception {
		final String content = """
			# Introduction

			See [details](#details) below.

			## Details

			More info here.
			""";
		final Path file = writeFile("docs/test.md", content);
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(file, content);

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should have no link errors");
	}

	@Test
	@DisplayName("reports missing anchor in same document")
	public void shouldReportMissingAnchorInSameDocument() throws Exception {
		final String content = """
			# Introduction

			See [missing section](#nonexistent) below.
			""";
		final Path file = writeFile("docs/test.md", content);
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(file, content);

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.linkErrors().size());
		assertEquals(LinkError.LinkErrorType.ANCHOR_NOT_FOUND, result.linkErrors().get(0).type());
	}

	@Test
	@DisplayName("passes for valid combined path and anchor links")
	public void shouldPassForValidCombinedPathAndAnchorLinks() throws Exception {
		final Path source = writeFile("docs/source.md", "See [section](other.md#details).");
		final Path target = writeFile("docs/other.md", "# Other\n\n## Details\n\nInfo.");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "See [section](other.md#details).");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should have no link errors");
	}

	@Test
	@DisplayName("reports missing anchor in external file")
	public void shouldReportMissingAnchorInExternalFile() throws Exception {
		final Path source = writeFile("docs/source.md", "See [section](other.md#nonexistent).");
		final Path target = writeFile("docs/other.md", "# Other\n\n## Details\n\nInfo.");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "See [section](other.md#nonexistent).");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.linkErrors().size());
		assertEquals(LinkError.LinkErrorType.ANCHOR_NOT_FOUND, result.linkErrors().get(0).type());
	}

	@Test
	@DisplayName("skips external links")
	public void shouldSkipExternalLinks() throws Exception {
		final String content = "Visit [Google](https://google.com) and [email](mailto:test@test.com).";
		final Path file = writeFile("docs/test.md", content);
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(file, content);

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should skip external links");
	}

	@Test
	@DisplayName("handles parent directory navigation")
	public void shouldHandleParentDirectoryNavigation() throws Exception {
		final Path source = writeFile("docs/sub/source.md", "See [parent](../parent.md).");
		final Path target = writeFile("docs/parent.md", "# Parent");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "See [parent](../parent.md).");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should handle parent directory links");
	}

	@Test
	@DisplayName("handles case-insensitive anchor matching")
	public void shouldHandleCaseInsensitiveAnchorMatching() throws Exception {
		final String content = """
			# Getting Started

			See [section](#GETTING-STARTED) above.
			""";
		final Path file = writeFile("docs/test.md", content);
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(file, content);

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should match anchors case-insensitively");
	}

	@Test
	@DisplayName("validates absolute path links from git root")
	public void shouldValidateAbsolutePathLinksFromGitRoot() throws Exception {
		final Path source = writeFile("docs/source.md", "See [root file](/readme.md).");
		final Path target = writeFile("readme.md", "# README");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "See [root file](/readme.md).");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should resolve absolute paths from git root");
	}

	@Test
	@DisplayName("reports missing absolute path links")
	public void shouldReportMissingAbsolutePathLinks() throws Exception {
		final Path source = writeFile("docs/source.md", "See [missing](/nonexistent.md).");
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(source, "See [missing](/nonexistent.md).");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.linkErrors().size());
		assertEquals(LinkError.LinkErrorType.FILE_NOT_FOUND, result.linkErrors().get(0).type());
	}

	@Test
	@DisplayName("validates image links")
	public void shouldValidateImageLinks() throws Exception {
		final Path source = writeFile("docs/source.md", "![alt](images/photo.png)");
		final Path image = writeFile("docs/images/photo.png", "fake image data");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "![alt](images/photo.png)");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should validate image links");
	}

	@Test
	@DisplayName("reports missing image links")
	public void shouldReportMissingImageLinks() throws Exception {
		final Path source = writeFile("docs/source.md", "![alt](images/missing.png)");
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.checker.checkFile(source, "![alt](images/missing.png)");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.linkErrors().size());
		assertEquals(LinkError.LinkErrorType.FILE_NOT_FOUND, result.linkErrors().get(0).type());
	}

	@Test
	@DisplayName("handles URL-encoded file paths")
	public void shouldHandleUrlEncodedFilePaths() throws Exception {
		final Path source = writeFile("docs/source.md", "See [file](my%20file.md).");
		final Path target = writeFile("docs/my file.md", "# My File");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.checker.checkFile(source, "See [file](my%20file.md).");

		final CheckResult result = this.checker.getResult();
		assertTrue(result.linkErrors().isEmpty(), "Should decode URL-encoded paths");
	}

	@Test
	@DisplayName("combines git and link errors in result")
	public void shouldCombineGitAndLinkErrorsInResult() throws Exception {
		// Create initial commit
		final Path initial = writeFile("initial.md", "init");
		runGit("add", ".");
		runGit("commit", "-m", "Initial");

		// Create untracked file with broken link
		final Path file = writeFile("docs/test.md", "See [missing](missing.md).");

		this.checker.checkFile(file, "See [missing](missing.md).");

		final CheckResult result = this.checker.getResult();
		assertEquals(1, result.gitErrors().size(), "Should have git error");
		assertEquals(1, result.linkErrors().size(), "Should have link error");
		assertFalse(result.isSuccess(), "Should not be success");
		assertEquals(2, result.errorCount(), "Should have 2 total errors");
	}
}
