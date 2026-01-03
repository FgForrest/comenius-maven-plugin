package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for extracting and merging translatable front matter fields.
 * Handles extraction of specified fields for translation and merging
 * translated values back into the document.
 */
public final class FrontMatterTranslationHelper {

	private static final String FIELD_SECTION_HEADER = "=== FRONT MATTER FIELDS TO TRANSLATE ===";
	private static final Pattern FIELD_BLOCK_PATTERN = Pattern.compile(
		"\\[\\[([^\\]]+)\\]\\]\\s*\\n(.*?)\\n\\[\\[/\\1\\]\\]",
		Pattern.DOTALL
	);

	private FrontMatterTranslationHelper() {
		// utility class
	}

	/**
	 * Extracts translatable front matter fields from a MarkdownDocument.
	 * Only includes fields that exist and have non-empty values.
	 *
	 * @param document   the source document
	 * @param fieldNames list of field names to extract
	 * @return map of field name to value (only non-empty existing fields)
	 */
	@Nonnull
	public static Map<String, String> extractTranslatableFields(
		@Nonnull MarkdownDocument document,
		@Nullable List<String> fieldNames
	) {
		Objects.requireNonNull(document, "document must not be null");
		if (fieldNames == null || fieldNames.isEmpty()) {
			return Map.of();
		}

		final Map<String, String> result = new LinkedHashMap<>();
		for (final String fieldName : fieldNames) {
			document.getProperty(fieldName)
				.filter(v -> !v.isBlank())
				.ifPresent(v -> result.put(fieldName, v));
		}
		return result;
	}

	/**
	 * Formats extracted fields for inclusion in LLM prompt.
	 * Uses a structured format that can be parsed from LLM response.
	 *
	 * @param fields map of field names to values
	 * @return formatted string for prompt, or empty string if no fields
	 */
	@Nonnull
	public static String formatFieldsForPrompt(@Nonnull Map<String, String> fields) {
		Objects.requireNonNull(fields, "fields must not be null");
		if (fields.isEmpty()) {
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("\n\n").append(FIELD_SECTION_HEADER).append("\n");
		for (final Map.Entry<String, String> entry : fields.entrySet()) {
			sb.append("[[").append(entry.getKey()).append("]]\n");
			sb.append(entry.getValue()).append("\n");
			sb.append("[[/").append(entry.getKey()).append("]]\n\n");
		}
		return sb.toString();
	}

	/**
	 * Parses translated field values from LLM response.
	 * Looks for `[[fieldName]]...[[/fieldName]]` blocks in the response.
	 * Validates that all expected fields are present in the response.
	 *
	 * @param llmResponse    the full LLM response
	 * @param expectedFields the field names that were sent for translation
	 * @return map of field name to translated value
	 * @throws IllegalStateException if any expected fields are missing from response
	 */
	@Nonnull
	public static Map<String, String> parseTranslatedFields(
		@Nonnull String llmResponse,
		@Nonnull Map<String, String> expectedFields
	) {
		Objects.requireNonNull(llmResponse, "llmResponse must not be null");
		Objects.requireNonNull(expectedFields, "expectedFields must not be null");

		final Map<String, String> result = new LinkedHashMap<>();
		if (expectedFields.isEmpty()) {
			return result;
		}

		final Matcher matcher = FIELD_BLOCK_PATTERN.matcher(llmResponse);
		while (matcher.find()) {
			final String fieldName = matcher.group(1);
			final String value = matcher.group(2).trim();
			if (expectedFields.containsKey(fieldName) && !value.isEmpty()) {
				result.put(fieldName, value);
			}
		}

		// Validate all expected fields were received
		if (result.size() != expectedFields.size()) {
			final Set<String> missing = new LinkedHashSet<>(expectedFields.keySet());
			missing.removeAll(result.keySet());
			throw new IllegalStateException(
				"Translation incomplete: missing front matter fields: " + missing
			);
		}

		return result;
	}

	/**
	 * Extracts the main document body from LLM response,
	 * removing the front matter field blocks.
	 *
	 * @param llmResponse the full LLM response
	 * @param fieldNames  the field names that were translated
	 * @return the document body without field blocks
	 */
	@Nonnull
	public static String extractBodyFromResponse(
		@Nonnull String llmResponse,
		@Nonnull Map<String, String> fieldNames
	) {
		Objects.requireNonNull(llmResponse, "llmResponse must not be null");
		Objects.requireNonNull(fieldNames, "fieldNames must not be null");

		if (fieldNames.isEmpty()) {
			return llmResponse;
		}

		String result = llmResponse;

		// Remove each field block from the response
		for (final String fieldName : fieldNames.keySet()) {
			final String startTag = "[[" + fieldName + "]]";
			final String endTag = "[[/" + fieldName + "]]";

			int startIdx = result.indexOf(startTag);
			while (startIdx >= 0) {
				final int endIdx = result.indexOf(endTag, startIdx);
				if (endIdx > startIdx) {
					result = result.substring(0, startIdx) +
						result.substring(endIdx + endTag.length());
				} else {
					break;
				}
				startIdx = result.indexOf(startTag);
			}
		}

		// Clean up the header section marker if present
		result = result.replace(FIELD_SECTION_HEADER, "");

		// Clean up excessive whitespace left after removal
		result = result.replaceAll("\\n{3,}", "\n\n");

		return result.trim();
	}

	/**
	 * Checks if there are any translatable fields configured.
	 *
	 * @param fieldNames the list of configured field names
	 * @return true if there are fields to translate, false otherwise
	 */
	public static boolean hasTranslatableFields(@Nullable List<String> fieldNames) {
		return fieldNames != null && !fieldNames.isEmpty();
	}
}
