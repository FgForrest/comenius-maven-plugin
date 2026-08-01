package io.evitadb.comenius.check;

import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.structure.TagVocabulary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CrossLanguageChecker validates a translation's markup shape against its source")
public class CrossLanguageCheckerTest {

	private final TagVocabulary vocabulary = TagVocabulary.of(Set.of("LS"), Set.of(), Set.of(), false);

	private Path repoRoot;
	private Path sourceDir;
	private Path targetDir;
	private GitService gitService;

	@BeforeEach
	void setUp() throws Exception {
		this.repoRoot = Files.createTempDirectory("cross-language-checker-test-");
		this.sourceDir = this.repoRoot.resolve("en");
		this.targetDir = this.repoRoot.resolve("cs");
		Files.createDirectories(this.sourceDir);
		Files.createDirectories(this.targetDir);
		initGitRepo();
		this.gitService = new GitService(this.repoRoot);
	}

	@AfterEach
	void tearDown() throws Exception {
		deleteRecursively(this.repoRoot);
	}

	private void initGitRepo() throws Exception {
		runGit("init");
		runGit("config", "user.email", "test@example.com");
		runGit("config", "user.name", "Test User");
	}

	private void runGit(String... args) throws Exception {
		final ProcessBuilder pb = new ProcessBuilder();
		pb.directory(this.repoRoot.toFile());
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

	private Path writeSource(String relativePath, String content) throws IOException {
		final Path file = this.sourceDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private String commitSource(String relativePath) throws Exception {
		runGit("add", "en/" + relativePath);
		runGit("commit", "-m", "commit " + relativePath);
		return this.gitService.getCurrentCommitHash(this.sourceDir.resolve(relativePath)).orElseThrow();
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
	@DisplayName("flags a heading nested in a different language scope than its source")
	void shouldDetectTagScopeMismatch() throws Exception {
		writeSource("doc.md", "<LS to=\"g\">\n\n# Title\n\nContent.\n\n</LS>\n");
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		checker.checkFile(
			this.targetDir.resolve("doc.md"),
			"<LS to=\"j,r\">\n\n# Titulek\n\nObsah.\n\n</LS>\n"
		);

		final List<StructuralError> errors = checker.getErrors();
		assertEquals(1, errors.size());
		assertEquals(StructuralError.StructuralErrorType.TAG_SCOPE_MISMATCH, errors.get(0).type());
		assertTrue(errors.get(0).message().contains("heading 1"));
	}

	@Test
	@DisplayName("passes when every heading sits in the same language scope as its source")
	void shouldPassWhenTagScopeMatches() throws Exception {
		writeSource("doc.md", "<LS to=\"g\">\n\n# Title\n\nContent.\n\n</LS>\n\n# Plain\n\nMore.\n");
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		checker.checkFile(
			this.targetDir.resolve("doc.md"),
			"<LS to=\"g\">\n\n# Titulek\n\nObsah.\n\n</LS>\n\n# Prosty\n\nDalsi.\n"
		);

		assertEquals(List.of(), checker.getErrors());
	}

	@Test
	@DisplayName("flags a heading-count mismatch instead of guessing a position-by-position comparison")
	void shouldDetectHeadingCountMismatch() throws Exception {
		writeSource("doc.md", "# One\n\nText.\n\n# Two\n\nText.\n");
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		checker.checkFile(this.targetDir.resolve("doc.md"), "# Jedna\n\nText.\n");

		final List<StructuralError> errors = checker.getErrors();
		assertEquals(1, errors.size());
		assertEquals(StructuralError.StructuralErrorType.HEADING_COUNT_MISMATCH, errors.get(0).type());
	}

	@Test
	@DisplayName("flags likely content loss when the commit field claims the source is current but structure diverges")
	void shouldDetectLikelyContentLossWhenCommitUnchangedButStructureDiverges() throws Exception {
		writeSource(
			"doc.md",
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish-only content.\n\n</LS>\n"
		);
		final String commit = commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		// the <LS> block was dropped entirely - same heading count, fewer tags
		checker.checkFile(
			this.targetDir.resolve("doc.md"),
			"---\ncommit: '" + commit + "'\n---\n# Titulek\n\nUvod.\n"
		);

		final List<StructuralError> errors = checker.getErrors();
		assertEquals(1, errors.size());
		assertEquals(StructuralError.StructuralErrorType.LIKELY_CONTENT_LOSS, errors.get(0).type());
		assertTrue(errors.get(0).message().contains(commit));
	}

	@Test
	@DisplayName("does not flag content loss when the source has moved on since the claimed commit")
	void shouldNotFlagStaleTranslationWhenSourceHasMovedOn() throws Exception {
		writeSource(
			"doc.md",
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish-only content.\n\n</LS>\n"
		);
		final String oldCommit = commitSource("doc.md");

		// the source keeps evolving after the translation was made
		writeSource(
			"doc.md",
			"# Title\n\nIntro.\n\n<LS to=\"e\">\n\nEnglish-only content.\n\n</LS>\n\n" +
				"# Added Later\n\nBrand new section.\n"
		);
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		// translation still only covers the old, smaller source - legitimately stale, not a defect
		checker.checkFile(
			this.targetDir.resolve("doc.md"),
			"---\ncommit: '" + oldCommit + "'\n---\n# Titulek\n\nUvod.\n\n<LS to=\"e\">\n\n" +
				"English-only content.\n\n</LS>\n"
		);

		assertEquals(
			List.of(),
			checker.getErrors().stream()
				.filter(e -> e.type() == StructuralError.StructuralErrorType.LIKELY_CONTENT_LOSS)
				.toList()
		);
	}

	@Test
	@DisplayName("does nothing when the translated file has no English source counterpart")
	void shouldSkipWhenNoSourceCounterpartExists() {
		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		checker.checkFile(this.targetDir.resolve("orphan.md"), "# Osamocene\n\nText.\n");

		assertEquals(List.of(), checker.getErrors());
	}

	@Test
	@DisplayName("reports one clear cause instead of cascading when the translation has an unmatched closing tag")
	void shouldReportUnmatchedClosingTagInsteadOfCascadingMismatches() throws Exception {
		writeSource("doc.md", "<LS to=\"g\">\n\n# A\n\nContent.\n\n</LS>\n\n# B\n\nMore.\n");
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		// "A" is dropped, leaving a stray </LS> with nothing open before it
		checker.checkFile(this.targetDir.resolve("doc.md"), "# A\n\n</LS>\n\n# B\n");

		final List<StructuralError> errors = checker.getErrors();
		assertEquals(1, errors.size(), "must report exactly one cause, not a cascade");
		assertEquals(StructuralError.StructuralErrorType.UNMATCHED_CLOSING_TAG, errors.get(0).type());
	}

	@Test
	@DisplayName("skips the content-loss check but still runs the tag-scope check when there is no commit field")
	void shouldSkipCompletenessCheckButStillCheckTagScopeWhenNoCommitField() throws Exception {
		writeSource("doc.md", "<LS to=\"g\">\n\n# Title\n\nContent.\n\n</LS>\n");
		commitSource("doc.md");

		final CrossLanguageChecker checker =
			new CrossLanguageChecker(this.sourceDir, this.targetDir, this.gitService, this.vocabulary);

		// no front matter at all, and the <LS> scope is wrong too
		checker.checkFile(this.targetDir.resolve("doc.md"), "<LS to=\"j\">\n\n# Titulek\n\nObsah.\n\n</LS>\n");

		final List<StructuralError> errors = checker.getErrors();
		assertEquals(1, errors.size());
		assertEquals(StructuralError.StructuralErrorType.TAG_SCOPE_MISMATCH, errors.get(0).type());
	}
}
