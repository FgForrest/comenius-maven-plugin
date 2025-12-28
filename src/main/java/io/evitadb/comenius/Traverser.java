package io.evitadb.comenius;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Traverses a source directory recursively, finds files matching a regex pattern and
 * invokes a visitor with the full file contents and accumulated instructions.
 *
 * - Traversal order is deterministic: files are visited in lexicographical order of their paths.
 * - Entire file contents are read using UTF-8.
 * - Accumulates instructions from files named ".comenius-instructions" and
 *   ".comenius-instructions.replace" present in directories from the root to the file's parent.
 *   Each file directly contains custom translation instructions that are accumulated.
 *   When a ".comenius-instructions.replace" is encountered at some level, parent instructions are
 *   ignored and accumulation restarts from that level.
 */
public final class Traverser {

	@Nonnull
	private static final String INSTRUCTIONS_FILE = ".comenius-instructions";
	@Nonnull
	private static final String INSTRUCTIONS_REPLACE_FILE = ".comenius-instructions.replace";

	@Nonnull
	private final Path sourceDir;
	@Nonnull
	private final Pattern filePattern;
	@Nonnull
	private final List<Pattern> exclusionPatterns;
	@Nonnull
	private final Visitor visitor;

	/**
	 * Create a traverser.
	 *
	 * @param sourceDir         root directory to traverse
	 * @param filePattern       regex pattern for matching file paths (Path.toString())
	 * @param exclusionPatterns list of regex patterns for excluding directories/files
	 * @param visitor           callback to process file contents
	 */
	public Traverser(
		@Nonnull final Path sourceDir,
		@Nonnull final Pattern filePattern,
		@Nullable final List<Pattern> exclusionPatterns,
		@Nonnull final Visitor visitor
	) {
		this.sourceDir = sourceDir;
		this.filePattern = filePattern;
		this.exclusionPatterns = exclusionPatterns != null ? exclusionPatterns : List.of();
		this.visitor = visitor;
	}

	/**
	 * Perform recursive traversal and notify visitor for each matched file.
	 *
	 * @throws IOException when directory reading fails
	 */
	public void traverse() throws IOException {
		// Validate input early to avoid using exceptions for control flow later
		if (!Files.exists(this.sourceDir)) {
			throw new IOException("Source directory does not exist: " + this.sourceDir);
		}
		if (!Files.isDirectory(this.sourceDir)) {
			throw new IOException("Source path is not a directory: " + this.sourceDir);
		}

		// Collect all files to allow deterministic ordering
		final List<Path> files = new ArrayList<>();
		collectFiles(this.sourceDir, files);
		files.sort(Comparator.comparing(Path::toString));

		final Map<Path, String> dirInstructionsCache = new HashMap<>();
		for (final Path file : files) {
			final String p = file.toString();
			if (!this.filePattern.matcher(p).matches()) {
				continue;
			}
			// Skip files matching exclusion patterns (for file-level exclusions like .*/_.*\.md)
			if (isExcluded(p)) {
				continue;
			}
			final byte[] bytes = Files.readAllBytes(file); // single allocation for performance
			final String content = new String(bytes, StandardCharsets.UTF_8);
			final Path parent = file.getParent();
			final String instructions = parent == null ? null : computeInstructions(parent, dirInstructionsCache);
			this.visitor.visit(file, content, instructions);
		}
	}

	private void collectFiles(@Nonnull final Path dir, @Nonnull final List<Path> out) throws IOException {
		final List<Path> children = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			stream.forEach(children::add);
		}
		// Sort for deterministic recursion order (directories and files together)
		children.sort(Comparator.comparing(Path::toString));
		for (final Path child : children) {
			final BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
			if (attrs.isDirectory()) {
				// Skip excluded directories entirely for efficiency
				final String dirPath = child.toString();
				if (!isExcluded(dirPath) && !isExcluded(dirPath + "/")) {
					collectFiles(child, out);
				}
			} else if (attrs.isRegularFile()) {
				out.add(child);
			}
			// symlinks and other types are ignored deliberately
		}
	}

	/**
	 * Checks if the given path matches any exclusion pattern.
	 *
	 * @param path the path to check
	 * @return true if the path should be excluded
	 */
	private boolean isExcluded(@Nonnull final String path) {
		for (final Pattern pattern : this.exclusionPatterns) {
			if (pattern.matcher(path).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Computes accumulated instructions for a directory by reading `.comenius-instructions`
	 * files from parent directories down to the specified directory.
	 *
	 * @param dir   the directory to compute instructions for
	 * @param cache cache of already computed instructions per directory
	 * @return accumulated instructions or null if none found
	 * @throws IOException if reading instruction files fails
	 */
	@Nullable
	private String computeInstructions(@Nonnull final Path dir, @Nonnull final Map<Path, String> cache) throws IOException {
		if (cache.containsKey(dir)) {
			return cache.get(dir);
		}

		final Path parent = dir.getParent();
		// Start with parent's accumulated instructions unless this dir contains a replace marker
		final boolean replaceHere = Files.exists(dir.resolve(INSTRUCTIONS_REPLACE_FILE));
		final StringBuilder result = new StringBuilder();

		if (!replaceHere && parent != null) {
			final String parentInstr = computeInstructions(parent, cache);
			if (parentInstr != null) {
				result.append(parentInstr);
			}
		}

		// If replaceHere, we intentionally ignore parent instructions and start fresh,
		// but we also include the content from the replace file itself as starting instructions.
		if (replaceHere) {
			final Path replacePath = dir.resolve(INSTRUCTIONS_REPLACE_FILE);
			appendInstructionContent(replacePath, result);
		}

		// Append this directory's instructions if present
		final Path instrPath = dir.resolve(INSTRUCTIONS_FILE);
		if (Files.exists(instrPath)) {
			appendInstructionContent(instrPath, result);
		}

		// Cache the result (null if empty)
		final String instructions = result.length() > 0 ? result.toString() : null;
		cache.put(dir, instructions);
		return instructions;
	}

	/**
	 * Reads content from an instruction file and appends it to the result.
	 *
	 * @param instructionFile path to the instruction file
	 * @param result          StringBuilder to append content to
	 * @throws IOException if reading the file fails
	 */
	private void appendInstructionContent(@Nonnull final Path instructionFile, @Nonnull final StringBuilder result) throws IOException {
		final byte[] bytes = Files.readAllBytes(instructionFile);
		final String content = new String(bytes, StandardCharsets.UTF_8).trim();
		if (!content.isEmpty()) {
			if (result.length() > 0) {
				result.append("\n\n");
			}
			result.append(content);
		}
	}
}
