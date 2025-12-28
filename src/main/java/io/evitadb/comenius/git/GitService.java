package io.evitadb.comenius.git;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for Git operations required by the translation workflow.
 * Executes git commands via ProcessBuilder to avoid additional dependencies.
 * All paths are handled relative to the repository root.
 */
public final class GitService {

	private static final long COMMAND_TIMEOUT_SECONDS = 30;

	@Nonnull
	private final Path repositoryRoot;

	/**
	 * Creates a GitService for the specified repository root.
	 *
	 * @param repositoryRoot the root directory of the git repository (must contain .git)
	 * @throws IllegalArgumentException if repositoryRoot is null
	 */
	public GitService(@Nonnull Path repositoryRoot) {
		Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
		this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
	}

	/**
	 * Gets the current HEAD commit hash for a specific file.
	 * Uses: `git log -1 --format=%H -- <file>`
	 *
	 * @param file the file path (absolute or relative to repository root)
	 * @return the commit hash, or empty if file is not tracked
	 * @throws IOException if git command execution fails
	 */
	@Nonnull
	public Optional<String> getCurrentCommitHash(@Nonnull Path file) throws IOException {
		final String relativePath = getRelativePath(file);
		final String output = executeGitCommand("log", "-1", "--format=%H", "--", relativePath);
		if (output.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(output.trim());
	}

	/**
	 * Checks if a file is fully committed (no uncommitted changes and tracked by git).
	 * Uses: `git status --porcelain -- <file>`
	 *
	 * @param file the file path (absolute or relative to repository root)
	 * @return true if file is tracked and has no uncommitted changes
	 * @throws IOException if git command execution fails
	 */
	public boolean isFileCommitted(@Nonnull Path file) throws IOException {
		final String relativePath = getRelativePath(file);
		final String output = executeGitCommand("status", "--porcelain", "--", relativePath);
		// Empty output means file is tracked and clean
		return output.isBlank();
	}

	/**
	 * Gets the unified diff between two commits for a specific file.
	 * Uses: `git diff <fromCommit>..<toCommit> -- <file>`
	 *
	 * @param file       the file path (absolute or relative to repository root)
	 * @param fromCommit the starting commit hash
	 * @param toCommit   the ending commit hash (typically HEAD)
	 * @return the unified diff output, or empty if no changes
	 * @throws IOException if git command execution fails
	 */
	@Nonnull
	public Optional<String> getDiff(
		@Nonnull Path file,
		@Nonnull String fromCommit,
		@Nonnull String toCommit
	) throws IOException {
		Objects.requireNonNull(fromCommit, "fromCommit must not be null");
		Objects.requireNonNull(toCommit, "toCommit must not be null");
		final String relativePath = getRelativePath(file);
		final String commitRange = fromCommit + ".." + toCommit;
		final String output = executeGitCommand("diff", commitRange, "--", relativePath);
		if (output.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(output);
	}

	/**
	 * Gets the commit count between two commits for a specific file.
	 * Uses: `git rev-list --count <fromCommit>..<toCommit> -- <file>`
	 *
	 * @param file       the file path (absolute or relative to repository root)
	 * @param fromCommit the starting commit hash
	 * @param toCommit   the ending commit hash
	 * @return number of commits affecting this file
	 * @throws IOException if git command execution fails
	 */
	public int getCommitCount(
		@Nonnull Path file,
		@Nonnull String fromCommit,
		@Nonnull String toCommit
	) throws IOException {
		Objects.requireNonNull(fromCommit, "fromCommit must not be null");
		Objects.requireNonNull(toCommit, "toCommit must not be null");
		final String relativePath = getRelativePath(file);
		final String commitRange = fromCommit + ".." + toCommit;
		final String output = executeGitCommand("rev-list", "--count", commitRange, "--", relativePath);
		if (output.isBlank()) {
			return 0;
		}
		return Integer.parseInt(output.trim());
	}

	/**
	 * Gets the file content at a specific commit.
	 * Uses: `git show <commit>:<file>`
	 *
	 * @param file   the file path (absolute or relative to repository root)
	 * @param commit the commit hash
	 * @return file content at that commit, or empty if not found
	 * @throws IOException if git command execution fails
	 */
	@Nonnull
	public Optional<String> getFileAtCommit(@Nonnull Path file, @Nonnull String commit) throws IOException {
		Objects.requireNonNull(commit, "commit must not be null");
		final String relativePath = getRelativePath(file);
		final String fileSpec = commit + ":" + relativePath;
		try {
			final String output = executeGitCommand("show", fileSpec);
			if (output.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(output);
		} catch (IOException e) {
			// git show returns error if file doesn't exist at commit
			if (e.getMessage().contains("exit code")) {
				return Optional.empty();
			}
			throw e;
		}
	}

	/**
	 * Builds a complete CommitInfo for a file, gathering current commit, diff, and original source
	 * if a previous translation commit is provided.
	 *
	 * @param file             the file path (absolute or relative to repository root)
	 * @param translatedCommit the commit hash from existing translation (null for new files)
	 * @return CommitInfo with all gathered information, or empty if file is not tracked
	 * @throws IOException if git command execution fails
	 */
	@Nonnull
	public Optional<CommitInfo> buildCommitInfo(
		@Nonnull Path file,
		@Nullable String translatedCommit
	) throws IOException {
		Objects.requireNonNull(file, "file must not be null");

		// Get current commit hash
		final Optional<String> currentCommitOpt = getCurrentCommitHash(file);
		if (currentCommitOpt.isEmpty()) {
			return Optional.empty();
		}
		final String currentCommit = currentCommitOpt.get();

		// If no previous translation, return info for new file
		if (translatedCommit == null) {
			return Optional.of(new CommitInfo(currentCommit, null, 0, null, null));
		}

		// If same commit, file is up-to-date
		if (translatedCommit.equals(currentCommit)) {
			return Optional.of(new CommitInfo(currentCommit, translatedCommit, 0, null, null));
		}

		// Gather diff, commit count, and original source for incremental update
		final Optional<String> diffOpt = getDiff(file, translatedCommit, currentCommit);
		final int commitCount = getCommitCount(file, translatedCommit, currentCommit);
		final Optional<String> originalSourceOpt = getFileAtCommit(file, translatedCommit);

		return Optional.of(new CommitInfo(
			currentCommit,
			translatedCommit,
			commitCount,
			diffOpt.orElse(null),
			originalSourceOpt.orElse(null)
		));
	}

	/**
	 * Converts an absolute path to a path relative to the repository root.
	 *
	 * @param file the file path to convert
	 * @return relative path as a string
	 */
	@Nonnull
	private String getRelativePath(@Nonnull Path file) {
		Objects.requireNonNull(file, "file must not be null");
		final Path absoluteFile = file.toAbsolutePath().normalize();
		if (absoluteFile.startsWith(this.repositoryRoot)) {
			return this.repositoryRoot.relativize(absoluteFile).toString();
		}
		return absoluteFile.toString();
	}

	/**
	 * Executes a git command and returns its output.
	 *
	 * @param args the git command arguments (without "git" prefix)
	 * @return the command output (stdout)
	 * @throws IOException if command execution fails or returns non-zero exit code
	 */
	@Nonnull
	private String executeGitCommand(@Nonnull String... args) throws IOException {
		final List<String> command = new ArrayList<>();
		command.add("git");
		for (final String arg : args) {
			command.add(arg);
		}

		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(this.repositoryRoot.toFile());
		processBuilder.redirectErrorStream(false);

		final Process process = processBuilder.start();

		final StringBuilder stdout = new StringBuilder();
		final StringBuilder stderr = new StringBuilder();

		// Read stdout
		try (final BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
		)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (stdout.length() > 0) {
					stdout.append("\n");
				}
				stdout.append(line);
			}
		}

		// Read stderr
		try (final BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)
		)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (stderr.length() > 0) {
					stderr.append("\n");
				}
				stderr.append(line);
			}
		}

		try {
			final boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (!completed) {
				process.destroyForcibly();
				throw new IOException("Git command timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds");
			}

			final int exitCode = process.exitValue();
			if (exitCode != 0) {
				// For some commands like git show, non-zero exit is expected for missing files
				throw new IOException(
					"Git command failed with exit code " + exitCode + ": " + stderr
				);
			}

			return stdout.toString();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Git command interrupted", e);
		}
	}
}
