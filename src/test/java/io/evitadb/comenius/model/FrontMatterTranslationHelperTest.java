package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for FrontMatterTranslationHelper utility class.
 */
@DisplayName("FrontMatterTranslationHelper")
public class FrontMatterTranslationHelperTest {

	@Test
	@DisplayName("extracts specified fields from document")
	void shouldExtractSpecifiedFieldsFromDocument() {
		final String markdown = """
			---
			title: Hello World
			perex: This is a sample perex
			author: John Doe
			---
			# Content
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<String> fieldsToExtract = List.of("title", "perex");

		final Map<String, String> result = FrontMatterTranslationHelper.extractTranslatableFields(
			doc, fieldsToExtract
		);

		assertEquals(2, result.size());
		assertEquals("Hello World", result.get("title"));
		assertEquals("This is a sample perex", result.get("perex"));
	}

	@Test
	@DisplayName("skips missing fields")
	void shouldSkipMissingFields() {
		final String markdown = """
			---
			title: Hello World
			---
			# Content
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<String> fieldsToExtract = List.of("title", "perex", "description");

		final Map<String, String> result = FrontMatterTranslationHelper.extractTranslatableFields(
			doc, fieldsToExtract
		);

		assertEquals(1, result.size());
		assertEquals("Hello World", result.get("title"));
		assertFalse(result.containsKey("perex"));
		assertFalse(result.containsKey("description"));
	}

	@Test
	@DisplayName("skips empty fields")
	void shouldSkipEmptyFields() {
		final String markdown = """
			---
			title: Hello World
			perex:
			description: ""
			---
			# Content
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<String> fieldsToExtract = List.of("title", "perex", "description");

		final Map<String, String> result = FrontMatterTranslationHelper.extractTranslatableFields(
			doc, fieldsToExtract
		);

		assertEquals(1, result.size());
		assertEquals("Hello World", result.get("title"));
	}

	@Test
	@DisplayName("returns empty map when no fields configured")
	void shouldReturnEmptyMapWhenNoFieldsConfigured() {
		final String markdown = """
			---
			title: Hello World
			---
			# Content
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);

		final Map<String, String> resultNull = FrontMatterTranslationHelper.extractTranslatableFields(
			doc, null
		);
		final Map<String, String> resultEmpty = FrontMatterTranslationHelper.extractTranslatableFields(
			doc, List.of()
		);

		assertTrue(resultNull.isEmpty());
		assertTrue(resultEmpty.isEmpty());
	}

	@Test
	@DisplayName("formats fields for prompt correctly")
	void shouldFormatFieldsForPromptCorrectly() {
		final Map<String, String> fields = Map.of(
			"title", "Hello World",
			"perex", "Sample description"
		);

		final String result = FrontMatterTranslationHelper.formatFieldsForPrompt(fields);

		assertTrue(result.contains("=== FRONT MATTER FIELDS TO TRANSLATE ==="));
		assertTrue(result.contains("[[title]]"));
		assertTrue(result.contains("Hello World"));
		assertTrue(result.contains("[[/title]]"));
		assertTrue(result.contains("[[perex]]"));
		assertTrue(result.contains("Sample description"));
		assertTrue(result.contains("[[/perex]]"));
	}

	@Test
	@DisplayName("returns empty string for empty fields map")
	void shouldReturnEmptyStringForEmptyFieldsMap() {
		final String result = FrontMatterTranslationHelper.formatFieldsForPrompt(Map.of());

		assertEquals("", result);
	}

	@Test
	@DisplayName("parses translated fields from LLM response")
	void shouldParseTranslatedFieldsFromLlmResponse() {
		final String llmResponse = """
			[[title]]
			Hallo Welt
			[[/title]]

			[[perex]]
			Dies ist eine Beispielbeschreibung
			[[/perex]]

			---
			title: Original Title
			---
			# Inhalt
			""";
		final Map<String, String> expectedFields = Map.of(
			"title", "Hello World",
			"perex", "Sample description"
		);

		final Map<String, String> result = FrontMatterTranslationHelper.parseTranslatedFields(
			llmResponse, expectedFields
		);

		assertEquals(2, result.size());
		assertEquals("Hallo Welt", result.get("title"));
		assertEquals("Dies ist eine Beispielbeschreibung", result.get("perex"));
	}

	@Test
	@DisplayName("ignores unexpected field blocks in response")
	void shouldIgnoreUnexpectedFieldBlocksInResponse() {
		final String llmResponse = """
			[[title]]
			Hallo Welt
			[[/title]]

			[[unexpectedField]]
			Some value
			[[/unexpectedField]]

			# Content
			""";
		final Map<String, String> expectedFields = Map.of("title", "Hello World");

		final Map<String, String> result = FrontMatterTranslationHelper.parseTranslatedFields(
			llmResponse, expectedFields
		);

		assertEquals(1, result.size());
		assertEquals("Hallo Welt", result.get("title"));
		assertFalse(result.containsKey("unexpectedField"));
	}

	@Test
	@DisplayName("throws exception when expected field is missing from response")
	void shouldThrowExceptionWhenFieldMissingFromResponse() {
		final String llmResponse = """
			[[title]]
			Translated Title
			[[/title]]

			# Content
			""";
		// Sent 2 fields, only 1 returned
		final Map<String, String> expectedFields = Map.of(
			"title", "Original Title",
			"perex", "Original Perex"
		);

		final IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() -> FrontMatterTranslationHelper.parseTranslatedFields(llmResponse, expectedFields)
		);

		assertTrue(exception.getMessage().contains("perex"));
		assertTrue(exception.getMessage().contains("missing front matter fields"));
	}

	@Test
	@DisplayName("throws exception when field value is empty in response")
	void shouldThrowExceptionWhenFieldValueEmptyInResponse() {
		final String llmResponse = """
			[[title]]
			Translated Title
			[[/title]]

			[[perex]]

			[[/perex]]

			# Content
			""";
		final Map<String, String> expectedFields = Map.of(
			"title", "Original Title",
			"perex", "Original Perex"
		);

		final IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() -> FrontMatterTranslationHelper.parseTranslatedFields(llmResponse, expectedFields)
		);

		assertTrue(exception.getMessage().contains("perex"));
	}

	@Test
	@DisplayName("handles multiline field values")
	void shouldHandleMultilineFieldValues() {
		final String llmResponse = """
			[[perex]]
			This is a multiline
			perex that spans
			several lines
			[[/perex]]

			# Content
			""";
		final Map<String, String> expectedFields = Map.of("perex", "Original");

		final Map<String, String> result = FrontMatterTranslationHelper.parseTranslatedFields(
			llmResponse, expectedFields
		);

		assertEquals(1, result.size());
		final String perex = result.get("perex");
		assertTrue(perex.contains("multiline"));
		assertTrue(perex.contains("several lines"));
	}

	@Test
	@DisplayName("extracts body from response removing field blocks")
	void shouldExtractBodyFromResponseRemovingFieldBlocks() {
		final String llmResponse = """
			[[title]]
			Hallo Welt
			[[/title]]

			=== FRONT MATTER FIELDS TO TRANSLATE ===

			---
			title: Original
			---
			# Content here
			""";
		final Map<String, String> fields = Map.of("title", "Hello World");

		final String result = FrontMatterTranslationHelper.extractBodyFromResponse(llmResponse, fields);

		assertFalse(result.contains("[[title]]"));
		assertFalse(result.contains("[[/title]]"));
		assertFalse(result.contains("Hallo Welt"));
		assertFalse(result.contains("=== FRONT MATTER FIELDS TO TRANSLATE ==="));
		assertTrue(result.contains("# Content here"));
		assertTrue(result.contains("---"));
	}

	@Test
	@DisplayName("returns original response when no fields to extract")
	void shouldReturnOriginalResponseWhenNoFieldsToExtract() {
		final String llmResponse = """
			---
			title: Hello
			---
			# Content
			""";

		final String result = FrontMatterTranslationHelper.extractBodyFromResponse(
			llmResponse, Map.of()
		);

		assertEquals(llmResponse, result);
	}

	@Test
	@DisplayName("hasTranslatableFields returns correct values")
	void shouldReturnCorrectValuesForHasTranslatableFields() {
		assertFalse(FrontMatterTranslationHelper.hasTranslatableFields(null));
		assertFalse(FrontMatterTranslationHelper.hasTranslatableFields(List.of()));
		assertTrue(FrontMatterTranslationHelper.hasTranslatableFields(List.of("title")));
		assertTrue(FrontMatterTranslationHelper.hasTranslatableFields(List.of("title", "perex")));
	}
}
