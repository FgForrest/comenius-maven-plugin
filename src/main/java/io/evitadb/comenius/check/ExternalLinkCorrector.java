package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrects cross-document anchor references in already-translated files
 * after one or more documents have been re-translated with changed headings.
 *
 * When a document is re-translated, its heading anchors may change. Other
 * translated documents linking to the re-translated file may have stale anchors.
 * This corrector detects and fixes those stale references using the
 * {@link AnchorChangeSet} that tracks old-to-new anchor mappings.
 *
 * Handles both body content links and translatable front matter fields.
 */
public final class ExternalLinkCorrector {

	/**
	 * Pattern to match markdown links: `[text](destination)` or `![alt](destination)`.
	 * Group 1: The link prefix including brackets (e.g., `[text]` or `![alt]`)
	 * Group 2: The link destination
	 */
	private static final Pattern LINK_PATTERN = Pattern.compile(
		"(!?\\[[^\\]]*\\])\\(([^)]+)\\)"
	);

	@Nonnull
	private final Path targetDir;
	@Nullable
	private final List<String> translatableFrontMatterFields;
	@Nonnull
	private final Log log;

	/**
	 * Creates an ExternalLinkCorrector for the given target directory.
	 *
	 * @param targetDir                    the target directory containing translated files
	 * @param translatableFrontMatterFields field names in front matter that may contain links
	 * @param log                          Maven log for output
	 */
	public ExternalLinkCorrector(
		@Nonnull Path targetDir,
		@Nullable List<String> translatableFrontMatterFields,
		@Nonnull Log log
	) {
		this.targetDir = Objects.requireNonNull(targetDir, "targetDir must not be null")
			.toAbsolutePath().normalize();
		this.translatableFrontMatterFields = translatableFrontMatterFields;
		this.log = Objects.requireNonNull(log, "log must not be null");
	}

	/**
	 * Corrects anchor references in multiple external files in parallel.
	 *
	 * For each file in externalFiles:
	 * - Parse all links in body and translatable front matter fields
	 * - For each link with an anchor that targets a file in changedFiles,
	 *   use the {@link AnchorChangeSet} to correct the anchor
	 * - Return corrected content if any changes were made
	 *
	 * @param changedFiles  map of absolute normalized target file path to
	 *                      {@link AnchorChangeSet} (files whose anchors changed)
	 * @param externalFiles map of absolute normalized file path to content
	 *                      (translated files to scan, excluding just-translated ones)
	 * @param executor      executor for parallel processing
	 * @return list of correction results for files that had corrections
	 */
	@Nonnull
	public List<LinkCorrectionResult> correctAllParallel(
		@Nonnull Map<Path, AnchorChangeSet> changedFiles,
		@Nonnull Map<Path, String> externalFiles,
		@Nonnull Executor executor
	) {
		Objects.requireNonNull(changedFiles, "changedFiles must not be null");
		Objects.requireNonNull(externalFiles, "externalFiles must not be null");
		Objects.requireNonNull(executor, "executor must not be null");

		final List<CompletableFuture<LinkCorrectionResult>> futures =
			new ArrayList<>(externalFiles.size());

		for (final Map.Entry<Path, String> entry : externalFiles.entrySet()) {
			final Path file = entry.getKey();
			final String content = entry.getValue();
			futures.add(CompletableFuture.supplyAsync(
				() -> correctAnchorsInFile(file, content, changedFiles),
				executor
			));
		}

		final List<LinkCorrectionResult> results = new ArrayList<>(futures.size());
		for (final CompletableFuture<LinkCorrectionResult> future : futures) {
			results.add(future.join());
		}
		return results;
	}

	/**
	 * Corrects anchor references in a single external file.
	 *
	 * @param externalFile the file to scan and correct
	 * @param content      the file's content
	 * @param changedFiles map of changed files to their AnchorChangeSets
	 * @return correction result
	 */
	@Nonnull
	private LinkCorrectionResult correctAnchorsInFile(
		@Nonnull Path externalFile,
		@Nonnull String content,
		@Nonnull Map<Path, AnchorChangeSet> changedFiles
	) {
		final Path normalizedFile = externalFile.toAbsolutePath().normalize();
		final CorrectionContext context = new CorrectionContext();

		// Parse document for front matter handling
		final MarkdownDocument document = new MarkdownDocument(content);

		// Phase 1: Correct translatable front matter fields
		if (this.translatableFrontMatterFields != null) {
			final Map<String, List<String>> properties = document.getProperties();
			for (final String fieldName : this.translatableFrontMatterFields) {
				final List<String> values = properties.get(fieldName);
				if (values == null || values.isEmpty()) {
					continue;
				}
				final String originalValue = values.get(0);
				if (originalValue == null || originalValue.isBlank()) {
					continue;
				}
				final String correctedValue = replaceAnchorsInLinks(
					originalValue, normalizedFile, changedFiles, context
				);
				if (!correctedValue.equals(originalValue)) {
					document.setProperty(fieldName, correctedValue);
					context.frontMatterCorrections++;
				}
			}
		}

		// Phase 2: Correct body content
		final String bodyContent = document.getBodyContent();
		final String correctedBody = replaceAnchorsInLinks(
			bodyContent, normalizedFile, changedFiles, context
		);

		// Reconstruct full document
		final String correctedContent = document.serializeFrontMatter() + correctedBody;

		return new LinkCorrectionResult(
			normalizedFile,
			correctedContent,
			0,
			context.anchorCorrections,
			context.frontMatterCorrections,
			context.errors
		);
	}

	/**
	 * Scans content for markdown links and corrects anchors that point to
	 * changed files.
	 *
	 * @param content      the content to scan
	 * @param fromFile     the file containing the content (for path resolution)
	 * @param changedFiles map of changed files to their AnchorChangeSets
	 * @param context      correction context for tracking statistics
	 * @return content with corrected anchor references
	 */
	@Nonnull
	private String replaceAnchorsInLinks(
		@Nonnull String content,
		@Nonnull Path fromFile,
		@Nonnull Map<Path, AnchorChangeSet> changedFiles,
		@Nonnull CorrectionContext context
	) {
		final Matcher matcher = LINK_PATTERN.matcher(content);
		final StringBuilder result = new StringBuilder();
		int lastEnd = 0;

		while (matcher.find()) {
			result.append(content, lastEnd, matcher.start());

			final String linkPrefix = matcher.group(1);
			final String destination = matcher.group(2);

			final String corrected = correctDestination(
				destination, fromFile, changedFiles, context
			);

			result.append(linkPrefix).append("(").append(corrected).append(")");
			lastEnd = matcher.end();
		}

		result.append(content.substring(lastEnd));
		return result.toString();
	}

	/**
	 * Corrects a single link destination if it points to a changed file
	 * and has an anchor that needs updating.
	 *
	 * @param destination  the original link destination
	 * @param fromFile     the file containing the link
	 * @param changedFiles map of changed files
	 * @param context      correction context
	 * @return the corrected destination, or original if no correction needed
	 */
	@Nonnull
	private String correctDestination(
		@Nonnull String destination,
		@Nonnull Path fromFile,
		@Nonnull Map<Path, AnchorChangeSet> changedFiles,
		@Nonnull CorrectionContext context
	) {
		final LinkInfo linkInfo = LinkInfo.parse(destination);

		// Skip external, absolute, and anchor-only links
		if (linkInfo.isExternal() || linkInfo.isAbsolute() || linkInfo.isAnchorOnly()) {
			return destination;
		}

		// Must have both a path and an anchor to need correction
		final String anchor = linkInfo.anchor();
		if (anchor == null || anchor.isEmpty()) {
			return destination;
		}

		// Resolve the link target to an absolute path
		final Path resolvedTarget = resolveTargetPath(linkInfo.path(), fromFile);
		if (resolvedTarget == null) {
			return destination;
		}

		// Check if this target is one of the changed files
		final AnchorChangeSet changeSet = changedFiles.get(resolvedTarget);
		if (changeSet == null) {
			return destination;
		}

		// Attempt to correct the anchor
		final String correctedAnchor = changeSet.correctAnchor(anchor);
		if (correctedAnchor == null) {
			// No match found — leave unchanged and warn
			this.log.warn("Stale anchor '#" + anchor + "' in "
				+ fromFile.getFileName()
				+ " pointing to " + resolvedTarget.getFileName()
				+ " — no matching anchor found in new translation");
			context.errors.add("Stale anchor '#" + anchor + "' in "
				+ fromFile.getFileName()
				+ " pointing to " + resolvedTarget.getFileName()
				+ " — no matching anchor found");
			return destination;
		}

		if (correctedAnchor.equals(anchor)) {
			// Anchor is unchanged — no correction needed
			return destination;
		}

		// Build corrected destination: preserve everything before '#', replace anchor
		context.anchorCorrections++;
		final int hashIdx = destination.indexOf('#');
		final String beforeAnchor = destination.substring(0, hashIdx);
		return beforeAnchor + "#" + correctedAnchor;
	}

	/**
	 * Resolves a relative link path from a file to an absolute normalized path
	 * within the target directory. Handles extensionless links by trying with
	 * `.md` appended.
	 *
	 * @param linkPath the raw link path from markdown (may include query params)
	 * @param fromFile the file containing the link
	 * @return resolved absolute normalized path, or null if not resolvable
	 */
	@Nullable
	private Path resolveTargetPath(
		@Nonnull String linkPath,
		@Nonnull Path fromFile
	) {
		// Decode URL-encoded path
		final String decodedPath = URLDecoder.decode(linkPath, StandardCharsets.UTF_8);

		// Strip query parameters
		final int queryIndex = decodedPath.indexOf('?');
		final String fileSystemPath = queryIndex >= 0
			? decodedPath.substring(0, queryIndex)
			: decodedPath;

		// Resolve relative to the file's parent directory
		final Path fromDir = fromFile.getParent();
		final Path resolved = fromDir.resolve(fileSystemPath).normalize();

		// Check if it's within the target directory
		if (!resolved.startsWith(this.targetDir)) {
			return null;
		}

		// Try as-is first
		if (resolved.toString().endsWith(".md")) {
			return resolved;
		}

		// Try with .md extension for extensionless links
		final String fileName = resolved.getFileName().toString();
		if (!fileName.contains(".")) {
			final Path withMd = resolved.resolveSibling(fileName + ".md")
				.toAbsolutePath().normalize();
			if (withMd.startsWith(this.targetDir)) {
				return withMd;
			}
		}

		return null;
	}

	/**
	 * Mutable context for tracking corrections in a single file.
	 */
	private static final class CorrectionContext {
		int anchorCorrections = 0;
		int frontMatterCorrections = 0;
		@Nonnull
		final List<String> errors = new ArrayList<>();
	}
}
