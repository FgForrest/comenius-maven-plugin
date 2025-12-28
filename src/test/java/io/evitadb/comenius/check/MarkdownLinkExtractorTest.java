package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarkdownLinkExtractor extracts links from markdown AST")
public class MarkdownLinkExtractorTest {

	@Test
	@DisplayName("extracts relative path links")
	public void shouldExtractRelativePathLinks() {
		final String markdown = "Check [this file](../other/file.md) for details.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertEquals("../other/file.md", link.destination());
		assertEquals("../other/file.md", link.path());
		assertFalse(link.isAbsolute());
		assertFalse(link.isExternal());
		assertTrue(link.shouldValidate());
	}

	@Test
	@DisplayName("extracts anchor-only links")
	public void shouldExtractAnchorOnlyLinks() {
		final String markdown = "See the [introduction](#introduction) section.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertEquals("#introduction", link.destination());
		assertEquals("introduction", link.anchor());
		assertTrue(link.isAnchorOnly());
		assertTrue(link.shouldValidate());
	}

	@Test
	@DisplayName("extracts combined path and anchor links")
	public void shouldExtractCombinedPathAndAnchorLinks() {
		final String markdown = "Read [the docs](./guide.md#getting-started) first.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertEquals("./guide.md#getting-started", link.destination());
		assertEquals("./guide.md", link.path());
		assertEquals("getting-started", link.anchor());
		assertFalse(link.isAnchorOnly());
		assertTrue(link.shouldValidate());
	}

	@Test
	@DisplayName("identifies external HTTP links and skips validation")
	public void shouldIdentifyExternalHttpLinks() {
		final String markdown = "Visit [Google](http://google.com).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertTrue(link.isExternal());
		assertFalse(link.shouldValidate());
	}

	@Test
	@DisplayName("identifies external HTTPS links and skips validation")
	public void shouldIdentifyExternalHttpsLinks() {
		final String markdown = "Visit [Google](https://www.google.com).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		assertTrue(links.get(0).isExternal());
		assertFalse(links.get(0).shouldValidate());
	}

	@Test
	@DisplayName("identifies mailto links as external")
	public void shouldIdentifyMailtoLinksAsExternal() {
		final String markdown = "Contact [support](mailto:support@example.com).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		assertTrue(links.get(0).isExternal());
	}

	@Test
	@DisplayName("identifies protocol-relative links as external")
	public void shouldIdentifyProtocolRelativeLinksAsExternal() {
		final String markdown = "Load [resource](//cdn.example.com/lib.js).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		assertTrue(links.get(0).isExternal());
	}

	@Test
	@DisplayName("extracts absolute path links")
	public void shouldExtractAbsolutePathLinks() {
		final String markdown = "See [the guide](/docs/guide.md).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertEquals("/docs/guide.md", link.destination());
		assertEquals("/docs/guide.md", link.path());
		assertTrue(link.isAbsolute());
		assertFalse(link.isExternal());
		assertTrue(link.shouldValidate());
	}

	@Test
	@DisplayName("extracts image links")
	public void shouldExtractImageLinks() {
		final String markdown = "Here is an image: ![alt text](../images/photo.png)";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		final LinkInfo link = links.get(0);
		assertEquals("../images/photo.png", link.destination());
		assertTrue(link.shouldValidate());
	}

	@Test
	@DisplayName("skips links inside fenced code blocks")
	public void shouldSkipLinksInsideFencedCodeBlocks() {
		final String markdown = """
			Here is some text with a [real link](real.md).

			```markdown
			This is a [fake link](fake.md) inside code.
			```

			And another [real link 2](real2.md).
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(2, links.size());
		assertEquals("real.md", links.get(0).destination());
		assertEquals("real2.md", links.get(1).destination());
	}

	@Test
	@DisplayName("skips links inside indented code blocks")
	public void shouldSkipLinksInsideIndentedCodeBlocks() {
		final String markdown = """
			Here is a [real link](real.md).

			    This is a [fake link](fake.md) in indented code.

			End with [real link 2](real2.md).
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(2, links.size());
		assertEquals("real.md", links.get(0).destination());
		assertEquals("real2.md", links.get(1).destination());
	}

	@Test
	@DisplayName("handles empty document")
	public void shouldHandleEmptyDocument() {
		final String markdown = "";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertTrue(links.isEmpty());
	}

	@Test
	@DisplayName("extracts multiple links from document")
	public void shouldExtractMultipleLinks() {
		final String markdown = """
			# Links

			- [Link 1](one.md)
			- [Link 2](two.md#section)
			- [Link 3](/absolute/three.md)
			- [External](https://example.com)
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(4, links.size());
		assertEquals("one.md", links.get(0).destination());
		assertEquals("two.md#section", links.get(1).destination());
		assertEquals("/absolute/three.md", links.get(2).destination());
		assertEquals("https://example.com", links.get(3).destination());
	}

	@Test
	@DisplayName("handles URL-encoded paths")
	public void shouldHandleUrlEncodedPaths() {
		final String markdown = "See [file with spaces](my%20file.md).";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(1, links.size());
		assertEquals("my%20file.md", links.get(0).destination());
		assertEquals("my%20file.md", links.get(0).path());
	}

	@Test
	@DisplayName("handles nested links in lists and paragraphs")
	public void shouldHandleNestedLinksInListsAndParagraphs() {
		final String markdown = """
			## Section

			Some text with [link1](a.md) and more.

			1. Item with [link2](b.md)
			2. Another with [link3](c.md)

			> Quote with [link4](d.md)
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final List<LinkInfo> links = MarkdownLinkExtractor.extractLinks(doc.getDocument());

		assertEquals(4, links.size());
	}
}
