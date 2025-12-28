package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a Git-related validation error for a file.
 * Used to report files that are not properly committed to the repository.
 *
 * @param file the file with the Git status issue
 * @param type the type of Git error encountered
 */
public record GitError(
	@Nonnull Path file,
	@Nonnull GitErrorType type
) {

	/**
	 * Creates a new GitError with validation.
	 */
	public GitError {
		Objects.requireNonNull(file, "file must not be null");
		Objects.requireNonNull(type, "type must not be null");
	}

	/**
	 * Types of Git status errors that can be detected.
	 */
	public enum GitErrorType {
		/**
		 * File exists in the repository but has uncommitted changes (modified or staged).
		 */
		UNCOMMITTED_CHANGES,

		/**
		 * File is not tracked by Git at all.
		 */
		UNTRACKED
	}
}
