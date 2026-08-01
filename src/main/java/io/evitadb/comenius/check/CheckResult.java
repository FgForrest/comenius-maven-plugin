package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of the check action containing all validation errors.
 * Aggregates Git status errors, link validation errors, and cross-language structural errors
 * for reporting.
 *
 * @param gitErrors        list of files with Git status issues (uncommitted or untracked)
 * @param linkErrors       list of broken link errors (missing files or anchors)
 * @param structuralErrors list of cross-language structural mismatches (tag scope, content loss)
 */
public record CheckResult(
	@Nonnull List<GitError> gitErrors,
	@Nonnull List<LinkError> linkErrors,
	@Nonnull List<StructuralError> structuralErrors
) {

	/**
	 * Creates a new CheckResult with validation and defensive copying.
	 */
	public CheckResult {
		Objects.requireNonNull(gitErrors, "gitErrors must not be null");
		Objects.requireNonNull(linkErrors, "linkErrors must not be null");
		Objects.requireNonNull(structuralErrors, "structuralErrors must not be null");
		gitErrors = List.copyOf(gitErrors);
		linkErrors = List.copyOf(linkErrors);
		structuralErrors = List.copyOf(structuralErrors);
	}

	/**
	 * Creates a CheckResult with no structural errors, for callers that only check Git status
	 * and links.
	 *
	 * @param gitErrors  list of files with Git status issues (uncommitted or untracked)
	 * @param linkErrors list of broken link errors (missing files or anchors)
	 */
	public CheckResult(@Nonnull List<GitError> gitErrors, @Nonnull List<LinkError> linkErrors) {
		this(gitErrors, linkErrors, List.of());
	}

	/**
	 * Returns true if there are no errors of any kind.
	 *
	 * @return true if gitErrors, linkErrors, and structuralErrors are all empty
	 */
	public boolean isSuccess() {
		return this.gitErrors.isEmpty() && this.linkErrors.isEmpty() && this.structuralErrors.isEmpty();
	}

	/**
	 * Returns the total count of all errors.
	 *
	 * @return sum of git errors, link errors, and structural errors
	 */
	public int errorCount() {
		return this.gitErrors.size() + this.linkErrors.size() + this.structuralErrors.size();
	}

	/**
	 * Creates an empty successful result with no errors.
	 *
	 * @return a CheckResult with empty error lists
	 */
	@Nonnull
	public static CheckResult success() {
		return new CheckResult(List.of(), List.of(), List.of());
	}
}
