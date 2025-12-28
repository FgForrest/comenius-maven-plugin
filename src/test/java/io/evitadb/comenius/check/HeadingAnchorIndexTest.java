package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HeadingAnchorIndex maintains ordered heading anchors for index-based lookup")
public class HeadingAnchorIndexTest {

	@Test
	@DisplayName("extracts headings in document order")
	public void shouldExtractHeadingsInDocumentOrder() {
		final String markdown = "# First\n\n## Second\n\n### Third";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(3, index.size());
		assertEquals("first", index.getAnchor(0));
		assertEquals("second", index.getAnchor(1));
		assertEquals("third", index.getAnchor(2));
	}

	@Test
	@DisplayName("returns anchors list in document order")
	public void shouldReturnAnchorsListInOrder() {
		final String markdown = "# Alpha\n\n## Beta\n\n## Gamma\n\n# Delta";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		final List<String> anchors = index.getAnchors();
		assertEquals(List.of("alpha", "beta", "gamma", "delta"), anchors);
	}

	@Test
	@DisplayName("finds anchor index case-insensitively")
	public void shouldFindAnchorIndexCaseInsensitively() {
		final String markdown = "# Getting Started\n\n## Installation";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(Optional.of(0), index.indexOf("getting-started"));
		assertEquals(Optional.of(0), index.indexOf("GETTING-STARTED"));
		assertEquals(Optional.of(0), index.indexOf("Getting-Started"));
		assertEquals(Optional.of(1), index.indexOf("installation"));
		assertEquals(Optional.of(1), index.indexOf("INSTALLATION"));
	}

	@Test
	@DisplayName("returns empty Optional for non-existent anchor")
	public void shouldReturnEmptyForNonExistentAnchor() {
		final String markdown = "# Heading";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(Optional.empty(), index.indexOf("nonexistent"));
		assertEquals(Optional.empty(), index.indexOf(""));
	}

	@Test
	@DisplayName("preserves duplicate headings with separate indices")
	public void shouldPreserveDuplicateHeadingsWithSeparateIndices() {
		final String markdown = """
			# Section
			## Section
			### Section
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		// Unlike MarkdownHeadingExtractor (Set), we preserve all occurrences
		assertEquals(3, index.size());
		assertEquals("section", index.getAnchor(0));
		assertEquals("section", index.getAnchor(1));
		assertEquals("section", index.getAnchor(2));

		// indexOf returns first occurrence
		assertEquals(Optional.of(0), index.indexOf("section"));
	}

	@Test
	@DisplayName("handles headings at all levels")
	public void shouldHandleHeadingsAtAllLevels() {
		final String markdown = """
			# H1
			## H2
			### H3
			#### H4
			##### H5
			###### H6
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(6, index.size());
		assertEquals("h1", index.getAnchor(0));
		assertEquals("h2", index.getAnchor(1));
		assertEquals("h3", index.getAnchor(2));
		assertEquals("h4", index.getAnchor(3));
		assertEquals("h5", index.getAnchor(4));
		assertEquals("h6", index.getAnchor(5));
	}

	@Test
	@DisplayName("handles headings with inline code")
	public void shouldHandleHeadingsWithInlineCode() {
		final String markdown = "# Using `git commit`\n\n## The `push` Command";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(2, index.size());
		assertEquals("using-git-commit", index.getAnchor(0));
		assertEquals("the-push-command", index.getAnchor(1));
	}

	@Test
	@DisplayName("handles empty document")
	public void shouldHandleEmptyDocument() {
		final String markdown = "";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(0, index.size());
		assertTrue(index.getAnchors().isEmpty());
	}

	@Test
	@DisplayName("handles document without headings")
	public void shouldHandleDocumentWithoutHeadings() {
		final String markdown = "Just some text without any headings.\n\nAnother paragraph.";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(0, index.size());
	}

	@Test
	@DisplayName("throws IndexOutOfBoundsException for invalid index")
	public void shouldThrowForInvalidIndex() {
		final String markdown = "# Only One Heading";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertThrows(IndexOutOfBoundsException.class, () -> index.getAnchor(1));
		assertThrows(IndexOutOfBoundsException.class, () -> index.getAnchor(-1));
	}

	@Test
	@DisplayName("returns immutable anchors list")
	public void shouldReturnImmutableAnchorsList() {
		final String markdown = "# Heading";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		final List<String> anchors = index.getAnchors();
		assertThrows(UnsupportedOperationException.class, () -> anchors.add("new"));
	}

	@Test
	@DisplayName("handles special characters like MarkdownHeadingExtractor")
	public void shouldHandleSpecialCharactersConsistently() {
		final String markdown = "# What's New in 2024!\n\n## Open / remap ports";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(2, index.size());
		assertEquals("whats-new-in-2024", index.getAnchor(0));
		// Slash removed leaves two spaces -> two hyphens
		assertEquals("open--remap-ports", index.getAnchor(1));
	}

	@Test
	@DisplayName("preserves heading order in complex document")
	public void shouldPreserveOrderInComplexDocument() {
		final String markdown = """
			# Introduction

			Some intro text.

			## Getting Started

			More text here.

			### Prerequisites

			- Item 1
			- Item 2

			### Installation

			```bash
			npm install
			```

			## Usage

			Final section.
			""";
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		final HeadingAnchorIndex index = HeadingAnchorIndex.fromDocument(doc.getDocument());

		assertEquals(5, index.size());
		assertEquals(List.of(
			"introduction",
			"getting-started",
			"prerequisites",
			"installation",
			"usage"
		), index.getAnchors());
	}

	@Test
	@DisplayName("correctly maps source anchor to translated anchor by index")
	public void shouldMapSourceToTranslatedAnchorByIndex() {
		// Source document (English)
		final String sourceMarkdown = """
			# Introduction
			## Setup
			## Usage
			""";
		final MarkdownDocument sourceDoc = new MarkdownDocument(sourceMarkdown);
		final HeadingAnchorIndex sourceIndex = HeadingAnchorIndex.fromDocument(
			sourceDoc.getDocument()
		);

		// Translated document (Spanish) - note: slugify strips accented chars
		final String translatedMarkdown = """
			# Introduccion
			## Configuracion
			## Uso
			""";
		final MarkdownDocument translatedDoc = new MarkdownDocument(translatedMarkdown);
		final HeadingAnchorIndex translatedIndex = HeadingAnchorIndex.fromDocument(
			translatedDoc.getDocument()
		);

		// Both have same number of headings
		assertEquals(sourceIndex.size(), translatedIndex.size());

		// Find "setup" in source -> index 1
		final Optional<Integer> setupIndex = sourceIndex.indexOf("setup");
		assertTrue(setupIndex.isPresent());
		assertEquals(1, setupIndex.get());

		// Get translated anchor at same index
		final String translatedAnchor = translatedIndex.getAnchor(setupIndex.get());
		assertEquals("configuracion", translatedAnchor);
	}
}
