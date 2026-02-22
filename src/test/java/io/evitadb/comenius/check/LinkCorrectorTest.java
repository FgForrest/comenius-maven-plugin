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

	@Test
	@DisplayName("keeps extensionless markdown link relative when .md file exists in source")
	public void shouldKeepExtensionlessMarkdownLinkRelative() throws Exception {
		// Source has other-doc.md but link references it without .md extension
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide\n[other](other-doc)");
		writeFile(this.sourceDir.resolve("other-doc.md"), "# Other Doc");

		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "# Guía\n[otro](other-doc)";

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
		assertEquals(0, result.assetCorrections(), "Extensionless markdown link should not be treated as asset");
		assertTrue(result.correctedContent().contains("[otro](other-doc)"),
			"Link should remain relative, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("keeps markdown link with query params relative when .md file exists")
	public void shouldKeepMarkdownLinkWithQueryParamsRelative() throws Exception {
		// Source has run-evitadb.md but link uses ?lang=java (no .md extension)
		Files.createDirectories(this.sourceDir.resolve("get-started"));
		Files.createDirectories(this.sourceDir.resolve("use/connectors"));
		writeFile(this.sourceDir.resolve("use/connectors/java.md"),
			"# Java\n[Run](../../get-started/run-evitadb?lang=java)");
		writeFile(this.sourceDir.resolve("get-started/run-evitadb.md"), "# Run evitaDB");

		Files.createDirectories(this.targetDir.resolve("use/connectors"));
		final Path translatedFile = this.targetDir.resolve("use/connectors/java.md");
		final String translatedContent = "# Java\n[Spuštění](../../get-started/run-evitadb?lang=java)";

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
		assertEquals(0, result.assetCorrections(),
			"Link with query params to markdown file should not be treated as asset");
		assertTrue(result.correctedContent().contains("../../get-started/run-evitadb?lang=java"),
			"Link should remain relative with query params, got: " + result.correctedContent());
		assertFalse(result.correctedContent().contains("/source/"),
			"Link should NOT point to source directory, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("translates anchor in extensionless markdown link with query params")
	public void shouldTranslateAnchorInExtensionlessMarkdownLinkWithQueryParams() throws Exception {
		Files.createDirectories(this.sourceDir.resolve("get-started"));
		Files.createDirectories(this.sourceDir.resolve("use/connectors"));
		writeFile(this.sourceDir.resolve("use/connectors/java.md"),
			"# Java\n[Create DB](../../get-started/create-db?lang=java#open-session)");
		writeFile(this.sourceDir.resolve("get-started/create-db.md"),
			"# Create Database\n## Open Session");

		Files.createDirectories(this.targetDir.resolve("use/connectors"));
		Files.createDirectories(this.targetDir.resolve("get-started"));
		writeFile(this.targetDir.resolve("get-started/create-db.md"),
			"# Vytvoreni Databaze\n## Otevreni Relace");

		final Path translatedFile = this.targetDir.resolve("use/connectors/java.md");
		final String translatedContent =
			"# Java\n[Vytvorit DB](../../get-started/create-db?lang=java#open-session)";

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
		assertTrue(result.correctedContent().contains(
			"../../get-started/create-db?lang=java#otevreni-relace"),
			"Expected translated anchor with preserved query params, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("still corrects actual asset links after extensionless fix")
	public void shouldStillCorrectActualAssetLinksAfterExtensionlessFix() throws Exception {
		// Ensure assets (.png, .pdf, etc.) are still recalculated correctly
		Files.createDirectories(this.sourceDir.resolve("docs"));
		Files.createDirectories(this.sourceDir.resolve("docs/assets"));
		writeFile(this.sourceDir.resolve("docs/guide.md"),
			"# Guide\n![img](assets/logo.png)\n[pdf](assets/manual.pdf)");
		writeFile(this.sourceDir.resolve("docs/assets/logo.png"), "PNG");
		writeFile(this.sourceDir.resolve("docs/assets/manual.pdf"), "PDF");

		Files.createDirectories(this.targetDir.resolve("docs"));
		final Path translatedFile = this.targetDir.resolve("docs/guide.md");
		final String translatedContent =
			"# Guía\n![img](assets/logo.png)\n[pdf](assets/manual.pdf)";

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
		assertEquals(2, result.assetCorrections(), "Both assets should be corrected");
		assertTrue(result.correctedContent().contains("source/docs/assets/logo.png"),
			"PNG asset should point to source, got: " + result.correctedContent());
		assertTrue(result.correctedContent().contains("source/docs/assets/manual.pdf"),
			"PDF asset should point to source, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("repairs already-broken markdown link pointing to source directory")
	public void shouldRepairBrokenMarkdownLinkPointingToSourceDir() throws Exception {
		// Simulate the bug: translated file has link going through ../source/ instead of
		// staying relative within the target directory structure
		Files.createDirectories(this.sourceDir.resolve("get-started"));
		Files.createDirectories(this.sourceDir.resolve("use/connectors"));
		writeFile(this.sourceDir.resolve("use/connectors/java.md"),
			"# Java\n[Run](../../get-started/run-evitadb?lang=java)");
		writeFile(this.sourceDir.resolve("get-started/run-evitadb.md"), "# Run evitaDB");

		Files.createDirectories(this.targetDir.resolve("use/connectors"));
		final Path translatedFile = this.targetDir.resolve("use/connectors/java.md");
		// The broken link goes ../../../source/get-started/run-evitadb?lang=java
		// instead of staying ../../get-started/run-evitadb?lang=java
		final String translatedContent =
			"# Java\n[Spuštění](../../../source/get-started/run-evitadb?lang=java)";

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
		assertTrue(result.totalCorrections() > 0, "Should count the path correction");
		assertTrue(result.correctedContent().contains("../../get-started/run-evitadb?lang=java"),
			"Broken link should be repaired to correct relative path, got: " + result.correctedContent());
		assertFalse(result.correctedContent().contains("../../../source/"),
			"Broken link through source dir should be gone, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("leaves correct markdown link unchanged during repair")
	public void shouldLeaveCorrectMarkdownLinkUnchangedDuringRepair() throws Exception {
		// A link that is already correct should not be modified
		Files.createDirectories(this.sourceDir.resolve("get-started"));
		Files.createDirectories(this.sourceDir.resolve("use/connectors"));
		writeFile(this.sourceDir.resolve("use/connectors/java.md"),
			"# Java\n[Run](../../get-started/run-evitadb?lang=java)");
		writeFile(this.sourceDir.resolve("get-started/run-evitadb.md"), "# Run evitaDB");

		Files.createDirectories(this.targetDir.resolve("use/connectors"));
		final Path translatedFile = this.targetDir.resolve("use/connectors/java.md");
		// The link is already correct
		final String translatedContent =
			"# Java\n[Spuštění](../../get-started/run-evitadb?lang=java)";

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
		assertEquals(0, result.totalCorrections(), "No corrections needed for correct link");
		assertEquals(translatedContent, result.correctedContent(),
			"Content should be unchanged");
	}

	@Test
	@DisplayName("autocorrects anchor to closest match when exact match fails")
	public void shouldAutocorrectAnchorToClosestMatch() throws Exception {
		// Source file with "Getting Started" heading
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Getting Started
			## Usage
			""");

		// Translated file links to "#gettin-started" (typo — missing 'g')
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Introduccion

			See [below](#gettin-started) for setup.

			## Primeros Pasos
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
		assertEquals(1, result.anchorCorrections());
		// The typo "gettin-started" should be fuzzy-matched to source's "getting-started"
		// at index 1, then translated to "primeros-pasos" at index 1
		assertTrue(result.correctedContent().contains("#primeros-pasos"),
			"Expected autocorrected anchor, got: " + result.correctedContent());
		assertFalse(result.correctedContent().contains("#gettin-started"),
			"Original typo should be replaced, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("does not warn when anchor-only link already exists in translated file")
	public void shouldNotWarnWhenAnchorAlreadyExistsInTranslatedFile() throws Exception {
		// Source has English headings
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Setup
			## Usage
			""");

		// Translated file already has correct translated anchors in links
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Introduccion

			See [below](#configuracion) for setup.

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
		assertEquals(0, result.anchorCorrections(),
			"Already-correct translated anchor should not count as correction");
		assertTrue(result.correctedContent().contains("#configuracion"),
			"Anchor should remain unchanged, got: " + result.correctedContent());
		Mockito.verify(this.mockLog, Mockito.never()).warn(Mockito.anyString());
	}

	@Test
	@DisplayName("does not warn when cross-document anchor already translated")
	public void shouldNotWarnWhenCrossDocumentAnchorAlreadyTranslated() throws Exception {
		// Source files with English headings
		writeFile(this.sourceDir.resolve("guide.md"), "# Guide\nSee [other](other.md#details)");
		writeFile(this.sourceDir.resolve("other.md"), """
			# Other Document
			## Details
			""");

		// Translated target file has translated headings
		writeFile(this.targetDir.resolve("other.md"), """
			# Otro Documento
			## Detalles
			""");

		// Translated file already links to the correct translated anchor
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = "# Guía\nVea [otro](other.md#detalles)";

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
		assertEquals(0, result.anchorCorrections(),
			"Already-correct translated anchor should not count as correction");
		assertTrue(result.correctedContent().contains("other.md#detalles"),
			"Anchor should remain unchanged, got: " + result.correctedContent());
		Mockito.verify(this.mockLog, Mockito.never()).warn(Mockito.anyString());
	}

	@Test
	@DisplayName("autocorrects via translated Levenshtein when anchor is in target language")
	public void shouldAutocorrectViaTranslatedLevenshtein() throws Exception {
		// Source has English headings
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Cleaning Up the Mess
			## Usage
			""");

		// Translated file has Czech headings
		final Path translatedFile = this.targetDir.resolve("guide.md");
		// Anchor "uklízení-nepořádku" is close to translated "úklid-nepořádku"
		// (Levenshtein within threshold in same language)
		// but nothing like English "cleaning-up-the-mess"
		final String translatedContent = """
			# Úvod

			See [cleanup](#úklid-nepořádku).

			## Úklid nepořádku
			## Použití
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(
			translatedFile, translatedContent
		);

		assertTrue(result.isSuccess());
		// The anchor "úklid-nepořádku" should exact-match the translated heading
		// "úklid-nepořádku" via Phase A (Levenshtein on translated index)
		assertEquals(0, result.anchorCorrections(),
			"Exact match in translated index, no correction needed");
		assertTrue(result.correctedContent().contains("#úklid-nepořádku"),
			"Anchor should be preserved, got: " + result.correctedContent());
	}

	@Test
	@DisplayName("autocorrects via token overlap when anchor shares tokens with translated heading")
	public void shouldAutocorrectViaTokenOverlap() throws Exception {
		// Source has English headings
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Recommended Development Environments IDE
			## Usage
			""");

		// Translated file has Czech headings
		final Path translatedFile = this.targetDir.resolve("guide.md");
		// Link uses truncated Czech anchor "doporučená-ide"
		// Full translated heading anchor is "doporučená-vývojová-prostředí-ide"
		// Token overlap: both share "doporučená" and "ide" → 2/2 match
		final String translatedContent = """
			# Úvod

			See [IDE](#doporučená-ide).

			## Doporučená vývojová prostředí IDE
			## Použití
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		final LinkCorrectionResult result = corrector.correctLinks(
			translatedFile, translatedContent
		);

		assertTrue(result.isSuccess());
		assertEquals(1, result.anchorCorrections());
		assertTrue(result.correctedContent().contains(
			"#doporučená-vývojová-prostředí-ide"),
			"Expected token-overlap autocorrected anchor, got: "
				+ result.correctedContent());
	}

	@Test
	@DisplayName("includes linking file name in warning message")
	public void shouldIncludeLinkingFileInWarningMessage() throws Exception {
		// Source has English headings
		writeFile(this.sourceDir.resolve("guide.md"), """
			# Introduction
			## Setup
			""");

		// Translated file with completely unrecognizable anchor
		final Path translatedFile = this.targetDir.resolve("guide.md");
		final String translatedContent = """
			# Úvod

			See [xyz](#xyz-abc-completely-unknown).

			## Nastavení
			""";

		final LinkCorrector corrector = new LinkCorrector(
			this.sourceDir,
			this.targetDir,
			Pattern.compile("(?i).*\\.md"),
			null,
			null,
			this.mockLog
		);

		corrector.correctLinks(translatedFile, translatedContent);

		// Verify warning includes the linking file name
		Mockito.verify(this.mockLog).warn(Mockito.argThat(
			(String msg) -> msg.contains("guide.md")
				&& msg.contains("xyz-abc-completely-unknown")
		));
	}

	private void writeFile(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.write(path, content.getBytes(StandardCharsets.UTF_8));
	}
}
