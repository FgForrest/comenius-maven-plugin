package io.evitadb.comenius.check;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LinkCorrector corrects links in translated markdown files")
public class LinkCorrectorTest {

	private Path tempDir;
	private Path sourceDir;
	private Path targetDir;
	private Log mockLog;

	@BeforeEach
	public void setUp() throws IOException {
		this.tempDir = Files.createTempDirectory("link-corrector-test-");
		this.sourceDir = this.tempDir.resolve("source");
		this.targetDir = this.tempDir.resolve("target/es");
		Files.createDirectories(this.sourceDir);
		Files.createDirectories(this.targetDir);
		this.mockLog = Mockito.mock(Log.class);
	}

	@AfterEach
	public void tearDown() throws IOException {
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
	@DisplayName("corrects asset link from translated file to source asset")
	public void shouldCorrectAssetLink() throws Exception {
		// Setup source structure
		Files.createDirectories(this.sourceDir.resolve("docs"));
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("docs/guide.md"), "# Guide\n![logo](../images/logo.png)");
		writeFile(this.sourceDir.resolve("images/logo.png"), "PNG");

		// Setup target structure (translated file)
		Files.createDirectories(this.targetDir.resolve("docs"));
		final Path translatedFile = this.targetDir.resolve("docs/guide.md");
		final String translatedContent = "# Guía\n![logo](../images/logo.png)";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.assetCorrections());
		assertEquals(0, result.anchorCorrections());
		// The corrected path should navigate from target/es/docs back to source/images
		assertTrue(result.correctedContent().contains("../../../source/images/logo.png"),
			"Expected corrected path, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("corrects anchor in same-document link")
	public void shouldCorrectAnchorInSameDocument() throws Exception {
		// Setup source
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Setup
			## Usage
			""");

		// Translated file with same number of headings (no accents for test clarity)
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Introduccion

			See [below](#setup) for setup instructions.

			## Configuracion
			## Uso
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.assetCorrections());
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("#configuracion"),
			"Expected translated anchor, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("corrects anchor in cross-document link")
	public void shouldCorrectAnchorInCrossDocumentLink() throws Exception {
		// Setup source files
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide\nSee [other](other.md#details)");
		writeFile(this.sourceDir.resolve("other.md"), """
			# Other Document
			## Details
			""");

		// Setup translated files
		writeFile(this.targetDir.resolve("other.md"), """
			# Otro Documento
			## Detalles
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "# Guía\nVea [otro](other.md#details)";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.assetCorrections());
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("other.md#detalles"),
			"Expected translated anchor, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("fails with error when heading count differs")
	public void shouldFailWhenHeadingCountDiffers() throws Exception {
		// Setup source with 3 headings
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Setup
			## Usage
			""");

		// Translated file with only 2 headings (translator dropped one)
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Introducción

			See [below](#setup).

			## Configuración
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertFalse(result.isSuccess());
		assertTrue(result.errors().stream().anyMatch(e -> e.contains("heading count") || e.contains("Heading count")),
			"Expected heading count mismatch error, got: " + result.errors());
	}

	@Test
	@DisplayName("skips external links")
	public void shouldSkipExternalLinks() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Guía
			[External](https://example.com)
			[Email](mailto:test@example.com)
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.totalCorrections());
		assertTrue(result.correctedContent().contains("https://example.com"));
		assertTrue(result.correctedContent().contains("mailto:test@example.com"));
	}

	@Test
	@DisplayName("skips absolute links")
	public void shouldSkipAbsoluteLinks() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Guía
			[Absolute](/docs/readme.md)
			![Image](/images/logo.png)
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.totalCorrections());
		assertTrue(result.correctedContent().contains("/docs/readme.md"));
		assertTrue(result.correctedContent().contains("/images/logo.png"));
	}

	@Test
	@DisplayName("leaves markdown links unchanged when no anchor")
	public void shouldLeaveMarkdownLinksUnchangedWithoutAnchor() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide\n[other](other.md)");
		writeFile(this.sourceDir.resolve("other.md"), "# Other");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "# Guía\n[otro](other.md)";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.totalCorrections());
		assertEquals(translatedContent, result.correctedContent());
	}

	@Test
	@DisplayName("handles multiple links in one file")
	public void shouldHandleMultipleLinks() throws Exception {
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Setup
			""");
		writeFile(this.sourceDir.resolve("images/a.png"), "A");
		writeFile(this.sourceDir.resolve("images/b.png"), "B");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Introducción
			![a](images/a.png)
			![b](images/b.png)
			[setup](#setup)
			## Configuración
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(2, result.assetCorrections());
		assertEquals(1, result.anchorCorrections());
	}

	@Test
	@DisplayName("handles empty document")
	public void shouldHandleEmptyDocument() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), "");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.totalCorrections());
		assertEquals("", result.correctedContent());
	}

	@Test
	@DisplayName("respects exclusion patterns")
	public void shouldRespectExclusionPatterns() throws Exception {
		// Setup source files - CHANGELOG.md matches (?i).*\.md but is excluded
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide\n[changelog](CHANGELOG.md#v1)");
		writeFile(this.sourceDir.resolve("CHANGELOG.md"), "# Changelog\n## v1.0");
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("images/logo.png"), "PNG");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "# Guía\n[changelog](CHANGELOG.md#v1)\n![logo](images/logo.png)";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			List.of(Pattern.compile("CHANGELOG\\.md")),  // Exclude CHANGELOG.md
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		// CHANGELOG.md is treated as an asset (excluded from translation)
		// logo.png is also an asset
		assertEquals(2, result.assetCorrections());
		assertEquals(0, result.anchorCorrections());
	}

	@Test
	@DisplayName("processes multiple files with correctAll")
	public void shouldProcessMultipleFilesWithCorrectAll() throws Exception {
		writeFile(this.sourceDir.resolve("a.md"), "# A");
		writeFile(this.sourceDir.resolve("b.md"), "# B");

		final Path translatedA = this.targetDir.resolve("a.md");
		final Path translatedB = this.targetDir.resolve("b.md");

		final Map<Path, String> translatedFiles = Map.of(
			translatedA, "# A traducido",
			translatedB, "# B traducido"
		);

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final List<LinkCorrectionResult> results = corrector.correctAll(translatedFiles);

		assertEquals(2, results.size());
		assertTrue(results.stream().allMatch(LinkCorrectionResult::isSuccess));
	}

	@Test
	@DisplayName("handles deeply nested directory structures")
	public void shouldHandleDeeplyNestedStructure() throws Exception {
		// Create deep structure
		Files.createDirectories(this.sourceDir.resolve("a/b/c/d"));
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("a/b/c/d/guide.md"), "# Guide\n![logo](../../../../images/logo.png)");
		writeFile(this.sourceDir.resolve("images/logo.png"), "PNG");

		// Create corresponding target structure
		Files.createDirectories(this.targetDir.resolve("a/b/c/d"));

		final Path translatedFile = this.targetDir.resolve("a/b/c/d/guide.md");
		final String translatedContent = "# Guía\n![logo](../../../../images/logo.png)";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.assetCorrections());
		// Path should navigate from target/es/a/b/c/d back to source/images
		assertTrue(result.correctedContent().contains("source/images/logo.png"),
			"Expected path to source, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("handles special characters in headings for anchor translation")
	public void shouldHandleSpecialCharactersInHeadings() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), """
			# What's New in 2024!
			## Getting Started
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# ¡Novedades de 2024!

			See [what's new](#whats-new-in-2024).

			## Primeros pasos
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("#novedades-de-2024"),
			"Expected translated anchor, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("corrects links in translatable front matter fields")
	public void shouldCorrectLinksInTranslatableFrontMatterField() throws Exception {
		// Setup source - use simple ASCII headings to avoid accent issues
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Original Title
			perex: Check the [setup](#setup) section
			---
			# Introduction
			## Setup
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Titulo Traducido
			perex: Mira la seccion [ajustes](#setup)
			---
			# Introduccion
			## Ajustes
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title", "perex"),  // translatable fields
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains("#ajustes"),
			"Expected translated anchor in perex, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("corrects file path in non-translatable front matter field when file exists")
	public void shouldCorrectPathInNonTranslatableFrontMatterField() throws Exception {
		// Setup source with an image asset
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			image: images/hero.png
			---
			# Guide
			""");
		writeFile(this.sourceDir.resolve("images/hero.png"), "PNG");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			image: images/hero.png
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),  // only title is translatable, image is not
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.frontMatterCorrections());
		// Path should be corrected to navigate from target/es back to source/images
		assertTrue(result.correctedContent().contains("source/images/hero.png"),
			"Expected corrected path in image field, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("does not correct path in front matter when file does not exist")
	public void shouldNotCorrectNonExistentPath() throws Exception {
		// Setup source without the referenced file
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			image: images/nonexistent.png
			---
			# Guide
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			image: images/nonexistent.png
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.frontMatterCorrections());
		// Original path should be preserved
		assertTrue(result.correctedContent().contains("image: images/nonexistent.png"),
			"Expected unchanged path, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("does not correct external URLs in front matter")
	public void shouldNotCorrectExternalUrlsInFrontMatter() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			image: https://example.com/logo.png
			---
			# Guide
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			image: https://example.com/logo.png
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.frontMatterCorrections());
		assertTrue(result.correctedContent().contains("https://example.com/logo.png"),
			"Expected unchanged URL, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("does not correct absolute paths in front matter")
	public void shouldNotCorrectAbsolutePathsInFrontMatter() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			image: /images/logo.png
			---
			# Guide
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			image: /images/logo.png
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.frontMatterCorrections());
		assertTrue(result.correctedContent().contains("image: /images/logo.png"),
			"Expected unchanged absolute path, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("tracks front matter corrections separately from body corrections")
	public void shouldTrackFrontMatterCorrectionsSeparately() throws Exception {
		// Setup source with both body and front matter links
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			thumbnail: images/thumb.png
			---
			# Introduction
			## Setup
			![logo](images/logo.png)
			""");
		writeFile(this.sourceDir.resolve("images/thumb.png"), "PNG");
		writeFile(this.sourceDir.resolve("images/logo.png"), "PNG");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			thumbnail: images/thumb.png
			---
			# Introducción
			## Configuración
			![logo](images/logo.png)
			[setup](#setup)
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.frontMatterCorrections(), "Expected 1 front matter correction");
		assertEquals(1, result.assetCorrections(), "Expected 1 asset correction");
		assertEquals(1, result.anchorCorrections(), "Expected 1 anchor correction");
		assertEquals(3, result.totalCorrections(), "Expected 3 total corrections");
	}

	@Test
	@DisplayName("handles image link corrections in translatable front matter fields")
	public void shouldCorrectImageLinksInTranslatableFrontMatterField() throws Exception {
		// Setup source with image link in perex
		Files.createDirectories(this.sourceDir.resolve("images"));
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Original Title
			perex: Welcome! ![logo](images/logo.png)
			---
			# Guide
			""");
		writeFile(this.sourceDir.resolve("images/logo.png"), "PNG");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Título
			perex: ¡Bienvenido! ![logo](images/logo.png)
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title", "perex"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(1, result.assetCorrections());
		// The perex should contain corrected path
		assertTrue(result.correctedContent().contains("source/images/logo.png"),
			"Expected corrected image path in perex, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("preserves front matter fields without corrections")
	public void shouldPreserveFrontMatterFieldsWithoutCorrections() throws Exception {
		writeFile(this.sourceDir.resolve("guide.md"), """
			---
			title: Guide
			author: John Doe
			date: 2024-01-15
			---
			# Guide
			""");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			---
			title: Guía
			author: John Doe
			date: 2024-01-15
			---
			# Guía
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			List.of("title"),
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(translatedFile, translatedContent);

		assertTrue(result.isSuccess());
		assertEquals(0, result.frontMatterCorrections());
		assertTrue(result.correctedContent().contains("author: John Doe"),
			"Expected author field preserved");
		assertTrue(result.correctedContent().contains("date: 2024-01-15") ||
				result.correctedContent().contains("date: '2024-01-15'"),
			"Expected date field preserved");
	}

	private void writeFile(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.write(path, content.getBytes(StandardCharsets.UTF_8));
	}
}
