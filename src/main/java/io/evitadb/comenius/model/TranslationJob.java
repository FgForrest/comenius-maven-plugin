package io.evitadb.comenius.model;

import io.evitadb.comenius.llm.PromptLoader;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed abstract class representing a translation job.
 * Contains common fields and abstract methods for polymorphic prompt building.
 * Subclasses handle different translation scenarios (new vs incremental).
 */
public sealed abstract class TranslationJob
	permits TranslateNewJob, TranslateIncrementalJob {

	@Nonnull
	protected final Path sourceFile;
	@Nonnull
	protected final Path targetFile;
	@Nonnull
	protected final Locale locale;
	@Nonnull
	protected final String sourceContent;
	@Nonnull
	protected final String currentCommit;
	@Nullable
	protected final String instructions;
	@Nullable
	protected final List<String> translatableFrontMatterFields;

	/**
	 * Creates a new translation job with the specified parameters.
	 *
	 * @param sourceFile                    the source markdown file path
	 * @param targetFile                    the target file path for the translation
	 * @param locale                        the target locale for translation
	 * @param sourceContent                 the current content of the source file
	 * @param currentCommit                 the current commit hash of the source file
	 * @param instructions                  optional custom instructions from .comenius-instructions files
	 * @param translatableFrontMatterFields optional list of front matter field names to translate
	 */
	protected TranslationJob(
		@Nonnull Path sourceFile,
		@Nonnull Path targetFile,
		@Nonnull Locale locale,
		@Nonnull String sourceContent,
		@Nonnull String currentCommit,
		@Nullable String instructions,
		@Nullable List<String> translatableFrontMatterFields
	) {
		this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile must not be null");
		this.targetFile = Objects.requireNonNull(targetFile, "targetFile must not be null");
		this.locale = Objects.requireNonNull(locale, "locale must not be null");
		this.sourceContent = Objects.requireNonNull(sourceContent, "sourceContent must not be null");
		this.currentCommit = Objects.requireNonNull(currentCommit, "currentCommit must not be null");
		this.instructions = instructions;
		this.translatableFrontMatterFields = translatableFrontMatterFields;
	}

	/**
	 * Builds the system prompt for the LLM using the appropriate template.
	 *
	 * @param loader the prompt loader to use for loading templates
	 * @return the system prompt string
	 */
	@Nonnull
	public abstract String buildSystemPrompt(@Nonnull PromptLoader loader);

	/**
	 * Builds the user prompt for the LLM using the appropriate template.
	 *
	 * @param loader the prompt loader to use for loading templates
	 * @return the user prompt string
	 */
	@Nonnull
	public abstract String buildUserPrompt(@Nonnull PromptLoader loader);

	/**
	 * Returns the type of this translation job for display purposes.
	 *
	 * @return "NEW" or "UPDATE" depending on the job type
	 */
	@Nonnull
	public abstract String getType();

	/**
	 * Returns the source markdown file path.
	 *
	 * @return the source file path
	 */
	@Nonnull
	public Path getSourceFile() {
		return this.sourceFile;
	}

	/**
	 * Returns the target file path for the translation.
	 *
	 * @return the target file path
	 */
	@Nonnull
	public Path getTargetFile() {
		return this.targetFile;
	}

	/**
	 * Returns the target locale for translation.
	 *
	 * @return the target locale
	 */
	@Nonnull
	public Locale getLocale() {
		return this.locale;
	}

	/**
	 * Returns the current content of the source file.
	 *
	 * @return the source content
	 */
	@Nonnull
	public String getSourceContent() {
		return this.sourceContent;
	}

	/**
	 * Returns the current commit hash of the source file.
	 *
	 * @return the current commit hash
	 */
	@Nonnull
	public String getCurrentCommit() {
		return this.currentCommit;
	}

	/**
	 * Returns the optional custom instructions.
	 *
	 * @return custom instructions or null if none
	 */
	@Nullable
	public String getInstructions() {
		return this.instructions;
	}

	/**
	 * Returns the optional list of front matter field names to translate.
	 *
	 * @return list of field names or null if none
	 */
	@Nullable
	public List<String> getTranslatableFrontMatterFields() {
		return this.translatableFrontMatterFields;
	}

	/**
	 * Returns the extracted translatable front matter fields for this job.
	 * The returned map contains field names as keys and their original values.
	 *
	 * @return map of field names to values that should be translated
	 */
	@Nonnull
	public abstract Map<String, String> getExtractedTranslatableFields();

	/**
	 * Creates a map of common placeholder values for prompt interpolation.
	 * Public to allow {@link io.evitadb.comenius.Translator} access
	 * for building front matter prompts.
	 *
	 * @return map of placeholder names to values
	 */
	@Nonnull
	public Map<String, String> getCommonPlaceholders() {
		return Map.of(
			"locale", this.locale.getDisplayName(),
			"localeTag", this.locale.toLanguageTag(),
			"customInstructions", this.instructions != null ? this.instructions : ""
		);
	}
}
