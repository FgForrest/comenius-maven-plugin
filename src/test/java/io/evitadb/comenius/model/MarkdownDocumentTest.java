package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarkdownDocument YAML FrontMatter parsing.
 */
@DisplayName("MarkdownDocument should parse YAML FrontMatter from markdown file")
public class MarkdownDocumentTest {
	@Test
	@DisplayName("shouldParseFrontMatterWhenExampleFileProvided")
	public void shouldParseFrontMatterWhenExampleFileProvided() throws IOException {
		final String markdown = loadResource("/META-INF/example.md");
		final MarkdownDocument doc = new MarkdownDocument(markdown);

		// Simple scalar fields
		assertEquals("Listening to the pulse of transaction processing", doc.getProperty("title").orElse(null));
		assertEquals("16.05.2025", doc.getProperty("date").orElse(null));
		assertEquals("Jan Novotný", doc.getProperty("author").orElse(null));
		assertEquals("assets/images/19-transaction-processing.png", doc.getProperty("motive").orElse(null));
		assertEquals("done", doc.getProperty("proofreading").orElse(null));

		// Multiline block scalar 'perex' should be captured as a single string entry by commonmark front-matter
		final String perex = doc.getProperty("perex").orElseThrow(() -> new AssertionError("perex missing"));
		assertTrue(perex.startsWith("Data writes in evitaDB are specific"), "Perex should start with expected text");
		assertTrue(perex.contains("Compaction happens synchronously during transaction processing"),
			"Perex should contain middle sentence from the block");
	}

	@Test
	@DisplayName("shouldReturnEmptyPropertyWhenMissing")
	public void shouldReturnEmptyPropertyWhenMissing() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\n---\n\nContent");
		assertTrue(doc.getProperty("nonexistent").isEmpty());
	}

	@Test
	@DisplayName("shouldSetNewProperty")
	public void shouldSetNewProperty() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\n---\n\nContent");
		doc.setProperty("commit", "abc123");
		assertEquals("abc123", doc.getProperty("commit").orElse(null));
	}

	@Test
	@DisplayName("shouldOverwriteExistingProperty")
	public void shouldOverwriteExistingProperty() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Old\n---\n\nContent");
		doc.setProperty("title", "New");
		assertEquals("New", doc.getProperty("title").orElse(null));
	}

	@Test
	@DisplayName("shouldSerializeFrontMatterWithSingleValues")
	public void shouldSerializeFrontMatterWithSingleValues() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\nauthor: Author\n---\n\nContent");
		final String serialized = doc.serializeFrontMatter();
		assertTrue(serialized.startsWith("---\n"));
		assertTrue(serialized.endsWith("---\n"));
		assertTrue(serialized.contains("title:"));
		assertTrue(serialized.contains("author:"));
	}

	@Test
	@DisplayName("shouldQuoteSpecialCharactersInYaml")
	public void shouldQuoteSpecialCharactersInYaml() {
		final MarkdownDocument doc = new MarkdownDocument("# Content");
		doc.setProperty("date", "16.05.2025");
		doc.setProperty("special", "value:with:colons");
		final String serialized = doc.serializeFrontMatter();
		assertTrue(serialized.contains("'16.05.2025'") || serialized.contains("date: 16.05.2025"));
		assertTrue(serialized.contains("'value:with:colons'"));
	}

	@Test
	@DisplayName("shouldPreserveRawMarkdownBody")
	public void shouldPreserveRawMarkdownBody() {
		final String markdown = "---\ntitle: Test\n---\n\n# Header\n\nBody content.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final String body = doc.getBodyContent();
		assertTrue(body.contains("# Header"));
		assertTrue(body.contains("Body content."));
		assertFalse(body.contains("title:"));
	}

	@Test
	@DisplayName("shouldHandleDocumentWithoutFrontMatter")
	public void shouldHandleDocumentWithoutFrontMatter() throws IOException {
		final String markdown = loadResource("/META-INF/simple.md");
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		assertTrue(doc.getProperties().isEmpty());
		assertEquals(markdown, doc.getBodyContent());
	}

	@Test
	@DisplayName("shouldReturnEmptySerializationWhenNoProperties")
	public void shouldReturnEmptySerializationWhenNoProperties() {
		final MarkdownDocument doc = new MarkdownDocument("# Just content");
		assertEquals("", doc.serializeFrontMatter());
	}

	@Test
	@DisplayName("shouldReturnRawMarkdown")
	public void shouldReturnRawMarkdown() {
		final String markdown = "---\ntitle: Test\n---\n\n# Content";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		assertEquals(markdown, doc.getRawMarkdown());
	}

	@Test
	@DisplayName("shouldParseWithCommitField")
	public void shouldParseWithCommitField() throws IOException {
		final String markdown = loadResource("/META-INF/with-commit.md");
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		assertEquals("abc123def456789012345678901234567890abcd", doc.getProperty("commit").orElse(null));
		assertEquals("Test Document", doc.getProperty("title").orElse(null));
	}

	@Test
	@DisplayName("shouldMergeFrontMatterProperties")
	public void shouldMergeFrontMatterProperties() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\n---\n\nContent");
		doc.mergeFrontMatterProperties(Map.of(
			"author", List.of("New Author"),
			"commit", List.of("xyz789")
		));
		assertEquals("Test", doc.getProperty("title").orElse(null));
		assertEquals("New Author", doc.getProperty("author").orElse(null));
		assertEquals("xyz789", doc.getProperty("commit").orElse(null));
	}

	@Test
	@DisplayName("shouldGetAllProperties")
	public void shouldGetAllProperties() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\nauthor: Author\n---\n\nContent");
		final Map<String, List<String>> props = doc.getProperties();
		assertEquals(2, props.size());
		assertTrue(props.containsKey("title"));
		assertTrue(props.containsKey("author"));
	}

	@Test
	@DisplayName("shouldReturnImmutablePropertiesMap")
	public void shouldReturnImmutablePropertiesMap() {
		final MarkdownDocument doc = new MarkdownDocument("---\ntitle: Test\n---\n\nContent");
		final Map<String, List<String>> props = doc.getProperties();
		assertThrows(UnsupportedOperationException.class, () -> props.put("new", List.of("value")));
	}

	@Test
	@DisplayName("shouldParseCodeBlocksDocument")
	public void shouldParseCodeBlocksDocument() throws IOException {
		final String markdown = loadResource("/META-INF/code-blocks.md");
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		assertEquals("Code Examples", doc.getProperty("title").orElse(null));
		assertTrue(doc.getBodyContent().contains("```java"));
		assertTrue(doc.getBodyContent().contains("```python"));
	}

	@Test
	@DisplayName("shouldParseHtmlTagsDocument")
	public void shouldParseHtmlTagsDocument() throws IOException {
		final String markdown = loadResource("/META-INF/html-tags.md");
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		assertEquals("HTML Tags Test", doc.getProperty("title").orElse(null));
		assertTrue(doc.getBodyContent().contains("<Note type=\"info\">"));
		assertTrue(doc.getBodyContent().contains("<SourceClass>"));
	}

	private static String loadResource(final String path) throws IOException {
		final InputStream is = MarkdownDocumentTest.class.getResourceAsStream(path);
		if (is == null) {
			throw new IOException("Resource not found: " + path);
		}
		try (is) {
			final Scanner scanner = new Scanner(is, StandardCharsets.UTF_8);
			scanner.useDelimiter("\\A");
			final String text = scanner.hasNext() ? scanner.next() : "";
			return text;
		}
	}
}
