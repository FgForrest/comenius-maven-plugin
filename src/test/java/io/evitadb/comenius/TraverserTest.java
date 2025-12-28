package io.evitadb.comenius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Traverser should visit files matching regex in deterministic order and pass full contents")
public class TraverserTest {
	@Test
	@DisplayName("visits files in lexicographical order")
	public void shouldVisitFilesInOrderWhenPatternMatches() throws Exception {
		final Path root = Files.createTempDirectory("traverser-test-");
		try {
			// Build structure:
			// root/a/one.md
			// root/a/two.txt (ignored)
			// root/b/sub/three.MD (case-insensitive match)
			// root/z-four.md
			final Path dirA = Files.createDirectories(root.resolve("a"));
			final Path dirB = Files.createDirectories(root.resolve("b/sub"));
			final Path f1 = write(dirA.resolve("one.md"), "ONE\nLINE\n");
			write(dirA.resolve("two.txt"), "TWO");
			final Path f3 = write(dirB.resolve("three.MD"), "THREE");
			final Path f4 = write(root.resolve("z-four.md"), "Z-FOUR");

			final List<Path> visited = new ArrayList<>();
			final List<String> contents = new ArrayList<>();
			final List<String> instructionsList = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nullable final String instructions) {
					visited.add(file);
					contents.add(content);
					instructionsList.add(instructions);
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, null, visitor);
			traverser.traverse();

			// Expect lexicographic path order
			final List<Path> expected = List.of(f1, f3, f4);
			assertEquals(expected, visited, "Visited files order");
			assertEquals(List.of("ONE\nLINE\n", "THREE", "Z-FOUR"), contents, "File contents");
			// No instruction files, so all should be null
			for (final String instr : instructionsList) {
				assertNull(instr, "Instructions should be null when no .comenius-instructions files exist");
			}
		} finally {
			// Cleanup
			deleteRecursively(root);
		}
	}

	private static Path write(final Path path, final String text) throws IOException {
		Files.write(path, text.getBytes(StandardCharsets.UTF_8));
		return path;
	}

	private static void deleteRecursively(final Path path) throws IOException {
		if (Files.notExists(path)) return;
		if (Files.isDirectory(path)) {
			try (var s = Files.list(path)) {
				s.forEach(p -> {
					try {
						deleteRecursively(p);
					} catch (IOException e) {
						// ignore in cleanup
					}
				});
			}
		}
		Files.deleteIfExists(path);
	}

	@Test
	@DisplayName("accumulates instructions from parent directories")
	public void shouldAccumulateInstructionsFromParentDirectories() throws Exception {
		final Path root = Files.createTempDirectory("traverser-instr-accum-");
		try {
			// Structure:
			// root/.comenius-instructions (contains "ROOT-CONTENT")
			// root/a/.comenius-instructions (contains "A-CONTENT")
			// root/a/b/.comenius-instructions (contains "B-CONTENT")
			// root/a/x.md, root/a/b/y.md
			Files.write(root.resolve(".comenius-instructions"), "ROOT-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path dirA = Files.createDirectories(root.resolve("a"));
			Files.write(dirA.resolve(".comenius-instructions"), "A-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path dirB = Files.createDirectories(dirA.resolve("b"));
			Files.write(dirB.resolve(".comenius-instructions"), "B-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path fx = write(dirA.resolve("x.md"), "X");
			final Path fy = write(dirB.resolve("y.md"), "Y");

			final List<Path> visited = new ArrayList<>();
			final List<String> contents = new ArrayList<>();
			final List<String> instructionsList = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nullable final String instructions) {
					visited.add(file);
					contents.add(content);
					instructionsList.add(instructions);
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, null, visitor);
			traverser.traverse();

			// Determine expectations in traversal order (directories before files)
			final List<Path> expectedVisited = List.of(fy, fx);
			assertEquals(expectedVisited, visited, "Visited files order with instructions");
			assertEquals(List.of("Y", "X"), contents, "File contents");
			// y.md is in b/, so it gets ROOT + A + B accumulated
			// x.md is in a/, so it gets ROOT + A accumulated
			assertEquals("ROOT-CONTENT\n\nA-CONTENT\n\nB-CONTENT", instructionsList.get(0), "Instructions for y.md");
			assertEquals("ROOT-CONTENT\n\nA-CONTENT", instructionsList.get(1), "Instructions for x.md");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("resets instructions when .comenius-instructions.replace is encountered")
	public void shouldResetInstructionsWhenReplaceFileEncountered() throws Exception {
		final Path root = Files.createTempDirectory("traverser-instr-replace-");
		try {
			// Structure:
			// root/.comenius-instructions (contains "ROOT-CONTENT")
			// root/a/.comenius-instructions.replace (contains "REPL-CONTENT")
			// root/a/.comenius-instructions (contains "A-CONTENT")
			// root/a/b/.comenius-instructions (contains "B-CONTENT")
			// root/a/x.md, root/a/b/y.md
			Files.write(root.resolve(".comenius-instructions"), "ROOT-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path dirA = Files.createDirectories(root.resolve("a"));
			Files.write(dirA.resolve(".comenius-instructions.replace"), "REPL-CONTENT".getBytes(StandardCharsets.UTF_8));
			Files.write(dirA.resolve(".comenius-instructions"), "A-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path dirB = Files.createDirectories(dirA.resolve("b"));
			Files.write(dirB.resolve(".comenius-instructions"), "B-CONTENT".getBytes(StandardCharsets.UTF_8));
			final Path fx = write(dirA.resolve("x.md"), "X");
			final Path fy = write(dirB.resolve("y.md"), "Y");

			final List<Path> visited = new ArrayList<>();
			final List<String> contents = new ArrayList<>();
			final List<String> instructionsList = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nullable final String instructions) {
					visited.add(file);
					contents.add(content);
					instructionsList.add(instructions);
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, null, visitor);
			traverser.traverse();

			final List<Path> expectedVisited = List.of(fy, fx);
			assertEquals(expectedVisited, visited, "Visited files order with replace");
			assertEquals(List.of("Y", "X"), contents, "File contents");
			// y.md is in b/, replace resets at a/, so it gets REPL + A + B (no ROOT)
			// x.md is in a/, replace resets at a/, so it gets REPL + A (no ROOT)
			assertEquals("REPL-CONTENT\n\nA-CONTENT\n\nB-CONTENT", instructionsList.get(0), "Instructions for y.md after replace");
			assertEquals("REPL-CONTENT\n\nA-CONTENT", instructionsList.get(1), "Instructions for x.md after replace");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("skips excluded directories entirely")
	public void shouldSkipExcludedDirectories() throws Exception {
		final Path root = Files.createTempDirectory("traverser-exclude-dir-");
		try {
			// Structure:
			// root/docs/readme.md
			// root/assets/image.md (should be excluded)
			// root/other.md
			final Path dirDocs = Files.createDirectories(root.resolve("docs"));
			final Path dirAssets = Files.createDirectories(root.resolve("assets"));
			final Path f1 = write(dirDocs.resolve("readme.md"), "README");
			write(dirAssets.resolve("image.md"), "IMAGE");
			final Path f3 = write(root.resolve("other.md"), "OTHER");

			final List<Path> visited = new ArrayList<>();

			final Visitor visitor = (file, content, instructions) -> visited.add(file);

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final List<Pattern> exclusions = List.of(Pattern.compile(".*/assets/.*"));
			final Traverser traverser = new Traverser(root, pattern, exclusions, visitor);
			traverser.traverse();

			// Only docs/readme.md and other.md should be visited
			final List<Path> expected = List.of(f1, f3);
			assertEquals(expected, visited, "Excluded directories should be skipped");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("supports multiple exclusion patterns")
	public void shouldSupportMultipleExclusionPatterns() throws Exception {
		final Path root = Files.createTempDirectory("traverser-multi-exclude-");
		try {
			// Structure:
			// root/docs/readme.md
			// root/assets/asset.md (excluded)
			// root/images/img.md (excluded)
			// root/other.md
			final Path dirDocs = Files.createDirectories(root.resolve("docs"));
			final Path dirAssets = Files.createDirectories(root.resolve("assets"));
			final Path dirImages = Files.createDirectories(root.resolve("images"));
			final Path f1 = write(dirDocs.resolve("readme.md"), "README");
			write(dirAssets.resolve("asset.md"), "ASSET");
			write(dirImages.resolve("img.md"), "IMG");
			final Path f4 = write(root.resolve("other.md"), "OTHER");

			final List<Path> visited = new ArrayList<>();

			final Visitor visitor = (file, content, instructions) -> visited.add(file);

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final List<Pattern> exclusions = List.of(
				Pattern.compile(".*/assets/.*"),
				Pattern.compile(".*/images/.*")
			);
			final Traverser traverser = new Traverser(root, pattern, exclusions, visitor);
			traverser.traverse();

			// Only docs/readme.md and other.md should be visited
			final List<Path> expected = List.of(f1, f4);
			assertEquals(expected, visited, "Multiple exclusion patterns should work");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("works with empty exclusion list")
	public void shouldWorkWithEmptyExclusionList() throws Exception {
		final Path root = Files.createTempDirectory("traverser-empty-exclude-");
		try {
			// Structure:
			// root/docs/readme.md
			// root/other.md
			final Path dirDocs = Files.createDirectories(root.resolve("docs"));
			final Path f1 = write(dirDocs.resolve("readme.md"), "README");
			final Path f2 = write(root.resolve("other.md"), "OTHER");

			final List<Path> visited = new ArrayList<>();

			final Visitor visitor = (file, content, instructions) -> visited.add(file);

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			// Pass empty list - should work the same as null
			final Traverser traverser = new Traverser(root, pattern, List.of(), visitor);
			traverser.traverse();

			// All .md files should be visited
			final List<Path> expected = List.of(f1, f2);
			assertEquals(expected, visited, "Empty exclusion list should not affect traversal");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("excludes files matching pattern in non-excluded directories")
	public void shouldExcludeFilesMatchingPatternInNonExcludedDirectories() throws Exception {
		final Path root = Files.createTempDirectory("traverser-file-exclude-");
		try {
			// Structure:
			// root/docs/readme.md
			// root/docs/_draft.md (excluded by file pattern)
			// root/other.md
			final Path dirDocs = Files.createDirectories(root.resolve("docs"));
			final Path f1 = write(dirDocs.resolve("readme.md"), "README");
			write(dirDocs.resolve("_draft.md"), "DRAFT");
			final Path f3 = write(root.resolve("other.md"), "OTHER");

			final List<Path> visited = new ArrayList<>();

			final Visitor visitor = (file, content, instructions) -> visited.add(file);

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			// Exclude files starting with underscore
			final List<Pattern> exclusions = List.of(Pattern.compile(".*/_.*\\.md"));
			final Traverser traverser = new Traverser(root, pattern, exclusions, visitor);
			traverser.traverse();

			// Only readme.md and other.md should be visited
			final List<Path> expected = List.of(f1, f3);
			assertEquals(expected, visited, "Files matching exclusion pattern should be skipped");
		} finally {
			deleteRecursively(root);
		}
	}
}
