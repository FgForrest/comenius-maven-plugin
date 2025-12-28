package io.evitadb.comenius.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PromptLoader should load and interpolate prompt templates")
public class PromptLoaderTest {

	private PromptLoader loader;

	@BeforeEach
	void setUp() {
		loader = new PromptLoader();
		loader.clearCache();
	}

	@Test
	@DisplayName("shouldLoadTemplateFromClasspath")
	void shouldLoadTemplateFromClasspath() {
		final String template = loader.loadTemplate("test-system.txt");
		assertNotNull(template);
		assertTrue(template.contains("You are a test translation assistant"));
		assertTrue(template.contains("{{locale}}"));
	}

	@Test
	@DisplayName("shouldReplaceSinglePlaceholder")
	void shouldReplaceSinglePlaceholder() {
		final String template = "Hello {{name}}!";
		final Map<String, String> values = Map.of("name", "World");
		final String result = loader.interpolate(template, values);
		assertEquals("Hello World!", result);
	}

	@Test
	@DisplayName("shouldReplaceMultiplePlaceholders")
	void shouldReplaceMultiplePlaceholders() {
		final String template = "Target: {{locale}} ({{localeTag}})";
		final Map<String, String> values = Map.of(
			"locale", "German",
			"localeTag", "de-DE"
		);
		final String result = loader.interpolate(template, values);
		assertEquals("Target: German (de-DE)", result);
	}

	@Test
	@DisplayName("shouldPreservePlaceholderWhenNoValue")
	void shouldPreservePlaceholderWhenNoValue() {
		final String template = "Hello {{name}}, welcome to {{place}}!";
		final Map<String, String> values = Map.of("name", "User");
		final String result = loader.interpolate(template, values);
		assertEquals("Hello User, welcome to {{place}}!", result);
	}

	@Test
	@DisplayName("shouldCacheLoadedTemplates")
	void shouldCacheLoadedTemplates() {
		// Load template twice - should return same string reference
		final String first = loader.loadTemplate("test-system.txt");
		final String second = loader.loadTemplate("test-system.txt");
		assertSame(first, second, "Cached template should return same instance");
	}

	@Test
	@DisplayName("shouldThrowWhenTemplateNotFound")
	void shouldThrowWhenTemplateNotFound() {
		final Exception exception = assertThrows(IllegalArgumentException.class, () ->
			loader.loadTemplate("non-existent-template.txt")
		);
		assertTrue(exception.getMessage().contains("Prompt template not found"));
	}

	@Test
	@DisplayName("shouldHandleTemplateWithNoPlaceholders")
	void shouldHandleTemplateWithNoPlaceholders() {
		final String template = "This is a static template with no placeholders.";
		final Map<String, String> values = Map.of("unused", "value");
		final String result = loader.interpolate(template, values);
		assertEquals(template, result);
	}

	@Test
	@DisplayName("shouldLoadAndInterpolateTemplate")
	void shouldLoadAndInterpolateTemplate() {
		final Map<String, String> values = Map.of(
			"locale", "German",
			"localeTag", "de-DE"
		);
		final String result = loader.loadAndInterpolate("test-system.txt", values);
		assertTrue(result.contains("Target language: German (de-DE)"));
	}

	@Test
	@DisplayName("shouldHandleEmptyValue")
	void shouldHandleEmptyValue() {
		final String template = "Instructions: {{customInstructions}}";
		final Map<String, String> values = Map.of("customInstructions", "");
		final String result = loader.interpolate(template, values);
		assertEquals("Instructions: ", result);
	}

	@Test
	@DisplayName("shouldHandleSpecialCharactersInValue")
	void shouldHandleSpecialCharactersInValue() {
		final String template = "Code: {{code}}";
		final Map<String, String> values = Map.of("code", "x = $100; y = \\n");
		final String result = loader.interpolate(template, values);
		assertEquals("Code: x = $100; y = \\n", result);
	}

	@Test
	@DisplayName("shouldClearCacheSuccessfully")
	void shouldClearCacheSuccessfully() {
		final String first = loader.loadTemplate("test-system.txt");
		loader.clearCache();
		final String second = loader.loadTemplate("test-system.txt");
		// After cache clear, new load should work but may not be same reference
		assertEquals(first, second, "Content should be equal after cache clear");
	}
}
