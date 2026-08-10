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

/**
 * Verifies that the translate action refuses to record commit provenance from a shallow clone.
 *
 * A shallow repository answers `git log -1 -- <file>` with its graft boundary for every file whose
 * real last change predates the truncation point, so the recorded `commit` field becomes a
 * plausible-looking hash unrelated to the document.
 */
@DisplayName("ComeniusMojo translate action guards against shallow clones")
public class ComeniusMojoShallowRepositoryTest {

	private Path tempDir;
	private Path originRepo;
	private Path shallowClone;
	private ComeniusMojo mojo;
	private TestLog testLog;

	@BeforeEach
	void setUp() throws Exception {
		this.tempDir = Files.createTempDirectory("mojo-shallow-test-");
		this.originRepo = this.tempDir.resolve("origin");
		this.shallowClone = this.tempDir.resolve("shallow");
		Files.createDirectories(this.originRepo);

		runGit(this.originRepo, "init");
		runGit(this.originRepo, "config", "user.email", "test@example.com");
		runGit(this.originRepo, "config", "user.name", "Test User");
		writeFile(this.originRepo, "docs/en/guide.md", "# Guide\n\nFirst revision.\n");
		writeFile(this.originRepo, "docs/de/guide.md", "# Anleitung\n\nErste Fassung.\n");
		runGit(this.originRepo, "add", ".");
		runGit(this.originRepo, "commit", "-m", "First commit");
		writeFile(this.originRepo, "unrelated.md", "# Unrelated\n");
		runGit(this.originRepo, "add", ".");
		runGit(this.originRepo, "commit", "-m", "Second commit");

		// depth 1 truncates history, so guide.md now looks as if the tip commit created it
		runGit(this.tempDir, "clone", "--depth", "1",
			"file://" + this.originRepo, this.shallowClone.toString());

		this.mojo = new ComeniusMojo();
		this.testLog = new TestLog();
		this.mojo.setLog(this.testLog);
		this.mojo.setAction("translate");
		this.mojo.setDryRun(true);
		this.mojo.setSourceDir(this.shallowClone.resolve("docs/en").toString());
		final List<ComeniusMojo.Target> targets = new ArrayList<>();
		targets.add(new ComeniusMojo.Target("de", this.shallowClone.resolve("docs/de").toString()));
		this.mojo.setTargets(targets);
	}

	@AfterEach
	void tearDown() throws Exception {
		deleteRecursively(this.tempDir);
	}

	@Test
	@DisplayName("refuses to translate from a shallow clone")
	public void shouldFailWhenRepositoryIsShallow() {
		final MojoExecutionException exception = assertThrows(
			MojoExecutionException.class, () -> this.mojo.execute()
		);
		assertTrue(exception.getMessage().contains("shallow"),
			"Expected the failure to name the cause, got: " + exception.getMessage());
	}

	@Test
	@DisplayName("proceeds with a warning when the shallow override is set")
	public void shouldWarnAndProceedWhenShallowIsAllowed() {
		this.mojo.setAllowShallowRepository(true);

		assertDoesNotThrow(() -> this.mojo.execute());
		assertTrue(
			this.testLog.warnMessages.stream().anyMatch(m -> m.contains("shallow clone")),
			"Expected a warning about the shallow clone, got: " + this.testLog.warnMessages
		);
	}

	private void runGit(Path workingDir, String... args) throws Exception {
		final ProcessBuilder pb = new ProcessBuilder();
		pb.directory(workingDir.toFile());
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

	private void writeFile(Path baseDir, String relativePath, String content) throws IOException {
		final Path file = baseDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
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

	private static class TestLog implements Log {
		final List<String> warnMessages = new ArrayList<>();

		@Override
		public boolean isDebugEnabled() { return false; }

		@Override
		public void debug(CharSequence content) { }

		@Override
		public void debug(CharSequence content, Throwable error) { }

		@Override
		public void debug(Throwable error) { }

		@Override
		public boolean isInfoEnabled() { return true; }

		@Override
		public void info(CharSequence content) { }

		@Override
		public void info(CharSequence content, Throwable error) { }

		@Override
		public void info(Throwable error) { }

		@Override
		public boolean isWarnEnabled() { return true; }

		@Override
		public void warn(CharSequence content) { this.warnMessages.add(content.toString()); }

		@Override
		public void warn(CharSequence content, Throwable error) { this.warnMessages.add(content.toString()); }

		@Override
		public void warn(Throwable error) { this.warnMessages.add(String.valueOf(error.getMessage())); }

		@Override
		public boolean isErrorEnabled() { return true; }

		@Override
		public void error(CharSequence content) { }

		@Override
		public void error(CharSequence content, Throwable error) { }

		@Override
		public void error(Throwable error) { }
	}
}
