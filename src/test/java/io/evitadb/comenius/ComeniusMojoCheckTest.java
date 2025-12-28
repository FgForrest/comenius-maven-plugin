package io.evitadb.comenius;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ComeniusMojo check action integration")
public class ComeniusMojoCheckTest {

	private Path tempDir;
	private ComeniusMojo mojo;
	private TestLog testLog;

	@BeforeEach
	void setUp() throws Exception {
		this.tempDir = Files.createTempDirectory("mojo-check-test-");
		initGitRepo();
		this.mojo = new ComeniusMojo();
		this.testLog = new TestLog();
		this.mojo.setLog(this.testLog);
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

	@Test
	@DisplayName("passes when all files are valid")
	public void shouldPassWhenAllFilesAreValid() throws Exception {
		// Create valid committed files with valid links
		writeFile("docs/index.md", "# Index\n\nSee [guide](guide.md).");
		writeFile("docs/guide.md", "# Guide\n\nBack to [index](index.md).");
		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		assertDoesNotThrow(() -> this.mojo.execute());
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("All checks passed")));
	}

	@Test
	@DisplayName("throws MojoExecutionException on git errors")
	public void shouldThrowMojoExecutionExceptionOnGitErrors() throws Exception {
		// Create initial commit
		writeFile("docs/initial.md", "# Initial");
		runGit("add", ".");
		runGit("commit", "-m", "Initial");

		// Create untracked file
		writeFile("docs/untracked.md", "# Untracked");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		final MojoExecutionException exception = assertThrows(
			MojoExecutionException.class,
			() -> this.mojo.execute()
		);
		assertTrue(exception.getMessage().contains("error"));
	}

	@Test
	@DisplayName("throws MojoExecutionException on link errors")
	public void shouldThrowMojoExecutionExceptionOnLinkErrors() throws Exception {
		// Create file with broken link
		writeFile("docs/test.md", "See [missing](nonexistent.md).");
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		final MojoExecutionException exception = assertThrows(
			MojoExecutionException.class,
			() -> this.mojo.execute()
		);
		assertTrue(exception.getMessage().contains("error"));
	}

	@Test
	@DisplayName("reports multiple error types together")
	public void shouldReportMultipleErrorTypesTogether() throws Exception {
		// Create initial commit
		writeFile("docs/initial.md", "# Initial");
		runGit("add", ".");
		runGit("commit", "-m", "Initial");

		// Create untracked file with broken link
		writeFile("docs/problem.md", "See [missing](nonexistent.md).");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		final MojoExecutionException exception = assertThrows(
			MojoExecutionException.class,
			() -> this.mojo.execute()
		);
		// Should have both git and link errors
		assertTrue(this.testLog.errorMessages.stream()
			.anyMatch(msg -> msg.contains("Git status errors")));
		assertTrue(this.testLog.errorMessages.stream()
			.anyMatch(msg -> msg.contains("Link validation errors")));
	}

	@Test
	@DisplayName("throws MojoExecutionException when source dir not specified")
	public void shouldThrowWhenSourceDirNotSpecified() {
		this.mojo.setAction("check");
		// Don't set sourceDir

		assertThrows(MojoExecutionException.class, () -> this.mojo.execute());
	}

	@Test
	@DisplayName("respects fileRegex pattern")
	public void shouldRespectFileRegexPattern() throws Exception {
		// Create files with different extensions
		writeFile("docs/test.md", "# Test\n\nSee [missing](nonexistent.md).");
		writeFile("docs/test.txt", "See [missing](nonexistent.txt).");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		// Only check .txt files (which have no broken links to .md files)
		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());
		this.mojo.setFileRegex(".*\\.txt");

		// The .txt file has a broken link to nonexistent.txt, so it should fail
		assertThrows(MojoExecutionException.class, () -> this.mojo.execute());
	}

	@Test
	@DisplayName("validates absolute links from git root")
	public void shouldValidateAbsoluteLinksFromGitRoot() throws Exception {
		// Create file in subdirectory linking to root file
		writeFile("docs/source.md", "See [readme](/readme.md).");
		writeFile("readme.md", "# README");
		runGit("add", ".");
		runGit("commit", "-m", "Add files");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		assertDoesNotThrow(() -> this.mojo.execute());
	}

	@Test
	@DisplayName("skips external links")
	public void shouldSkipExternalLinks() throws Exception {
		// Create file with external links only
		writeFile("docs/test.md", "Visit [Google](https://google.com) and [email](mailto:test@test.com).");
		runGit("add", ".");
		runGit("commit", "-m", "Add file");

		this.mojo.setAction("check");
		this.mojo.setSourceDir(this.tempDir.resolve("docs").toString());

		assertDoesNotThrow(() -> this.mojo.execute());
	}

	/**
	 * Simple test implementation of Maven Log that captures messages.
	 */
	private class TestLog implements Log {
		final List<String> debugMessages = new ArrayList<>();
		final List<String> infoMessages = new ArrayList<>();
		final List<String> warnMessages = new ArrayList<>();
		final List<String> errorMessages = new ArrayList<>();

		@Override
		public boolean isDebugEnabled() { return true; }

		@Override
		public void debug(CharSequence content) { this.debugMessages.add(content.toString()); }

		@Override
		public void debug(CharSequence content, Throwable error) { this.debugMessages.add(content.toString()); }

		@Override
		public void debug(Throwable error) { this.debugMessages.add(error.getMessage()); }

		@Override
		public boolean isInfoEnabled() { return true; }

		@Override
		public void info(CharSequence content) { this.infoMessages.add(content.toString()); }

		@Override
		public void info(CharSequence content, Throwable error) { this.infoMessages.add(content.toString()); }

		@Override
		public void info(Throwable error) { this.infoMessages.add(error.getMessage()); }

		@Override
		public boolean isWarnEnabled() { return true; }

		@Override
		public void warn(CharSequence content) { this.warnMessages.add(content.toString()); }

		@Override
		public void warn(CharSequence content, Throwable error) { this.warnMessages.add(content.toString()); }

		@Override
		public void warn(Throwable error) { this.warnMessages.add(error.getMessage()); }

		@Override
		public boolean isErrorEnabled() { return true; }

		@Override
		public void error(CharSequence content) { this.errorMessages.add(content.toString()); }

		@Override
		public void error(CharSequence content, Throwable error) { this.errorMessages.add(content.toString()); }

		@Override
		public void error(Throwable error) { this.errorMessages.add(error.getMessage()); }
	}
}
