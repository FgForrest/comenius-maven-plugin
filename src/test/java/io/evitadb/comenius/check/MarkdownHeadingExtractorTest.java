package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarkdownHeadingExtractor extracts heading anchors from markdown AST")
public class MarkdownHeadingExtractorTest {

	@Test
	@DisplayName("extracts simple heading anchors")
	public void shouldExtractSimpleHeadingAnchors() {
		final String markdown = "# Introduction\n\nSome text.\n\n## Details";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(2, anchors.size());
		assertTrue(anchors.contains("introduction"));
		assertTrue(anchors.contains("details"));
	}

	@Test
	@DisplayName("slugifies headings with spaces")
	public void shouldSlugifyHeadingsWithSpaces() {
		final String markdown = "# Getting Started Guide";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("getting-started-guide"));
	}

	@Test
	@DisplayName("handles special characters in headings")
	public void shouldHandleSpecialCharactersInHeadings() {
		final String markdown = "# What's New in 2024!";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("whats-new-in-2024"));
	}

	@Test
	@DisplayName("extracts headings at all levels")
	public void shouldExtractHeadingsAtAllLevels() {
		final String markdown = """
			# H1
			## H2
			### H3
			#### H4
			##### H5
			###### H6
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(6, anchors.size());
		assertTrue(anchors.contains("h1"));
		assertTrue(anchors.contains("h2"));
		assertTrue(anchors.contains("h3"));
		assertTrue(anchors.contains("h4"));
		assertTrue(anchors.contains("h5"));
		assertTrue(anchors.contains("h6"));
	}

	@Test
	@DisplayName("handles headings with inline code")
	public void shouldHandleHeadingsWithInlineCode() {
		final String markdown = "# Using `git commit`";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("using-git-commit"));
	}

	@Test
	@DisplayName("handles duplicate heading text")
	public void shouldHandleDuplicateHeadingText() {
		final String markdown = """
			# Section
			## Section
			### Section
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		// Set deduplicates - we just get one "section" slug
		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("section"));
	}

	@Test
	@DisplayName("returns empty set for document without headings")
	public void shouldReturnEmptySetForNoHeadings() {
		final String markdown = "Just some text without any headings.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertTrue(anchors.isEmpty());
	}

	@Test
	@DisplayName("handles headings with numbers")
	public void shouldHandleHeadingsWithNumbers() {
		final String markdown = "# Chapter 1: Introduction";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("chapter-1-introduction"));
	}

	@Test
	@DisplayName("handles headings with hyphens")
	public void shouldHandleHeadingsWithHyphens() {
		final String markdown = "# Step-by-Step Guide";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("step-by-step-guide"));
	}

	@Test
	@DisplayName("handles empty document")
	public void shouldHandleEmptyDocument() {
		final String markdown = "";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertTrue(anchors.isEmpty());
	}

	@Test
	@DisplayName("slugify method produces expected output")
	public void shouldSlugifyCorrectly() {
		assertEquals("hello-world", MarkdownHeadingExtractor.slugify("Hello World"));
		assertEquals("test-123", MarkdownHeadingExtractor.slugify("Test 123"));
		assertEquals("no-special-chars", MarkdownHeadingExtractor.slugify("No! Special? Chars."));
		assertEquals("multiple---spaces", MarkdownHeadingExtractor.slugify("Multiple   Spaces"));
		assertEquals("leading-trailing", MarkdownHeadingExtractor.slugify("  Leading Trailing  "));
	}

	@Test
	@DisplayName("slugify preserves double hyphens from special characters")
	public void shouldPreserveDoubleHyphensFromSpecialChars() {
		// "Open / remap ports" - the / is removed, leaving two spaces which become two hyphens
		assertEquals("open--remap-ports", MarkdownHeadingExtractor.slugify("Open / remap ports"));
		// Explicit hyphen in text is preserved
		assertEquals("step-by-step", MarkdownHeadingExtractor.slugify("Step-by-Step"));
	}

	@Test
	@DisplayName("handles headings with bold and italic text")
	public void shouldHandleHeadingsWithFormattedText() {
		final String markdown = "# **Bold** and *Italic* Heading";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("bold-and-italic-heading"));
	}

	@Test
	@DisplayName("preserves national/Unicode characters in anchors (GitHub style)")
	public void shouldPreserveNationalCharactersInAnchors() {
		// Czech text with diacritics
		final String markdown = "# Struktura záznamu v úložišti";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final Set<String> anchors = MarkdownHeadingExtractor.extractAnchors(doc.getDocument());

		assertEquals(1, anchors.size());
		assertTrue(anchors.contains("struktura-záznamu-v-úložišti"));
	}

	@Test
	@DisplayName("slugify preserves various national characters")
	public void shouldSlugifyPreserveNationalCharacters() {
		// Czech
		assertEquals("struktura-záznamu-v-úložišti", MarkdownHeadingExtractor.slugify("Struktura záznamu v úložišti"));
		assertEquals("přehled-entit", MarkdownHeadingExtractor.slugify("Přehled entit"));
		// German
		assertEquals("größe-und-länge", MarkdownHeadingExtractor.slugify("Größe und Länge"));
		// French
		assertEquals("déjà-vu", MarkdownHeadingExtractor.slugify("Déjà vu"));
		// Spanish
		assertEquals("año-nuevo", MarkdownHeadingExtractor.slugify("Año Nuevo"));
		// Polish
		assertEquals("żółć", MarkdownHeadingExtractor.slugify("Żółć"));
	}
}
