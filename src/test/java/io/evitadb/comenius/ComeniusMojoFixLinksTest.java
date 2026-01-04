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
 * Integration tests for the fix-links action of ComeniusMojo.
 */
@DisplayName("ComeniusMojo fix-links action integration")
public class ComeniusMojoFixLinksTest {

	private Path tempDir;
	private Path sourceDir;
	private Path targetDir;
	private ComeniusMojo mojo;
	private TestLog testLog;

	@BeforeEach
	void setUp() throws Exception {
		this.tempDir = Files.createTempDirectory("mojo-fix-links-test-");
		this.sourceDir = this.tempDir.resolve("source");
		this.targetDir = this.tempDir.resolve("target");
		Files.createDirectories(this.sourceDir);
		Files.createDirectories(this.targetDir);
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

	private Path writeFile(Path baseDir, String relativePath, String content) throws IOException {
		final Path file = baseDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private String readFile(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
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

	private void configureMojo() {
		this.mojo.setAction("fix-links");
		this.mojo.setSourceDir(this.sourceDir.toString());
		final List<ComeniusMojo.Target> targets = new ArrayList<>();
		targets.add(new ComeniusMojo.Target("de", this.targetDir.toString()));
		this.mojo.setTargets(targets);
	}

	@Test
	@DisplayName("passes when all links are already valid")
	public void shouldPassWhenAllLinksAreValid() throws Exception {
		// Create source files with headings
		writeFile(this.sourceDir, "index.md", "# Index\n\nSee [guide](guide.md).");
		writeFile(this.sourceDir, "guide.md", "# Guide\n\nBack to [index](index.md).");

		// Create target files with same structure (already valid links)
		writeFile(this.targetDir, "index.md", "# Index\n\nSee [guide](guide.md).");
		writeFile(this.targetDir, "guide.md", "# Guide\n\nBack to [index](index.md).");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();

		assertDoesNotThrow(() -> this.mojo.execute());
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("all links OK")));
	}

	@Test
	@DisplayName("corrects asset links in target directory")
	public void shouldCorrectAssetLinksInTargetDirectory() throws Exception {
		// Create source file with image
		writeFile(this.sourceDir, "docs/article.md", "# Article\n\n![Image](../assets/image.png)");
		writeFile(this.sourceDir, "assets/image.png", "fake-png-content");

		// Create target file - the relative path needs correction since target structure differs
		writeFile(this.targetDir, "docs/article.md", "# Artikel\n\n![Bild](../assets/image.png)");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();

		assertDoesNotThrow(() -> this.mojo.execute());

		// The link should be corrected to point from target/docs to source/assets
		final String correctedContent = readFile(this.targetDir.resolve("docs/article.md"));
		assertTrue(correctedContent.contains("../../source/assets/image.png"),
			"Asset link should be corrected to point to source directory. Actual: " + correctedContent);
	}

	@Test
	@DisplayName("corrects anchor links by index mapping")
	public void shouldCorrectAnchorLinksInTargetDirectory() throws Exception {
		// Create source file with heading
		writeFile(this.sourceDir, "article.md",
			"# Introduction\n\nSee [below](#conclusion).\n\n## Conclusion\n\nThe end.");

		// Create target file with translated heading - anchor should be corrected
		writeFile(this.targetDir, "article.md",
			"# Einleitung\n\nSiehe [unten](#conclusion).\n\n## Fazit\n\nDas Ende.");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();

		assertDoesNotThrow(() -> this.mojo.execute());

		// The anchor should be corrected to the translated heading slug
		final String correctedContent = readFile(this.targetDir.resolve("article.md"));
		assertTrue(correctedContent.contains("#fazit"),
			"Anchor should be corrected to translated heading. Actual: " + correctedContent);
	}

	@Test
	@DisplayName("throws MojoExecutionException when source dir not specified")
	public void shouldThrowWhenSourceDirNotSpecified() {
		this.mojo.setAction("fix-links");
		// Don't set sourceDir
		final List<ComeniusMojo.Target> targets = new ArrayList<>();
		targets.add(new ComeniusMojo.Target("de", this.targetDir.toString()));
		this.mojo.setTargets(targets);

		assertThrows(MojoExecutionException.class, () -> this.mojo.execute());
	}

	@Test
	@DisplayName("throws MojoExecutionException when no targets specified")
	public void shouldThrowWhenNoTargetsSpecified() {
		this.mojo.setAction("fix-links");
		this.mojo.setSourceDir(this.sourceDir.toString());
		// Don't set targets

		assertThrows(MojoExecutionException.class, () -> this.mojo.execute());
	}

	@Test
	@DisplayName("processes multiple target directories")
	public void shouldProcessMultipleTargetDirectories() throws Exception {
		final Path targetDirDe = this.tempDir.resolve("target-de");
		final Path targetDirFr = this.tempDir.resolve("target-fr");
		Files.createDirectories(targetDirDe);
		Files.createDirectories(targetDirFr);

		// Create source file
		writeFile(this.sourceDir, "index.md", "# Index\n\nContent.");

		// Create target files
		writeFile(targetDirDe, "index.md", "# Index DE\n\nInhalt.");
		writeFile(targetDirFr, "index.md", "# Index FR\n\nContenu.");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		this.mojo.setAction("fix-links");
		this.mojo.setSourceDir(this.sourceDir.toString());
		final List<ComeniusMojo.Target> targets = new ArrayList<>();
		targets.add(new ComeniusMojo.Target("de", targetDirDe.toString()));
		targets.add(new ComeniusMojo.Target("fr", targetDirFr.toString()));
		this.mojo.setTargets(targets);

		assertDoesNotThrow(() -> this.mojo.execute());

		// Should process both targets
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("German") || msg.contains("de")));
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("French") || msg.contains("fr")));
	}

	@Test
	@DisplayName("respects fileRegex pattern")
	public void shouldRespectFileRegexPattern() throws Exception {
		// Create files with different extensions
		writeFile(this.sourceDir, "article.md", "# Article");
		writeFile(this.sourceDir, "notes.txt", "Notes");

		writeFile(this.targetDir, "article.md", "# Artikel");
		writeFile(this.targetDir, "notes.txt", "Notizen");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();
		this.mojo.setFileRegex(".*\\.md"); // Only process .md files

		assertDoesNotThrow(() -> this.mojo.execute());

		// Should only find 1 file (the .md file)
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("Found 1 files")));
	}

	@Test
	@DisplayName("respects excluded file patterns")
	public void shouldRespectExcludedFilePatterns() throws Exception {
		// Create files in different directories
		writeFile(this.sourceDir, "docs/article.md", "# Article");
		writeFile(this.sourceDir, "excluded/hidden.md", "# Hidden");

		writeFile(this.targetDir, "docs/article.md", "# Artikel");
		writeFile(this.targetDir, "excluded/hidden.md", "# Versteckt");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();
		final List<String> excludedPatterns = new ArrayList<>();
		excludedPatterns.add(".*/excluded/.*");
		this.mojo.setExcludedFilePatterns(excludedPatterns);

		assertDoesNotThrow(() -> this.mojo.execute());

		// Should only find 1 file (excluded directory is skipped)
		assertTrue(this.testLog.infoMessages.stream()
			.anyMatch(msg -> msg.contains("Found 1 files")));
	}

	@Test
	@DisplayName("reports link validation errors after correction")
	public void shouldValidateLinksAfterCorrection() throws Exception {
		// Create source file
		writeFile(this.sourceDir, "article.md", "# Article\n\nContent.");

		// Create target file with broken link
		writeFile(this.targetDir, "article.md", "# Artikel\n\nSee [missing](nonexistent.md).");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		configureMojo();

		assertDoesNotThrow(() -> this.mojo.execute());

		// Should report validation errors for broken link
		assertTrue(this.testLog.errorMessages.stream()
			.anyMatch(msg -> msg.contains("validation error") || msg.contains("nonexistent.md")));
	}

	@Test
	@DisplayName("skips non-existent target directories")
	public void shouldSkipNonExistentTargetDirectories() throws Exception {
		// Create source file
		writeFile(this.sourceDir, "index.md", "# Index");

		runGit("add", ".");
		runGit("commit", "-m", "Add docs");

		this.mojo.setAction("fix-links");
		this.mojo.setSourceDir(this.sourceDir.toString());
		final List<ComeniusMojo.Target> targets = new ArrayList<>();
		targets.add(new ComeniusMojo.Target("de", this.tempDir.resolve("nonexistent").toString()));
		this.mojo.setTargets(targets);

		assertDoesNotThrow(() -> this.mojo.execute());

		// Should warn about missing directory
		assertTrue(this.testLog.warnMessages.stream()
			.anyMatch(msg -> msg.contains("does not exist")));
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
