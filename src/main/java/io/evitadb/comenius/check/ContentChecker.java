package io.evitadb.comenius.check;

import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.MarkdownDocument;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates markdown files for Git status and link integrity.
 * Collects all errors for batch reporting at the end of validation.
 */
public final class ContentChecker {

	@Nonnull
	private final GitService gitService;
	@Nonnull
	private final Path sourceDir;
	@Nonnull
	private final Path gitRoot;
	@Nonnull
	private final List<GitError> gitErrors = new ArrayList<>();
	@Nonnull
	private final List<LinkError> linkErrors = new ArrayList<>();
	@Nonnull
	private final Map<Path, Set<String>> anchorCache = new HashMap<>();

	/**
	 * Creates a ContentChecker for validating files.
	 *
	 * @param gitService the Git service for status checks
	 * @param sourceDir  the source directory being checked (for relative path display)
	 * @param gitRoot    the Git repository root (for resolving absolute paths)
	 */
	public ContentChecker(
		@Nonnull GitService gitService,
		@Nonnull Path sourceDir,
		@Nonnull Path gitRoot
	) {
		this.gitService = Objects.requireNonNull(gitService, "gitService must not be null");
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null")
			.toAbsolutePath().normalize();
		this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot must not be null")
			.toAbsolutePath().normalize();
	}

	/**
	 * Checks a single file for Git status and link validity.
	 *
	 * @param file    the file to check
	 * @param content the file content
	 */
	public void checkFile(@Nonnull Path file, @Nonnull String content) {
		Objects.requireNonNull(file, "file must not be null");
		Objects.requireNonNull(content, "content must not be null");

		final Path normalizedFile = file.toAbsolutePath().normalize();

		// Git status check
		checkGitStatus(normalizedFile);

		// Link validation
		checkLinks(normalizedFile, content);
	}

	/**
	 * Returns the collected check result with all errors.
	 *
	 * @return aggregated CheckResult with git and link errors
	 */
	@Nonnull
	public CheckResult getResult() {
		return new CheckResult(List.copyOf(this.gitErrors), List.copyOf(this.linkErrors));
	}

	/**
	 * Checks Git status of a file and records any errors.
	 *
	 * @param file the file to check
	 */
	private void checkGitStatus(@Nonnull Path file) {
		try {
			final boolean committed = this.gitService.isFileCommitted(file);
			if (!committed) {
				// Determine if untracked or has uncommitted changes
				// If file has no commit history, it's untracked
				final boolean hasHistory = this.gitService.getCurrentCommitHash(file).isPresent();
				final GitError.GitErrorType type = hasHistory
					? GitError.GitErrorType.UNCOMMITTED_CHANGES
					: GitError.GitErrorType.UNTRACKED;
				this.gitErrors.add(new GitError(file, type));
			}
		} catch (IOException e) {
			// Treat IO errors as untracked (file not in git)
			this.gitErrors.add(new GitError(file, GitError.GitErrorType.UNTRACKED));
		}
	}

	/**
	 * Validates all links in a markdown file.
	 *
	 * @param file    the source file path
	 * @param content the markdown content
	 */
	private void checkLinks(@Nonnull Path file, @Nonnull String content) {
		final MarkdownDocument doc = new MarkdownDocument(content);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		for (final LinkInfo link : links) {
			if (!link.shouldValidate()) {
				// Skip external links
				continue;
			}

			if (link.isAnchorOnly()) {
				// Anchor within same document
				validateAnchor(file, content, link, file);
			} else if (link.path() != null) {
				// Path link (relative or absolute)
				validatePathLink(file, content, link);
			}
		}
	}

	/**
	 * Validates a path-based link (relative or absolute).
	 *
	 * @param sourceFile    the file containing the link
	 * @param sourceContent the source file content
	 * @param link          the link info to validate
	 */
	private void validatePathLink(
		@Nonnull Path sourceFile,
		@Nonnull String sourceContent,
		@Nonnull LinkInfo link
	) {
		// Decode URL-encoded path components
		final String decodedPath = decodeUrlPath(link.path());

		// Resolve the target path
		final Path targetPath;
		if (link.isAbsolute()) {
			// Absolute path: resolve from Git root
			// Remove leading slash and resolve from gitRoot
			final String pathWithoutSlash = decodedPath.startsWith("/")
				? decodedPath.substring(1)
				: decodedPath;
			targetPath = this.gitRoot.resolve(pathWithoutSlash).normalize();
		} else {
			// Relative path: resolve from source file's parent directory
			final Path sourceDir = sourceFile.getParent();
			if (sourceDir == null) {
				return;
			}
			targetPath = sourceDir.resolve(decodedPath).normalize();
		}

		// Check if target file exists
		if (!Files.exists(targetPath)) {
			this.linkErrors.add(new LinkError(
				sourceFile,
				link.destination(),
				targetPath,
				link.anchor(),
				LinkError.LinkErrorType.FILE_NOT_FOUND
			));
			return;
		}

		// If there's an anchor, validate it exists in target
		if (link.anchor() != null) {
			validateAnchor(sourceFile, sourceContent, link, targetPath);
		}
	}

	/**
	 * Validates that an anchor exists in the target document.
	 *
	 * @param sourceFile    the file containing the link
	 * @param sourceContent the source file content
	 * @param link          the link info with anchor to validate
	 * @param targetPath    the target file containing the expected heading
	 */
	private void validateAnchor(
		@Nonnull Path sourceFile,
		@Nonnull String sourceContent,
		@Nonnull LinkInfo link,
		@Nonnull Path targetPath
	) {
		try {
			final Set<String> anchors = getAnchors(targetPath, sourceFile, sourceContent);
			final String normalizedAnchor = normalizeAnchor(link.anchor());

			if (!anchors.contains(normalizedAnchor)) {
				this.linkErrors.add(new LinkError(
					sourceFile,
					link.destination(),
					targetPath,
					link.anchor(),
					LinkError.LinkErrorType.ANCHOR_NOT_FOUND
				));
			}
		} catch (IOException e) {
			// Cannot read target file - should already be reported as FILE_NOT_FOUND
			// if we got here from validatePathLink
		}
	}

	/**
	 * Gets the set of valid anchors for a file, using cache for efficiency.
	 *
	 * @param targetPath    the file to extract anchors from
	 * @param sourceFile    the source file (for same-document optimization)
	 * @param sourceContent the source content (for same-document optimization)
	 * @return set of valid anchor slugs
	 * @throws IOException if the target file cannot be read
	 */
	@Nonnull
	private Set<String> getAnchors(
		@Nonnull Path targetPath,
		@Nonnull Path sourceFile,
		@Nonnull String sourceContent
	) throws IOException {
		// Check cache first
		final Set<String> cached = this.anchorCache.get(targetPath);
		if (cached != null) {
			return cached;
		}

		// If target is the source file, use source content directly (avoid re-reading)
		final String content;
		if (targetPath.equals(sourceFile)) {
			content = sourceContent;
		} else {
			content = Files.readString(targetPath, StandardCharsets.UTF_8);
		}

		final MarkdownDocument doc = new MarkdownDocument(content);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());
		this.anchorCache.put(targetPath, anchors);
		return anchors;
	}

	/**
	 * Decodes URL-encoded path components.
	 *
	 * @param path the potentially URL-encoded path
	 * @return decoded path
	 */
	@Nonnull
	private static String decodeUrlPath(@Nonnull String path) {
		try {
			return URLDecoder.decode(path, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// Invalid encoding - return original
			return path;
		}
	}

	/**
	 * Normalizes an anchor for comparison.
	 * Anchors are compared case-insensitively and URL-decoded.
	 *
	 * @param anchor the anchor to normalize
	 * @return normalized anchor slug
	 */
	@Nonnull
	private static String normalizeAnchor(@Nonnull String anchor) {
		return decodeUrlPath(anchor).toLowerCase();
	}
}
