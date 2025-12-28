package io.evitadb.comenius;

import javax.annotation.Nonnull;
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
 * invokes a visitor with the full file contents and ordered instruction files.
 *
 * - Traversal order is deterministic: files are visited in lexicographical order of their paths.
 * - Entire file contents are read using UTF-8.
 * - Accumulates instruction files from special manifest files named ".comenius-instructions" and
 *   ".comenius-instructions.replace" present in directories from the root to the file's parent.
 *   Each manifest contains a comma-delimited list of filenames that should be composed in order.
 *   When a ".comenius-instructions.replace" is encountered at some level, parent manifests are
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
	private final Visitor visitor;

	/**
	 * Create a traverser.
	 *
	 * @param sourceDir   root directory to traverse
	 * @param filePattern regex pattern for matching file paths (Path.toString())
	 * @param visitor     callback to process file contents
	 */
	public Traverser(@Nonnull final Path sourceDir, @Nonnull final Pattern filePattern,
					 @Nonnull final Visitor visitor) {
		this.sourceDir = sourceDir;
		this.filePattern = filePattern;
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

		final Map<Path, List<Path>> dirInstructionsCache = new HashMap<>();
		for (final Path file : files) {
			final String p = file.toString();
			if (!this.filePattern.matcher(p).matches()) {
				continue;
			}
			final byte[] bytes = Files.readAllBytes(file); // single allocation for performance
			final String content = new String(bytes, StandardCharsets.UTF_8);
			final Path parent = file.getParent();
			final List<Path> instructionFiles = parent == null ? List.of() : computeInstructionFiles(parent, dirInstructionsCache);
			this.visitor.visit(file, content, instructionFiles);
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
				collectFiles(child, out);
			} else if (attrs.isRegularFile()) {
				out.add(child);
			}
			// symlinks and other types are ignored deliberately
		}
	}

	@Nonnull
	private List<Path> computeInstructionFiles(@Nonnull final Path dir, @Nonnull final Map<Path, List<Path>> cache) throws IOException {
		final List<Path> cached = cache.get(dir);
		if (cached != null) {
			return cached;
		}

		final Path parent = dir.getParent();
		// Start with parent's accumulated instruction files unless this dir contains a replace marker
		final boolean replaceHere = Files.exists(dir.resolve(INSTRUCTIONS_REPLACE_FILE));
		final List<Path> result = new ArrayList<>(8);
		if (!replaceHere && parent != null) {
			final List<Path> parentInstr = computeInstructionFiles(parent, cache);
			// copy to avoid aliasing cached lists
			result.addAll(parentInstr);
		}
		// If replaceHere, we intentionally ignore parent instructions and start fresh,
		// but we also include the files listed by the replace file itself as starting instructions.
		if (replaceHere) {
			final Path replacePath = dir.resolve(INSTRUCTIONS_REPLACE_FILE);
			appendManifestEntries(dir, replacePath, result);
		}

		// Append this directory's instructions if present
		final Path instrPath = dir.resolve(INSTRUCTIONS_FILE);
		if (Files.exists(instrPath)) {
			appendManifestEntries(dir, instrPath, result);
		}

		// Cache an immutable snapshot (defensive copy)
		final List<Path> snapshot = List.copyOf(result);
		cache.put(dir, snapshot);
		return snapshot;
	}

	private void appendManifestEntries(@Nonnull final Path dir, @Nonnull final Path manifest, @Nonnull final List<Path> out) throws IOException {
		final byte[] bytes = Files.readAllBytes(manifest);
		final String txt = new String(bytes, StandardCharsets.UTF_8);
		if (txt.isEmpty()) return;
		// Split by comma, trim whitespace around tokens; ignore blanks
		int start = 0;
		for (int i = 0; i <= txt.length(); i++) {
			final boolean atEnd = i == txt.length();
			if (atEnd || txt.charAt(i) == ',') {
				final String token = txt.substring(start, i).trim();
				if (!token.isEmpty()) {
					final Path p = dir.resolve(token);
					out.add(p);
				}
				start = i + 1;
			}
		}
	}
}
