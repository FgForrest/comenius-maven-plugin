package io.evitadb.comenius.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CheckResult aggregates validation errors")
public class CheckResultTest {

	@Test
	@DisplayName("returns success when no errors")
	public void shouldReturnSuccessWhenNoErrors() {
		final CheckResult result = new CheckResult(List.of(), List.of());

		assertTrue(result.isSuccess());
		assertEquals(0, result.errorCount());
	}

	@Test
	@DisplayName("returns failure when git errors exist")
	public void shouldReturnFailureWhenGitErrorsExist() {
		final GitError error = new GitError(
			Path.of("/test/file.md"),
			GitError.GitErrorType.UNCOMMITTED_CHANGES
		);
		final CheckResult result = new CheckResult(List.of(error), List.of());

		assertFalse(result.isSuccess());
		assertEquals(1, result.errorCount());
	}

	@Test
	@DisplayName("returns failure when link errors exist")
	public void shouldReturnFailureWhenLinkErrorsExist() {
		final LinkError error = new LinkError(
			Path.of("/test/source.md"),
			"missing.md",
			Path.of("/test/missing.md"),
			null,
			LinkError.LinkErrorType.FILE_NOT_FOUND
		);
		final CheckResult result = new CheckResult(List.of(), List.of(error));

		assertFalse(result.isSuccess());
		assertEquals(1, result.errorCount());
	}

	@Test
	@DisplayName("counts all errors correctly")
	public void shouldCountAllErrorsCorrectly() {
		final GitError gitError1 = new GitError(
			Path.of("/test/file1.md"),
			GitError.GitErrorType.UNCOMMITTED_CHANGES
		);
		final GitError gitError2 = new GitError(
			Path.of("/test/file2.md"),
			GitError.GitErrorType.UNTRACKED
		);
		final LinkError linkError1 = new LinkError(
			Path.of("/test/source.md"),
			"missing.md",
			null,
			null,
			LinkError.LinkErrorType.FILE_NOT_FOUND
		);
		final LinkError linkError2 = new LinkError(
			Path.of("/test/source.md"),
			"other.md#section",
			Path.of("/test/other.md"),
			"section",
			LinkError.LinkErrorType.ANCHOR_NOT_FOUND
		);

		final CheckResult result = new CheckResult(
			List.of(gitError1, gitError2),
			List.of(linkError1, linkError2)
		);

		assertFalse(result.isSuccess());
		assertEquals(4, result.errorCount());
		assertEquals(2, result.gitErrors().size());
		assertEquals(2, result.linkErrors().size());
	}

	@Test
	@DisplayName("success factory method creates empty result")
	public void shouldCreateEmptyResultWithSuccessFactory() {
		final CheckResult result = CheckResult.success();

		assertTrue(result.isSuccess());
		assertEquals(0, result.errorCount());
		assertTrue(result.gitErrors().isEmpty());
		assertTrue(result.linkErrors().isEmpty());
	}

	@Test
	@DisplayName("creates defensive copies of error lists")
	public void shouldCreateDefensiveCopies() {
		final GitError error = new GitError(
			Path.of("/test/file.md"),
			GitError.GitErrorType.UNCOMMITTED_CHANGES
		);
		final List<GitError> mutableList = new java.util.ArrayList<>();
		mutableList.add(error);

		final CheckResult result = new CheckResult(mutableList, List.of());

		// Modify original list
		mutableList.clear();

		// Result should still have the error
		assertEquals(1, result.gitErrors().size());
	}
}
