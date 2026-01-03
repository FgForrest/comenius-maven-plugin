package io.evitadb.comenius.model;

import io.evitadb.comenius.llm.PromptLoader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translation job for new files that have no existing translation.
 * Uses full translation prompts to translate the entire document.
 */
public final class TranslateNewJob extends TranslationJob {

	private static final String SYSTEM_TEMPLATE = "translate-new-system.txt";
	private static final String USER_TEMPLATE = "translate-new-user.txt";

	/**
	 * Creates a new translation job for a file without existing translation.
	 *
	 * @param sourceFile                    the source markdown file path
	 * @param targetFile                    the target file path for the translation
	 * @param locale                        the target locale for translation
	 * @param sourceContent                 the current content of the source file
	 * @param currentCommit                 the current commit hash of the source file
	 * @param instructions                  optional custom instructions from .comenius-instructions files
	 * @param translatableFrontMatterFields optional list of front matter field names to translate
	 */
	public TranslateNewJob(
		@Nonnull Path sourceFile,
		@Nonnull Path targetFile,
		@Nonnull Locale locale,
		@Nonnull String sourceContent,
		@Nonnull String currentCommit,
		@Nullable String instructions,
		@Nullable List<String> translatableFrontMatterFields
	) {
		super(sourceFile, targetFile, locale, sourceContent, currentCommit, instructions, translatableFrontMatterFields);
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

		// Send only body content (no front matter) to LLM
		// Front matter fields are translated separately in Phase 1 by Translator
		final MarkdownDocument sourceDoc = new MarkdownDocument(this.sourceContent);
		placeholders.put("sourceContent", sourceDoc.getBodyContent());

		return loader.loadAndInterpolate(USER_TEMPLATE, placeholders);
	}

	@Override
	@Nonnull
	public String getType() {
		return "NEW";
	}

	@Override
	@Nonnull
	public Map<String, String> getExtractedTranslatableFields() {
		final MarkdownDocument sourceDoc = new MarkdownDocument(this.sourceContent);
		return FrontMatterTranslationHelper.extractTranslatableFields(
			sourceDoc, this.translatableFrontMatterFields
		);
	}
}
