package io.evitadb.comenius.model;

import io.evitadb.comenius.llm.PromptLoader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Translation job for updating existing translations based on source file changes.
 * Uses incremental translation prompts that include the original source, existing translation, and diff.
 */
public final class TranslateIncrementalJob extends TranslationJob {

	private static final String SYSTEM_TEMPLATE = "translate-incremental-system.txt";
	private static final String USER_TEMPLATE = "translate-incremental-user.txt";

	@Nonnull
	private final String originalSource;
	@Nonnull
	private final String existingTranslation;
	@Nonnull
	private final String diff;
	@Nonnull
	private final String translatedCommit;
	private final int commitCount;

	/**
	 * Creates an incremental translation job for updating an existing translation.
	 *
	 * @param sourceFile                    the source markdown file path
	 * @param targetFile                    the target file path for the translation
	 * @param locale                        the target locale for translation
	 * @param sourceContent                 the current content of the source file
	 * @param currentCommit                 the current commit hash of the source file
	 * @param instructions                  optional custom instructions from .comenius-instructions files
	 * @param translatableFrontMatterFields optional list of front matter field names to translate
	 * @param originalSource                the source content at the previously translated commit
	 * @param existingTranslation           the current translation content
	 * @param diff                          the unified diff showing changes between commits
	 * @param translatedCommit              the commit hash from the existing translation
	 * @param commitCount                   the number of commits between translatedCommit and currentCommit
	 */
	public TranslateIncrementalJob(
		@Nonnull Path sourceFile,
		@Nonnull Path targetFile,
		@Nonnull Locale locale,
		@Nonnull String sourceContent,
		@Nonnull String currentCommit,
		@Nullable String instructions,
		@Nullable List<String> translatableFrontMatterFields,
		@Nonnull String originalSource,
		@Nonnull String existingTranslation,
		@Nonnull String diff,
		@Nonnull String translatedCommit,
		int commitCount
	) {
		super(sourceFile, targetFile, locale, sourceContent, currentCommit, instructions, translatableFrontMatterFields);
		this.originalSource = Objects.requireNonNull(originalSource, "originalSource must not be null");
		this.existingTranslation = Objects.requireNonNull(existingTranslation, "existingTranslation must not be null");
		this.diff = Objects.requireNonNull(diff, "diff must not be null");
		this.translatedCommit = Objects.requireNonNull(translatedCommit, "translatedCommit must not be null");
		this.commitCount = commitCount;
	}

	@Override
	@Nonnull
	public String buildSystemPrompt(@Nonnull PromptLoader loader) {
		return loader.loadAndInterpolate(SYSTEM_TEMPLATE, getCommonPlaceholders());
	}

	@Override
	@Nonnull
	public String buildUserPrompt(@Nonnull PromptLoader loader) {
		final Map<String, String> placeholders = new HashMap<>(getCommonPlaceholders());
		placeholders.put("originalSource", this.originalSource);
		placeholders.put("existingTranslation", this.existingTranslation);
		placeholders.put("diff", this.diff);

		// Extract and format front matter fields for translation (only changed fields)
		final Map<String, String> translatableFields = getExtractedTranslatableFields();
		placeholders.put("frontMatterFields",
			FrontMatterTranslationHelper.formatFieldsForPrompt(translatableFields));

		return loader.loadAndInterpolate(USER_TEMPLATE, placeholders);
	}

	@Override
	@Nonnull
	public String getType() {
		return "UPDATE";
	}

	/**
	 * Returns the source content at the previously translated commit.
	 *
	 * @return the original source content
	 */
	@Nonnull
	public String getOriginalSource() {
		return this.originalSource;
	}

	/**
	 * Returns the current translation content.
	 *
	 * @return the existing translation
	 */
	@Nonnull
	public String getExistingTranslation() {
		return this.existingTranslation;
	}

	/**
	 * Returns the unified diff showing changes between commits.
	 *
	 * @return the diff string
	 */
	@Nonnull
	public String getDiff() {
		return this.diff;
	}

	/**
	 * Returns the commit hash from the existing translation.
	 *
	 * @return the translated commit hash
	 */
	@Nonnull
	public String getTranslatedCommit() {
		return this.translatedCommit;
	}

	/**
	 * Returns the number of commits between translatedCommit and currentCommit.
	 *
	 * @return the commit count
	 */
	public int getCommitCount() {
		return this.commitCount;
	}

	/**
	 * Returns a short form of the translated commit hash (first 7 characters).
	 *
	 * @return short commit hash
	 */
	@Nonnull
	public String getTranslatedCommitShort() {
		return this.translatedCommit.length() > 7 ?
			this.translatedCommit.substring(0, 7) :
			this.translatedCommit;
	}

	/**
	 * Returns a short form of the current commit hash (first 7 characters).
	 *
	 * @return short commit hash
	 */
	@Nonnull
	public String getCurrentCommitShort() {
		return this.currentCommit.length() > 7 ?
			this.currentCommit.substring(0, 7) :
			this.currentCommit;
	}

	@Override
	@Nonnull
	public Map<String, String> getExtractedTranslatableFields() {
		final MarkdownDocument currentDoc = new MarkdownDocument(this.sourceContent);
		final MarkdownDocument originalDoc = new MarkdownDocument(this.originalSource);

		final Map<String, String> currentFields = FrontMatterTranslationHelper.extractTranslatableFields(
			currentDoc, this.translatableFrontMatterFields
		);
		final Map<String, String> originalFields = FrontMatterTranslationHelper.extractTranslatableFields(
			originalDoc, this.translatableFrontMatterFields
		);

		// Only include fields that have changed
		final Map<String, String> changedFields = new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : currentFields.entrySet()) {
			final String originalValue = originalFields.get(entry.getKey());
			if (!entry.getValue().equals(originalValue)) {
				changedFields.put(entry.getKey(), entry.getValue());
			}
		}

		return changedFields;
	}
}
