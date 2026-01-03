package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.apache.maven.plugin.logging.Log;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrects relative links in translated markdown files.
 *
 * Handles two types of corrections:
 * 1. **Asset links** - Recalculates relative paths from target location to source assets
 * 2. **Anchor links** - Translates anchors by position index between source and translated docs
 *
 * The corrector processes links found in markdown content and adjusts them based on
 * the relationship between source and target directories.
 */
public final class LinkCorrector {

	/**
	 * Pattern to match markdown links: `[text](destination)` or `![alt](destination)`.
	 * Group 1: The link prefix including brackets (e.g., `[text]` or `![alt]`)
	 * Group 2: The link destination
	 */
	private static final Pattern LINK_PATTERN = Pattern.compile(
		"(!?\\[[^\\]]*\\])\\(([^)]+)\\)"
	);

	@Nonnull
	private final Path sourceDir;
	@Nonnull
	private final Path targetDir;
	@Nonnull
	private final Pattern filePattern;
	@Nullable
	private final List<Pattern> exclusionPatterns;
	@Nullable
	private final List<String> translatableFrontMatterFields;
	@Nonnull
	private final Log log;

	/**
	 * Cache of source file heading indices for efficient anchor mapping.
	 * Key is the absolute normalized path to the source file.
	 */
	@Nonnull
	private final Map<Path, HeadingAnchorIndex> sourceAnchorCache = new HashMap<>();

	/**
	 * Creates a LinkCorrector for the given source and target directories.
	 *
	 * @param sourceDir                    the source directory containing original markdown files
	 * @param targetDir                    the target directory containing translated files
	 * @param filePattern                  regex pattern to identify translatable markdown files
	 * @param exclusionPatterns            patterns for files to exclude from translation
	 * @param translatableFrontMatterFields field names in front matter that are translated
	 * @param log                          Maven log for output
	 */
	public LinkCorrector(
		@Nonnull Path sourceDir,
		@Nonnull Path targetDir,
		@Nonnull Pattern filePattern,
		@Nullable List<Pattern> exclusionPatterns,
		@Nullable List<String> translatableFrontMatterFields,
		@Nonnull Log log
	) {
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null")
			.toAbsolutePath().normalize();
		this.targetDir = Objects.requireNonNull(targetDir, "targetDir must not be null")
			.toAbsolutePath().normalize();
		this.filePattern = Objects.requireNonNull(filePattern, "filePattern must not be null");
		this.exclusionPatterns = exclusionPatterns;
		this.translatableFrontMatterFields = translatableFrontMatterFields;
		this.log = Objects.requireNonNull(log, "log must not be null");
	}

	/**
	 * Corrects links in multiple translated files.
	 *
	 * @param translatedFiles map of target file path to translated content
	 * @return list of correction results for each file
	 */
	@Nonnull
	public List<LinkCorrectionResult> correctAll(@Nonnull Map<Path, String> translatedFiles) {
		Objects.requireNonNull(translatedFiles, "translatedFiles must not be null");

		final List<LinkCorrectionResult> results = new ArrayList<>(translatedFiles.size());
		for (final Map.Entry<Path, String> entry : translatedFiles.entrySet()) {
			try {
				results.add(correctLinks(entry.getKey(), entry.getValue()));
			} catch (IOException e) {
				this.log.error("Failed to correct links in " + entry.getKey() + ": " + e.getMessage());
				results.add(LinkCorrectionResult.failed(
					entry.getKey(),
					entry.getValue(),
					"IO error: " + e.getMessage()
				));
			}
		}
		return results;
	}

	/**
	 * Corrects links in a single translated file.
	 * Processes both front matter fields and markdown body content.
	 *
	 * @param translatedFile    the translated file being processed
	 * @param translatedContent the content of the translated file
	 * @return correction result with corrected content and statistics
	 * @throws IOException if reading source files fails
	 */
	@Nonnull
	public LinkCorrectionResult correctLinks(
		@Nonnull Path translatedFile,
		@Nonnull String translatedContent
	) throws IOException {
		Objects.requireNonNull(translatedFile, "translatedFile must not be null");
		Objects.requireNonNull(translatedContent, "translatedContent must not be null");

		final Path normalizedTarget = translatedFile.toAbsolutePath().normalize();

		// Calculate the source file path (mirror structure in sourceDir)
		final Path sourceFile = calculateSourceFile(normalizedTarget);

		final CorrectionContext context = new CorrectionContext(
			normalizedTarget,
			sourceFile,
			translatedContent
		);

		// Parse the document
		final MarkdownDocument document = new MarkdownDocument(translatedContent);

		// Phase 1: Correct front matter fields
		final Map<String, String> correctedFrontMatter = correctFrontMatter(document, context);

		// Apply front matter corrections to document
		for (final Map.Entry<String, String> entry : correctedFrontMatter.entrySet()) {
			document.setProperty(entry.getKey(), entry.getValue());
		}

		// Phase 2: Correct body content
		final String bodyContent = document.getBodyContent();
		final String correctedBody = replaceLinks(bodyContent, context);

		// Reconstruct full document
		final String correctedContent = document.serializeFrontMatter() + correctedBody;

		if (!context.errors.isEmpty()) {
			return new LinkCorrectionResult(
				normalizedTarget,
				correctedContent,
				context.assetCorrections,
				context.anchorCorrections,
				context.frontMatterCorrections,
				context.errors
			);
		}

		return new LinkCorrectionResult(
			normalizedTarget,
			correctedContent,
			context.assetCorrections,
			context.anchorCorrections,
			context.frontMatterCorrections,
			List.of()
		);
	}

	/**
	 * Calculates the corresponding source file path for a translated file.
	 * The source file has the same relative path from sourceDir as the
	 * translated file has from targetDir.
	 *
	 * @param translatedFile the translated file path
	 * @return the corresponding source file path
	 */
	@Nonnull
	private Path calculateSourceFile(@Nonnull Path translatedFile) {
		final Path relativePath = this.targetDir.relativize(translatedFile);
		return this.sourceDir.resolve(relativePath).normalize();
	}

	/**
	 * Replaces links in content using the correction context.
	 *
	 * @param content the original content
	 * @param context the correction context
	 * @return content with corrected links
	 */
	@Nonnull
	private String replaceLinks(
		@Nonnull String content,
		@Nonnull CorrectionContext context
	) {
		final Matcher matcher = LINK_PATTERN.matcher(content);
		final StringBuilder result = new StringBuilder();
		int lastEnd = 0;

		while (matcher.find()) {
			// Append content before this match
			result.append(content, lastEnd, matcher.start());

			final String linkPrefix = matcher.group(1);  // [text] or ![alt]
			final String destination = matcher.group(2);

			// Correct the link destination
			final String corrected = correctDestination(destination, context);

			// Reconstruct the link
			result.append(linkPrefix).append("(").append(corrected).append(")");

			lastEnd = matcher.end();
		}

		// Append remaining content
		result.append(content.substring(lastEnd));

		return result.toString();
	}

	/**
	 * Corrects a single link destination.
	 *
	 * @param destination the original link destination
	 * @param context     the correction context
	 * @return the corrected destination
	 */
	@Nonnull
	private String correctDestination(
		@Nonnull String destination,
		@Nonnull CorrectionContext context
	) {
		final LinkInfo linkInfo = LinkInfo.parse(destination);

		// Skip external links
		if (linkInfo.isExternal()) {
			return destination;
		}

		// Skip absolute links
		if (linkInfo.isAbsolute()) {
			return destination;
		}

		// Handle anchor-only links within the same document
		if (linkInfo.isAnchorOnly()) {
			return correctAnchorOnlyLink(linkInfo, context);
		}

		// Handle links with paths
		return correctPathLink(linkInfo, context);
	}

	/**
	 * Corrects an anchor-only link (e.g., `#section`).
	 *
	 * @param linkInfo the parsed link info
	 * @param context  the correction context
	 * @return the corrected anchor link
	 */
	@Nonnull
	private String correctAnchorOnlyLink(
		@Nonnull LinkInfo linkInfo,
		@Nonnull CorrectionContext context
	) {
		final String anchor = linkInfo.anchor();
		if (anchor == null || anchor.isEmpty()) {
			return linkInfo.destination();
		}

		try {
			final String translatedAnchor = translateAnchor(
				context.sourceFile,
				context.translatedContent,
				context.translatedFile,
				anchor
			);
			if (translatedAnchor != null) {
				context.anchorCorrections++;
				return "#" + translatedAnchor;
			}
		} catch (LinkCorrectionException e) {
			context.errors.add(e.getMessage());
		} catch (IOException e) {
			this.log.warn("Failed to read source file for anchor correction: " + e.getMessage());
		}

		return linkInfo.destination();
	}

	/**
	 * Corrects a link with a path component.
	 *
	 * @param linkInfo the parsed link info
	 * @param context  the correction context
	 * @return the corrected link
	 */
	@Nonnull
	private String correctPathLink(
		@Nonnull LinkInfo linkInfo,
		@Nonnull CorrectionContext context
	) {
		final String path = linkInfo.path();
		if (path == null) {
			return linkInfo.destination();
		}

		// Decode URL-encoded path for file resolution
		final String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);

		// Resolve the link target relative to the source file
		final Path sourceFileDir = context.sourceFile.getParent();
		final Path targetPath = sourceFileDir.resolve(decodedPath).normalize();

		// Check if this is a translatable markdown file
		if (isTranslatableMarkdown(targetPath)) {
			// This file is translated, so it exists in targetDir with the same relative path.
			// The path itself doesn't need correction, but the anchor might.
			return correctMarkdownLink(linkInfo, targetPath, context);
		} else {
			// This is an asset - needs path correction
			return correctAssetLink(linkInfo, targetPath, context);
		}
	}

	/**
	 * Corrects a link to another translated markdown file.
	 * The path stays the same (same relative structure), but anchors need translation.
	 *
	 * @param linkInfo      the parsed link info
	 * @param sourceTarget  the resolved source file being linked to
	 * @param context       the correction context
	 * @return the corrected link
	 */
	@Nonnull
	private String correctMarkdownLink(
		@Nonnull LinkInfo linkInfo,
		@Nonnull Path sourceTarget,
		@Nonnull CorrectionContext context
	) {
		final String anchor = linkInfo.anchor();
		if (anchor == null || anchor.isEmpty()) {
			// No anchor to correct
			return linkInfo.destination();
		}

		// Calculate the translated version of the target file
		final Path relativePath = this.sourceDir.relativize(sourceTarget);
		final Path translatedTarget = this.targetDir.resolve(relativePath).normalize();

		try {
			// Read the translated target content
			if (Files.exists(translatedTarget)) {
				final String translatedTargetContent = Files.readString(
					translatedTarget, StandardCharsets.UTF_8
				);
				final String translatedAnchor = translateAnchor(
					sourceTarget,
					translatedTargetContent,
					translatedTarget,
					anchor
				);
				if (translatedAnchor != null) {
					context.anchorCorrections++;
					return linkInfo.path() + "#" + translatedAnchor;
				}
			}
		} catch (LinkCorrectionException e) {
			context.errors.add(e.getMessage());
		} catch (IOException e) {
			this.log.warn("Failed to read translated target file: " + e.getMessage());
		}

		return linkInfo.destination();
	}

	/**
	 * Corrects an asset link by recalculating the relative path from target to source.
	 *
	 * @param linkInfo    the parsed link info
	 * @param assetPath   the resolved asset path in source directory
	 * @param context     the correction context
	 * @return the corrected link
	 */
	@Nonnull
	private String correctAssetLink(
		@Nonnull LinkInfo linkInfo,
		@Nonnull Path assetPath,
		@Nonnull CorrectionContext context
	) {
		// Calculate relative path from translated file to the asset
		final Path translatedDir = context.translatedFile.getParent();
		final Path relativePath = translatedDir.relativize(assetPath);

		// Convert to Unix-style path separators for markdown
		final String correctedPath = relativePath.toString().replace('\\', '/');

		// Preserve any anchor
		final String anchor = linkInfo.anchor();
		final String result;
		if (anchor != null && !anchor.isEmpty()) {
			result = correctedPath + "#" + anchor;
		} else {
			result = correctedPath;
		}

		// Only count as correction if path actually changed
		if (!result.equals(linkInfo.destination())) {
			context.assetCorrections++;
		}

		return result;
	}

	/**
	 * Translates an anchor from source document to translated document by index.
	 *
	 * @param sourceFile        the source markdown file
	 * @param translatedContent the translated content
	 * @param translatedFile    the translated file (for error reporting)
	 * @param anchor            the anchor to translate
	 * @return the translated anchor, or null if anchor not found
	 * @throws LinkCorrectionException if heading count mismatch
	 * @throws IOException             if reading source file fails
	 */
	@Nullable
	private String translateAnchor(
		@Nonnull Path sourceFile,
		@Nonnull String translatedContent,
		@Nonnull Path translatedFile,
		@Nonnull String anchor
	) throws LinkCorrectionException, IOException {
		// Get source heading index (cached)
		final HeadingAnchorIndex sourceIndex = getSourceAnchorIndex(sourceFile);

		// Build translated heading index
		final MarkdownDocument translatedDoc = new MarkdownDocument(translatedContent);
		final HeadingAnchorIndex translatedIndex = HeadingAnchorIndex.fromDocument(
			translatedDoc.getDocument()
		);

		// Validate heading counts match
		if (sourceIndex.size() != translatedIndex.size()) {
			throw new LinkCorrectionException(
				sourceFile,
				translatedFile,
				sourceIndex.size(),
				translatedIndex.size()
			);
		}

		// Find anchor index in source
		final Optional<Integer> indexOpt = sourceIndex.indexOf(anchor);
		if (indexOpt.isEmpty()) {
			// Anchor not found in source - might be an invalid link
			this.log.debug("Anchor not found in source: " + anchor);
			return null;
		}

		// Return anchor at same index in translated document
		return translatedIndex.getAnchor(indexOpt.get());
	}

	/**
	 * Gets the heading anchor index for a source file, using cache.
	 *
	 * @param sourceFile the source file
	 * @return the heading anchor index
	 * @throws IOException if reading the file fails
	 */
	@Nonnull
	private HeadingAnchorIndex getSourceAnchorIndex(@Nonnull Path sourceFile) throws IOException {
		final Path normalized = sourceFile.toAbsolutePath().normalize();
		HeadingAnchorIndex index = this.sourceAnchorCache.get(normalized);
		if (index == null) {
			final String content = Files.readString(normalized, StandardCharsets.UTF_8);
			final MarkdownDocument doc = new MarkdownDocument(content);
			index = HeadingAnchorIndex.fromDocument(doc.getDocument());
			this.sourceAnchorCache.put(normalized, index);
		}
		return index;
	}

	/**
	 * Pattern to detect file extensions (any dot followed by word characters at end of string).
	 */
	private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile(".*\\.\\w+$");

	/**
	 * Checks if a value appears to be a relative file path.
	 * Returns true if the value looks like a path that might need correction.
	 * The actual validation is done by checking if the file exists.
	 *
	 * @param value the value to check
	 * @return true if this looks like a relative file path
	 */
	private boolean isLikelyFilePath(@Nonnull String value) {
		if (value.isBlank()) {
			return false;
		}

		// Skip external URLs
		final String lower = value.toLowerCase();
		if (lower.startsWith("http://") ||
			lower.startsWith("https://") ||
			lower.startsWith("mailto:") ||
			lower.startsWith("tel:") ||
			lower.startsWith("ftp://") ||
			lower.startsWith("//")) {
			return false;
		}

		// Skip absolute paths
		if (value.startsWith("/")) {
			return false;
		}

		// Path with directory separator
		if (value.contains("/") || value.contains("\\")) {
			return true;
		}

		// Has file extension (any extension)
		return FILE_EXTENSION_PATTERN.matcher(value).matches();
	}

	/**
	 * Corrects a non-translatable field value if it appears to be a relative file path.
	 * Only corrects if the resolved path points to an existing file in the source directory.
	 *
	 * @param fieldName the field name (for debugging)
	 * @param value     the field value to check and potentially correct
	 * @param context   the correction context
	 * @return the corrected value, or original if not a correctable path
	 */
	@Nonnull
	private String correctNonTranslatableField(
		@Nonnull String fieldName,
		@Nonnull String value,
		@Nonnull CorrectionContext context
	) {
		if (!isLikelyFilePath(value)) {
			return value;
		}

		// Decode URL-encoded path for file resolution
		final String decodedPath = URLDecoder.decode(value, StandardCharsets.UTF_8);

		// Resolve the path relative to the source file's directory
		final Path sourceFileDir = context.sourceFile.getParent();
		final Path resolvedPath = sourceFileDir.resolve(decodedPath).normalize();

		// Only correct if the file actually exists
		if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
			return value;
		}

		// Calculate relative path from translated file location to the source asset
		final Path translatedDir = context.translatedFile.getParent();
		final Path relativePath = translatedDir.relativize(resolvedPath);

		// Convert to Unix-style path separators for consistency
		final String correctedPath = relativePath.toString().replace('\\', '/');

		// Only count as correction if path actually changed
		if (!correctedPath.equals(value)) {
			context.frontMatterCorrections++;
			this.log.debug("Corrected front matter field '" + fieldName + "': " + value + " -> " + correctedPath);
		}

		return correctedPath;
	}

	/**
	 * Corrects links in front matter fields.
	 * Translatable fields get full link correction (anchor translation + asset paths).
	 * Non-translatable fields are checked for file paths and corrected if the file exists.
	 *
	 * @param document the parsed MarkdownDocument
	 * @param context  the correction context
	 * @return map of field names to corrected values (only fields that changed)
	 */
	@Nonnull
	private Map<String, String> correctFrontMatter(
		@Nonnull MarkdownDocument document,
		@Nonnull CorrectionContext context
	) {
		final Map<String, String> correctedFields = new HashMap<>();
		final Map<String, List<String>> properties = document.getProperties();

		for (final Map.Entry<String, List<String>> entry : properties.entrySet()) {
			final String fieldName = entry.getKey();
			final List<String> values = entry.getValue();

			if (values == null || values.isEmpty()) {
				continue;
			}

			// Get the first value (single-value fields)
			final String originalValue = values.get(0);
			if (originalValue == null || originalValue.isBlank()) {
				continue;
			}

			final String correctedValue;
			if (this.translatableFrontMatterFields != null &&
				this.translatableFrontMatterFields.contains(fieldName)) {
				// Translatable field: apply full markdown link correction
				correctedValue = replaceLinks(originalValue, context);
			} else {
				// Non-translatable field: check if it's a file path
				correctedValue = correctNonTranslatableField(fieldName, originalValue, context);
			}

			if (!correctedValue.equals(originalValue)) {
				correctedFields.put(fieldName, correctedValue);
			}
		}

		return correctedFields;
	}

	/**
	 * Determines if a path points to a translatable markdown file.
	 *
	 * @param path the path to check
	 * @return true if this is a translatable markdown file
	 */
	private boolean isTranslatableMarkdown(@Nonnull Path path) {
		// Must be within source directory
		if (!path.startsWith(this.sourceDir)) {
			return false;
		}

		// Must match file pattern
		final String filename = path.getFileName().toString();
		if (!this.filePattern.matcher(filename).matches()) {
			return false;
		}

		// Must not match any exclusion pattern
		if (this.exclusionPatterns != null) {
			final String relativePath = this.sourceDir.relativize(path).toString();
			for (final Pattern exclusion : this.exclusionPatterns) {
				if (exclusion.matcher(relativePath).matches()) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Context for correcting links in a single file.
	 * Tracks correction statistics and errors.
	 */
	private static final class CorrectionContext {
		@Nonnull
		final Path translatedFile;
		@Nonnull
		final Path sourceFile;
		@Nonnull
		final String translatedContent;
		int assetCorrections = 0;
		int anchorCorrections = 0;
		int frontMatterCorrections = 0;
		@Nonnull
		final List<String> errors = new ArrayList<>();

		CorrectionContext(
			@Nonnull Path translatedFile,
			@Nonnull Path sourceFile,
			@Nonnull String translatedContent
		) {
			this.translatedFile = translatedFile;
			this.sourceFile = sourceFile;
			this.translatedContent = translatedContent;
		}
	}
}
