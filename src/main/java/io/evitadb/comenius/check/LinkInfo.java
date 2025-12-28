package io.evitadb.comenius.check;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Parsed information from a markdown link.
 * Represents the decomposed parts of a link destination for validation purposes.
 *
 * @param destination the full original link destination string
 * @param path        the path portion of the link (null if anchor-only link like `#section`)
 * @param anchor      the anchor portion without the `#` prefix (null if no anchor)
 * @param isExternal  true if this is an external URL (http/https/mailto/tel/ftp)
 * @param isAbsolute  true if the path starts with `/` (resolved from Git root)
 */
public record LinkInfo(
	@Nonnull String destination,
	@Nullable String path,
	@Nullable String anchor,
	boolean isExternal,
	boolean isAbsolute
) {

	/**
	 * Creates a new LinkInfo with validation.
	 */
	public LinkInfo {
		Objects.requireNonNull(destination, "destination must not be null");
	}

	/**
	 * Returns true if this is an anchor-only link (e.g., `#section`).
	 *
	 * @return true if path is null but anchor is present
	 */
	public boolean isAnchorOnly() {
		return this.path == null && this.anchor != null;
	}

	/**
	 * Returns true if this link should be validated.
	 * External links are skipped since we don't do network validation.
	 *
	 * @return true if the link should be validated
	 */
	public boolean shouldValidate() {
		return !this.isExternal;
	}

	/**
	 * Parses a link destination string into a structured LinkInfo object.
	 *
	 * @param destination the raw link destination from markdown
	 * @return parsed LinkInfo with all components extracted
	 */
	@Nonnull
	public static LinkInfo parse(@Nonnull String destination) {
		Objects.requireNonNull(destination, "destination must not be null");

		// Check for external links
		if (isExternalLink(destination)) {
			return new LinkInfo(destination, null, null, true, false);
		}

		// Parse path and anchor
		final int hashIndex = destination.indexOf('#');
		if (hashIndex == 0) {
			// Anchor-only link: #section
			final String anchor = destination.substring(1);
			return new LinkInfo(destination, null, anchor, false, false);
		} else if (hashIndex > 0) {
			// Path with anchor: file.md#section
			final String path = destination.substring(0, hashIndex);
			final String anchor = destination.substring(hashIndex + 1);
			final boolean isAbsolute = path.startsWith("/");
			return new LinkInfo(destination, path, anchor, false, isAbsolute);
		} else {
			// Path only: file.md or ../dir/file.md or /absolute/path.md
			final boolean isAbsolute = destination.startsWith("/");
			return new LinkInfo(destination, destination, null, false, isAbsolute);
		}
	}

	/**
	 * Checks if the destination is an external link that should not be validated.
	 *
	 * @param destination the link destination to check
	 * @return true if external (http, https, mailto, tel, ftp, protocol-relative)
	 */
	private static boolean isExternalLink(@Nonnull String destination) {
		final String lower = destination.toLowerCase();
		return lower.startsWith("http://") ||
			lower.startsWith("https://") ||
			lower.startsWith("mailto:") ||
			lower.startsWith("tel:") ||
			lower.startsWith("ftp://") ||
			lower.startsWith("//");
	}
}
