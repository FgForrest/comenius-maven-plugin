package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aligns old and new document sections using Longest Common Subsequence (LCS) on content hashes.
 * Unchanged sections (matching hashes) serve as anchors. Sections between anchors are classified
 * as MODIFIED, ADDED, or DELETED based on their positions.
 *
 * Algorithm:
 * 1. Compute LCS of content hash sequences to find unchanged anchors
 * 2. Walk through both section lists, using anchors as synchronization points
 * 3. Between anchors, pair up old/new sections as MODIFIED, then classify remainder as DELETED/ADDED
 */
public final class SectionAligner {

	private SectionAligner() {
		// utility class
	}

	/**
	 * Aligns old and new sections, producing alignment records ordered by new document position.
	 * DELETED sections are placed at the position where they were removed (before the next anchor).
	 *
	 * @param oldSections sections from the previously translated source document
	 * @param newSections sections from the current source document
	 * @return list of alignment records ordered by output position
	 */
	@Nonnull
	public static List<SectionAlignment> align(
		@Nonnull List<DocumentSection> oldSections,
		@Nonnull List<DocumentSection> newSections
	) {
		Objects.requireNonNull(oldSections, "oldSections must not be null");
		Objects.requireNonNull(newSections, "newSections must not be null");

		// Extract hash sequences
		final String[] oldHashes = new String[oldSections.size()];
		for (int i = 0; i < oldSections.size(); i++) {
			oldHashes[i] = oldSections.get(i).contentHash();
		}
		final String[] newHashes = new String[newSections.size()];
		for (int i = 0; i < newSections.size(); i++) {
			newHashes[i] = newSections.get(i).contentHash();
		}

		// Compute LCS indices
		final List<int[]> lcs = computeLcs(oldHashes, newHashes);

		// Build alignments by walking between LCS anchors
		final List<SectionAlignment> alignments = new ArrayList<>();
		int oldPos = 0;
		int newPos = 0;

		for (final int[] anchor : lcs) {
			final int oldAnchor = anchor[0];
			final int newAnchor = anchor[1];

			// Process gap before this anchor
			processGap(alignments, oldPos, oldAnchor, newPos, newAnchor);

			// Add the anchor itself as UNCHANGED
			alignments.add(new SectionAlignment(
				SectionAlignment.Type.UNCHANGED, oldAnchor, newAnchor
			));

			oldPos = oldAnchor + 1;
			newPos = newAnchor + 1;
		}

		// Process trailing gap after last anchor
		processGap(alignments, oldPos, oldHashes.length, newPos, newHashes.length);

		return alignments;
	}

	/**
	 * Processes a gap between two LCS anchors, classifying sections as MODIFIED, DELETED, or ADDED.
	 * Pairs up old/new sections as MODIFIED (min count), then classifies remainder.
	 *
	 * @param alignments output list to append to
	 * @param oldStart   start index in old sections (inclusive)
	 * @param oldEnd     end index in old sections (exclusive)
	 * @param newStart   start index in new sections (inclusive)
	 * @param newEnd     end index in new sections (exclusive)
	 */
	private static void processGap(
		@Nonnull List<SectionAlignment> alignments,
		int oldStart,
		int oldEnd,
		int newStart,
		int newEnd
	) {
		final int oldCount = oldEnd - oldStart;
		final int newCount = newEnd - newStart;
		final int paired = Math.min(oldCount, newCount);

		// Pair up as MODIFIED
		for (int i = 0; i < paired; i++) {
			alignments.add(new SectionAlignment(
				SectionAlignment.Type.MODIFIED, oldStart + i, newStart + i
			));
		}

		// Remaining old sections are DELETED
		for (int i = paired; i < oldCount; i++) {
			alignments.add(new SectionAlignment(
				SectionAlignment.Type.DELETED, oldStart + i, -1
			));
		}

		// Remaining new sections are ADDED
		for (int i = paired; i < newCount; i++) {
			alignments.add(new SectionAlignment(
				SectionAlignment.Type.ADDED, -1, newStart + i
			));
		}
	}

	/**
	 * Computes the Longest Common Subsequence of two hash arrays.
	 * Returns list of [oldIndex, newIndex] pairs representing matching positions.
	 *
	 * @param oldHashes hashes from old sections
	 * @param newHashes hashes from new sections
	 * @return LCS as list of index pairs in order
	 */
	@Nonnull
	private static List<int[]> computeLcs(@Nonnull String[] oldHashes, @Nonnull String[] newHashes) {
		final int m = oldHashes.length;
		final int n = newHashes.length;

		// Standard DP table for LCS length
		final int[][] dp = new int[m + 1][n + 1];
		for (int i = 1; i <= m; i++) {
			for (int j = 1; j <= n; j++) {
				if (oldHashes[i - 1].equals(newHashes[j - 1])) {
					dp[i][j] = dp[i - 1][j - 1] + 1;
				} else {
					dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
				}
			}
		}

		// Backtrack to find actual LCS pairs (append, then reverse to avoid O(n^2))
		final List<int[]> result = new ArrayList<>();
		int i = m;
		int j = n;
		while (i > 0 && j > 0) {
			if (oldHashes[i - 1].equals(newHashes[j - 1])) {
				result.add(new int[]{i - 1, j - 1});
				i--;
				j--;
			} else if (dp[i - 1][j] > dp[i][j - 1]) {
				i--;
			} else {
				j--;
			}
		}
		Collections.reverse(result);

		return result;
	}
}
