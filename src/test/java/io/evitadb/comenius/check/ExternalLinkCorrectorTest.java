package io.evitadb.comenius.check;

import io.evitadb.comenius.model.MarkdownDocument;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExternalLinkCorrector fixes stale anchor references in external files")
public class ExternalLinkCorrectorTest {

	private Path tempDir;
	private Path targetDir;
	private Log mockLog;
	private ForkJoinPool executor;

	@BeforeEach
	public void setUp() throws IOException {
		this.tempDir = Files.createTempDirectory("external-link-corrector-test-");
		this.targetDir = this.tempDir.resolve("target/es");
		Files.createDirectories(this.targetDir);
		this.mockLog = Mockito.mock(Log.class);
		this.executor = new ForkJoinPool(2);
	}

	@AfterEach
	public void tearDown() throws IOException {
		this.executor.shutdown();
		if (this.tempDir != null && Files.exists(this.tempDir)) {
			try (Stream<Path> walk = Files.walk(this.tempDir)) {
				walk.sorted(Comparator.reverseOrder())
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							// Ignore cleanup errors
						}
					});
			}
		}
	}

	@Test
	@DisplayName("corrects stale anchor in external file pointing to re-translated document")
	public void shouldCorrectStaleAnchorInExternalFile() {
		// The re-translated file: guide.md had "quick-start" → now has "inicio-rápido"
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom(
			"# Introduction\n\n## Quick Start\n\n## Usage"
		);
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Inicio Rápido\n\n## Uso"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		// External file links to guide.md#quick-start
		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String externalContent = "# Índice\n\nSee [guide](guide.md#quick-start) for details.";
		final Map<Path, String> externalFiles = Map.of(externalFile, externalContent);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(1, results.size());
		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("guide.md#inicio-rápido"));
		assertFalse(result.correctedContent().contains("quick-start"));
	}

	@Test
	@DisplayName("does not modify links to files not in changed set")
	public void shouldNotModifyLinksToUnchangedFiles() {
		// No changed files
		final Map<Path, AnchorChangeSet> changedFiles = Map.of();

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = "# Índice\n\n[link](other.md#section)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(1, results.size());
		assertEquals(0, results.get(0).anchorCorrections());
		assertEquals(content, results.get(0).correctedContent());
	}

	@Test
	@DisplayName("skips external links")
	public void shouldSkipExternalLinks() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = "# Índice\n\n[link](https://example.com#setup)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(0, results.get(0).anchorCorrections());
		assertEquals(content, results.get(0).correctedContent());
	}

	@Test
	@DisplayName("skips absolute links")
	public void shouldSkipAbsoluteLinks() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = "# Índice\n\n[link](/docs/guide.md#setup)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(0, results.get(0).anchorCorrections());
	}

	@Test
	@DisplayName("handles extensionless links with query parameters")
	public void shouldHandleExtensionlessLinksWithQueryParams() throws IOException {
		Files.createDirectories(this.targetDir.resolve("docs"));

		final Path changedFile = this.targetDir.resolve("docs/run-evitadb.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom("# Running\n\n## Docker Setup");
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Ejecución\n\n## Configuración de Docker"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		// Extensionless link with query param
		final Path externalFile = this.targetDir.resolve("docs/index.md")
			.toAbsolutePath().normalize();
		final String content = "# Docs\n\n[Run](run-evitadb?lang=java#docker-setup)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(1, results.size());
		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.anchorCorrections());
		assertTrue(
			result.correctedContent().contains(
				"run-evitadb?lang=java#configuración-de-docker"
			),
			"Expected corrected anchor, got: " + result.correctedContent()
		);
	}

	@Test
	@DisplayName("corrects multiple links in one file")
	public void shouldCorrectMultipleLinksInOneFile() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom(
			"# Introduction\n\n## Setup\n\n## Usage"
		);
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Configuración\n\n## Uso"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = """
			# Index

			- [Intro](guide.md#introduction)
			- [Setup](guide.md#setup)
			- [Usage](guide.md#usage)
			""";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		final LinkCorrectionResult result = results.get(0);
		assertEquals(3, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("guide.md#introducción"));
		assertTrue(result.correctedContent().contains("guide.md#configuración"));
		assertTrue(result.correctedContent().contains("guide.md#uso"));
	}

	@Test
	@DisplayName("skips links without anchors")
	public void shouldSkipLinksWithoutAnchors() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = "# Index\n\n[Guide](guide.md)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(0, results.get(0).anchorCorrections());
	}

	@Test
	@DisplayName("handles cross-directory relative links")
	public void shouldHandleCrossDirectoryLinks() throws IOException {
		Files.createDirectories(this.targetDir.resolve("docs"));
		Files.createDirectories(this.targetDir.resolve("guides"));

		final Path changedFile = this.targetDir.resolve("docs/setup.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom("# Installation\n\n## Prerequisites");
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Instalación\n\n## Requisitos Previos"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		// File in different directory links using ../
		final Path externalFile = this.targetDir.resolve("guides/quickstart.md")
			.toAbsolutePath().normalize();
		final String content = "# Quick Start\n\nSee [prereqs](../docs/setup.md#prerequisites)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains(
			"../docs/setup.md#requisitos-previos"
		));
	}

	@Test
	@DisplayName("returns empty results when no external files to scan")
	public void shouldReturnEmptyResultsWhenNoExternalFiles() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, Map.of(), this.executor
		);

		assertTrue(results.isEmpty());
	}

	@Test
	@DisplayName("preserves non-link content unchanged")
	public void shouldPreserveNonLinkContent() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final Path externalFile = this.targetDir.resolve("readme.md")
			.toAbsolutePath().normalize();
		final String content = """
			# Readme

			This is a paragraph with **bold** and *italic* text.

			```java
			System.out.println("Hello");
			```

			| Column | Value |
			|--------|-------|
			| A      | 1     |
			""";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(content, results.get(0).correctedContent());
	}

	@Test
	@DisplayName("skips anchor-only links (same-document references)")
	public void shouldSkipAnchorOnlyLinks() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(indexFrom("# Setup"))
		);

		final Path externalFile = this.targetDir.resolve("readme.md")
			.toAbsolutePath().normalize();
		final String content = "# Top\n\n## Setup\n\nSee [above](#top) for intro.";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(0, results.get(0).anchorCorrections());
	}

	@Test
	@DisplayName("corrects anchors in translatable front matter fields")
	public void shouldCorrectAnchorsInTranslatableFrontMatterFields() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom("# Quick Start\n\n## Setup");
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Inicio Rápido\n\n## Configuración"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String content = """
			---
			title: Overview
			perex: See [guide](guide.md#quick-start) for details
			---
			# Overview body
			""";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, List.of("perex"), this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.frontMatterCorrections());
		assertTrue(result.correctedContent().contains("guide.md#inicio-rápido"));
	}

	@Test
	@DisplayName("uses Levenshtein for full translation without old index")
	public void shouldUseLevenshteinForFullTranslation() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		// Full translation — no old index
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Configuración\n\n## Uso"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(newIndex)
		);

		// External file has anchor close to new via Levenshtein
		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		// "introduccion" (no accent) → should Levenshtein match "introducción"
		final String content = "# Index\n\n[Intro](guide.md#introduccion)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("guide.md#introducción"));
	}

	@Test
	@DisplayName("warns and leaves link unchanged when no match found")
	public void shouldWarnAndLeaveUnchangedWhenNoMatchFound() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex newIndex = indexFrom("# Setup\n\n## Usage");
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(newIndex)
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		// Completely unrelated anchor
		final String content = "# Index\n\n[link](guide.md#xyz-completely-unrelated)";
		final Map<Path, String> externalFiles = Map.of(externalFile, content);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		final LinkCorrectionResult result = results.get(0);
		assertEquals(0, result.anchorCorrections());
		assertFalse(result.errors().isEmpty());
		// Original link preserved
		assertTrue(result.correctedContent().contains("guide.md#xyz-completely-unrelated"));
	}

	@Test
	@DisplayName("processes multiple external files")
	public void shouldProcessMultipleExternalFiles() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom("# Setup\n\n## Usage");
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Configuración\n\n## Uso"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		final Path file1 = this.targetDir.resolve("readme.md")
			.toAbsolutePath().normalize();
		final Path file2 = this.targetDir.resolve("overview.md")
			.toAbsolutePath().normalize();
		final Map<Path, String> externalFiles = new HashMap<>();
		externalFiles.put(file1, "# Readme\n\n[link](guide.md#setup)");
		externalFiles.put(file2, "# Overview\n\n[link](guide.md#usage)");

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(2, results.size());
		final int totalCorrections = results.stream()
			.mapToInt(LinkCorrectionResult::anchorCorrections)
			.sum();
		assertEquals(2, totalCorrections);
	}

	@Test
	@DisplayName("keeps the link title when correcting a stale anchor")
	public void shouldPreserveLinkTitleWhenCorrectingAnchor() {
		final Path changedFile = this.targetDir.resolve("guide.md")
			.toAbsolutePath().normalize();
		final HeadingAnchorIndex oldIndex = indexFrom(
			"# Introduction\n\n## Quick Start\n\n## Usage"
		);
		final HeadingAnchorIndex newIndex = indexFrom(
			"# Introducción\n\n## Inicio Rápido\n\n## Uso"
		);
		final Map<Path, AnchorChangeSet> changedFiles = Map.of(
			changedFile, new AnchorChangeSet(oldIndex, newIndex)
		);

		final Path externalFile = this.targetDir.resolve("index.md")
			.toAbsolutePath().normalize();
		final String externalContent =
			"# Índice\n\nSee [guide](guide.md#quick-start \"Průvodce rychlým startem\") for details.";
		final Map<Path, String> externalFiles = Map.of(externalFile, externalContent);

		final ExternalLinkCorrector corrector = new ExternalLinkCorrector(
			this.targetDir, null, this.mockLog
		);
		final List<LinkCorrectionResult> results = corrector.correctAllParallel(
			changedFiles, externalFiles, this.executor
		);

		assertEquals(1, results.size());
		final LinkCorrectionResult result = results.get(0);
		assertEquals(1, result.anchorCorrections());
		assertEquals(
			"# Índice\n\nSee [guide](guide.md#inicio-rápido \"Průvodce rychlým startem\")"
				+ " for details.",
			result.correctedContent()
		);
	}

	/**
	 * Helper to create a HeadingAnchorIndex from markdown string.
	 */
	private static HeadingAnchorIndex indexFrom(String markdown) {
		final MarkdownDocument doc = new MarkdownDocument(markdown);
		return HeadingAnchorIndex.fromDocument(doc.getDocument());
	}
}
