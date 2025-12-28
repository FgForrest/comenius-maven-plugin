package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of the check action containing all validation errors.
 * Aggregates both Git status errors and link validation errors for reporting.
 *
 * @param gitErrors  list of files with Git status issues (uncommitted or untracked)
 * @param linkErrors list of broken link errors (missing files or anchors)
 */
public record CheckResult(
	@Nonnull List<GitError> gitErrors,
	@Nonnull List<LinkError> linkErrors
) {

	/**
	 * Creates a new CheckResult with validation and defensive copying.
	 */
	public CheckResult {
		Objects.requireNonNull(gitErrors, "gitErrors must not be null");
		Objects.requireNonNull(linkErrors, "linkErrors must not be null");
		gitErrors = List.copyOf(gitErrors);
		linkErrors = List.copyOf(linkErrors);
	}

	/**
	 * Returns true if there are no errors of any kind.
	 *
	 * @return true if both gitErrors and linkErrors are empty
	 */
	public boolean isSuccess() {
		return this.gitErrors.isEmpty() && this.linkErrors.isEmpty();
	}

	/**
	 * Returns the total count of all errors.
	 *
	 * @return sum of git errors and link errors
	 */
	public int errorCount() {
		return this.gitErrors.size() + this.linkErrors.size();
	}

	/**
	 * Creates an empty successful result with no errors.
	 *
	 * @return a CheckResult with empty error lists
	 */
	@Nonnull
	public static CheckResult success() {
		return new CheckResult(List.of(), List.of());
	}
}
