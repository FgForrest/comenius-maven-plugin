package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnchorChangeSet tracks old-to-new anchor mapping for cross-document correction")
public class AnchorChangeSetTest {

	@Test
	@DisplayName("returns anchor unchanged when it exists in new index")
	public void shouldReturnAnchorUnchangedWhenExistsInNewIndex() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Setup\n\n## Usage");
		final HeadingAnchorIndex newIndex = indexFrom("# Configuración\n\n## Usage");
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		// "usage" exists in new index — returned as-is
		final String result = changeSet.correctAnchor("usage");
		assertEquals("usage", result);
	}

	@Test
	@DisplayName("maps anchor by position when old index available and sizes match")
	public void shouldMapByPositionWhenOldIndexAvailableAndSizesMatch() {
		final HeadingAnchorIndex oldIndex = indexFrom(
			"# Introduction\n\n## Quick Start\n\n## Advanced Usage"
		);
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Inicio Rápido\n\n## Uso Avanzado"
		);
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		// "quick-start" at old index 1 → "inicio-rápido" at new index 1
		final String result = changeSet.correctAnchor("quick-start");
		assertNotNull(result);
		assertEquals("inicio-rápido", result);
	}

	@Test
	@DisplayName("falls back to Levenshtein when index sizes differ")
	public void shouldFallBackToLevenshteinWhenSizesMismatch() {
		final HeadingAnchorIndex oldIndex = indexFrom(
			"# Introduction\n\n## Getting Started\n\n## Usage"
		);
		// New has an extra heading — sizes don't match
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Requisitos\n\n## Primeros Pasos\n\n## Uso"
		);
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		// "introduction" doesn't exist in new, old→new sizes differ,
		// Levenshtein should match "introducción" (1 edit: accent)
		final String result = changeSet.correctAnchor("introduction");
		assertNotNull(result);
		assertEquals("introducción", result);
	}

	@Test
	@DisplayName("falls back to Levenshtein when anchor not found in old index")
	public void shouldFallBackToLevenshteinWhenAnchorNotInOldIndex() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Setup\n\n## Usage");
		final HeadingAnchorIndex newIndex = indexFrom("# Configuración\n\n## Uso");
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		// "setu" is not in old index but is close to... hmm, it's not in new either.
		// Let's use "configuracion" (without accent) which is close to "configuración"
		final String result = changeSet.correctAnchor("configuracion");
		assertNotNull(result);
		assertEquals("configuración", result);
	}

	@Test
	@DisplayName("returns null when no match found")
	public void shouldReturnNullWhenNoMatchFound() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Getting Started\n\n## Installation");
		final HeadingAnchorIndex newIndex = indexFrom("# Primeros Pasos\n\n## Instalación");
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		// Completely different anchor — no reasonable match
		final String result = changeSet.correctAnchor("xyz-completely-unrelated");
		assertNull(result);
	}

	@Test
	@DisplayName("handles full translation with no old index using Levenshtein")
	public void shouldHandleFullTranslationWithNoOldIndex() {
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Primeros Pasos\n\n## Instalación\n\n## Uso"
		);
		final AnchorChangeSet changeSet = new AnchorChangeSet(newIndex);

		// No old index — Levenshtein only
		// "instalacion" (no accent) should match "instalación"
		final String result = changeSet.correctAnchor("instalacion");
		assertNotNull(result);
		assertEquals("instalación", result);
	}

	@Test
	@DisplayName("returns anchor as-is in full translation when it exists in new index")
	public void shouldReturnAnchorAsIsInFullTranslationWhenExists() {
		final HeadingAnchorIndex newIndex = indexFrom("# Setup\n\n## Usage");
		final AnchorChangeSet changeSet = new AnchorChangeSet(newIndex);

		final String result = changeSet.correctAnchor("setup");
		assertEquals("setup", result);
	}

	@Test
	@DisplayName("detects changes when old and new anchor lists differ")
	public void shouldDetectChanges() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Setup\n\n## Usage");
		final HeadingAnchorIndex newIndex = indexFrom("# Configuración\n\n## Uso");
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		assertTrue(changeSet.hasChanges());
	}

	@Test
	@DisplayName("detects no changes when anchor lists are identical")
	public void shouldDetectNoChanges() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Setup\n\n## Usage");
		final HeadingAnchorIndex newIndex = indexFrom("# Setup\n\n## Usage");
		final AnchorChangeSet changeSet = new AnchorChangeSet(oldIndex, newIndex);

		assertFalse(changeSet.hasChanges());
	}

	@Test
	@DisplayName("always reports changes for full translation with no old index")
	public void shouldAlwaysReportChangesForFullTranslation() {
		final HeadingAnchorIndex newIndex = indexFrom("# Setup\n\n## Usage");
		final AnchorChangeSet changeSet = new AnchorChangeSet(newIndex);

		assertTrue(changeSet.hasChanges());
	}

	@Test
	@DisplayName("returns getters correctly")
	public void shouldReturnGettersCorrectly() {
		final HeadingAnchorIndex oldIndex = indexFrom("# Old");
		final HeadingAnchorIndex newIndex = indexFrom("# New");

		final AnchorChangeSet incremental = new AnchorChangeSet(oldIndex, newIndex);
		assertNotNull(incremental.getOldIndex());
		assertNotNull(incremental.getNewIndex());

		final AnchorChangeSet full = new AnchorChangeSet(newIndex);
		assertNull(full.getOldIndex());
		assertNotNull(full.getNewIndex());
	}

	/**
	 * Helper to create a HeadingAnchorIndex from markdown string.
	 */
	private static HeadingAnchorIndex indexFrom(String markdown) {
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		return HeadingAnchorIndex.fromDocument(doc.getDocument());
	}
}
