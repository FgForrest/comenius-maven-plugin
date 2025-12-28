package io.evitadb.comenius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Traverser should visit files matching regex in deterministic order and pass full contents")
public class TraverserTest {
	@Test
	@DisplayName("shouldVisitFilesInOrderWhenPatternMatches")
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
			final List<List<Path>> instructionFiles = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nonnull final java.util.Collection<Path> instructionFilesForFile) {
					visited.add(file);
					contents.add(content);
					instructionFiles.add(List.copyOf(instructionFilesForFile));
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, visitor);
			traverser.traverse();

			// Expect lexicographic path order
			final List<Path> expected = List.of(f1, f3, f4);
			assertEquals(expected, visited, "Visited files order");
			assertEquals(List.of("ONE\nLINE\n", "THREE", "Z-FOUR"), contents, "File contents");
			assertEquals(List.of(List.of(), List.of(), List.of()), instructionFiles, "Instruction files should be empty");
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
	@DisplayName("shouldAccumulateInstructionsFromParentDirectories")
	public void shouldAccumulateInstructionsFromParentDirectories() throws Exception {
		final Path root = Files.createTempDirectory("traverser-instr-accum-");
		try {
			// Structure:
			// root/.comenius-instructions => ROOT.txt
			// root/a/.comenius-instructions => A.txt
			// root/a/b/.comenius-instructions => B.txt
			// root/a/x.md, root/a/b/y.md
			final Path rootInstr = write(root.resolve("ROOT.txt"), "ROOT-CONTENT");
			Files.write(root.resolve(".comenius-instructions"), "ROOT.txt".getBytes(StandardCharsets.UTF_8));
			final Path dirA = Files.createDirectories(root.resolve("a"));
			final Path aInstr = write(dirA.resolve("A.txt"), "A-CONTENT");
			Files.write(dirA.resolve(".comenius-instructions"), "A.txt".getBytes(StandardCharsets.UTF_8));
			final Path dirB = Files.createDirectories(dirA.resolve("b"));
			final Path bInstr = write(dirB.resolve("B.txt"), "B-CONTENT");
			Files.write(dirB.resolve(".comenius-instructions"), "B.txt".getBytes(StandardCharsets.UTF_8));
			final Path fx = write(dirA.resolve("x.md"), "X");
			final Path fy = write(dirB.resolve("y.md"), "Y");

			final List<Path> visited = new ArrayList<>();
			final List<String> contents = new ArrayList<>();
			final List<List<Path>> instructionFiles = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nonnull final java.util.Collection<Path> instructionFilesForFile) {
					visited.add(file);
					contents.add(content);
					instructionFiles.add(List.copyOf(instructionFilesForFile));
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, visitor);
			traverser.traverse();

			// Determine expectations in traversal order (directories before files)
			final List<Path> expectedVisited = List.of(fy, fx);
			assertEquals(expectedVisited, visited, "Visited files order with instructions");
			assertEquals(List.of("Y", "X"), contents, "File contents");
			assertEquals(List.of(List.of(rootInstr, aInstr, bInstr), List.of(rootInstr, aInstr)), instructionFiles, "Accumulated instruction files");
		} finally {
			deleteRecursively(root);
		}
	}

	@Test
	@DisplayName("shouldResetInstructionsWhenReplaceFileEncountered")
	public void shouldResetInstructionsWhenReplaceFileEncountered() throws Exception {
		final Path root = Files.createTempDirectory("traverser-instr-replace-");
		try {
			// Structure:
			// root/.comenius-instructions => ROOT.txt
			// root/a/.comenius-instructions.replace => REPL.txt
			// root/a/.comenius-instructions => A.txt
			// root/a/b/.comenius-instructions => B.txt
			// root/a/x.md, root/a/b/y.md
			final Path rootInstr = write(root.resolve("ROOT.txt"), "ROOT-CONTENT");
			Files.write(root.resolve(".comenius-instructions"), "ROOT.txt".getBytes(StandardCharsets.UTF_8));
			final Path dirA = Files.createDirectories(root.resolve("a"));
			final Path replInstr = write(dirA.resolve("REPL.txt"), "REPL-CONTENT");
			Files.write(dirA.resolve(".comenius-instructions.replace"), "REPL.txt".getBytes(StandardCharsets.UTF_8));
			final Path aInstr = write(dirA.resolve("A.txt"), "A-CONTENT");
			Files.write(dirA.resolve(".comenius-instructions"), "A.txt".getBytes(StandardCharsets.UTF_8));
			final Path dirB = Files.createDirectories(dirA.resolve("b"));
			final Path bInstr = write(dirB.resolve("B.txt"), "B-CONTENT");
			Files.write(dirB.resolve(".comenius-instructions"), "B.txt".getBytes(StandardCharsets.UTF_8));
			final Path fx = write(dirA.resolve("x.md"), "X");
			final Path fy = write(dirB.resolve("y.md"), "Y");

			final List<Path> visited = new ArrayList<>();
			final List<String> contents = new ArrayList<>();
			final List<List<Path>> instructionFiles = new ArrayList<>();

			final Visitor visitor = new Visitor() {
				@Override
				public void visit(@Nonnull final Path file, @Nonnull final String content, @Nonnull final java.util.Collection<Path> instructionFilesForFile) {
					visited.add(file);
					contents.add(content);
					instructionFiles.add(List.copyOf(instructionFilesForFile));
				}
			};

			final Pattern pattern = Pattern.compile("(?i).*\\.md");
			final Traverser traverser = new Traverser(root, pattern, visitor);
			traverser.traverse();

			final List<Path> expectedVisited = List.of(fy, fx);
			assertEquals(expectedVisited, visited, "Visited files order with replace");
			assertEquals(List.of("Y", "X"), contents, "File contents");
			assertEquals(List.of(List.of(replInstr, aInstr, bInstr), List.of(replInstr, aInstr)), instructionFiles, "Reset and accumulate after replace");
		} finally {
			deleteRecursively(root);
		}
	}
}
