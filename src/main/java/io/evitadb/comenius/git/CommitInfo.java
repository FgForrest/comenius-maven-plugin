package io.evitadb.comenius.git;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable record holding commit tracking information for a translated file.
 * Used to determine whether a translation needs to be created or updated.
 *
 * @param currentCommit    the current HEAD commit hash for the source file (required, never null)
 * @param translatedCommit the commit hash from existing translation's front matter (null for new files)
 * @param commitCount      the number of commits between translatedCommit and currentCommit (0 for new files)
 * @param diff             the unified diff between the two commits (null for new files or when no changes)
 * @param originalSource   the source content at translatedCommit (null for new files)
 */
public record CommitInfo(
	@Nonnull String currentCommit,
	@Nullable String translatedCommit,
	int commitCount,
	@Nullable String diff,
	@Nullable String originalSource
) {

	/**
	 * Returns true if this represents a new file (no previous translation exists).
	 *
	 * @return true if translatedCommit is null, indicating no existing translation
	 */
	public boolean isNewFile() {
		return this.translatedCommit == null;
	}

	/**
	 * Returns true if the file needs updating (commits differ and there is a previous translation).
	 *
	 * @return true if there is a previous translation that is outdated
	 */
	public boolean needsUpdate() {
		return this.translatedCommit != null && !this.translatedCommit.equals(this.currentCommit);
	}

	/**
	 * Returns true if the file is up-to-date (same commit as previous translation).
	 *
	 * @return true if no update is needed
	 */
	public boolean isUpToDate() {
		return this.translatedCommit != null && this.translatedCommit.equals(this.currentCommit);
	}
}
