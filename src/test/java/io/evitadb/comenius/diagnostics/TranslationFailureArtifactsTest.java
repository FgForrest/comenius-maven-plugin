package io.evitadb.comenius.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TranslationFailureArtifacts should keep rejected translations for diagnosis")
class TranslationFailureArtifactsTest {

	private Path baseDir;

	@BeforeEach
	void setUp() throws IOException {
		this.baseDir = Files.createTempDirectory("failure-artifacts-test-");
	}

	@AfterEach
	void tearDown() throws IOException {
		if (Files.exists(this.baseDir)) {
			try (var paths = Files.walk(this.baseDir)) {
				for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	@DisplayName("shouldWriteEveryAttachmentAndTheReason")
	void shouldWriteEveryAttachmentAndTheReason() throws IOException {
		final Map<String, String> attachments = new LinkedHashMap<>();
		attachments.put("source.md", "## Heading\n\nSource text.\n");
		attachments.put("attempt-1.md", "## Nadpis\nPreklad.\n");

		final Path written = new TranslationFailureArtifacts(this.baseDir).record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/documentation/user/en/query/header/label.md"),
			"section-4",
			List.of("blank line count changed (19 -> 18)"),
			attachments
		);

		assertEquals(
			"## Heading\n\nSource text.\n",
			Files.readString(written.resolve("source.md"), StandardCharsets.UTF_8)
		);
		assertEquals(
			"## Nadpis\nPreklad.\n",
			Files.readString(written.resolve("attempt-1.md"), StandardCharsets.UTF_8)
		);

		final String reason = Files.readString(written.resolve("reason.txt"), StandardCharsets.UTF_8);
		assertTrue(reason.contains("blank line count changed (19 -> 18)"), reason);
		assertTrue(reason.contains("label.md"), reason);
		assertTrue(reason.contains("section-4"), reason);
		assertTrue(reason.contains("cs"), reason);
	}

	@Test
	@DisplayName("shouldSeparateLocaleDocumentAndUnit")
	void shouldSeparateLocaleDocumentAndUnit() throws IOException {
		final Path written = new TranslationFailureArtifacts(this.baseDir).record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/documentation/user/en/query/header/label.md"),
			"body",
			List.of("nope"),
			Map.of()
		);

		assertEquals("body", written.getFileName().toString());
		assertEquals("query_header_label.md", written.getParent().getFileName().toString());
		assertEquals("cs", written.getParent().getParent().getFileName().toString());
	}

	@Test
	@DisplayName("shouldNotCollideBetweenSameNamedDocumentsInDifferentDirectories")
	void shouldNotCollideBetweenSameNamedDocumentsInDifferentDirectories() throws IOException {
		final TranslationFailureArtifacts artifacts = new TranslationFailureArtifacts(this.baseDir);
		final Path first = artifacts.record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/documentation/user/en/query/header/label.md"),
			"body", List.of("a"), Map.of("source.md", "first")
		);
		final Path second = artifacts.record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/documentation/user/en/query/requirements/label.md"),
			"body", List.of("b"), Map.of("source.md", "second")
		);

		assertNotEquals(first, second);
		assertEquals("first", Files.readString(first.resolve("source.md"), StandardCharsets.UTF_8));
		assertEquals("second", Files.readString(second.resolve("source.md"), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("shouldNotLetAUnitNameEscapeTheBaseDirectory")
	void shouldNotLetAUnitNameEscapeTheBaseDirectory() throws IOException {
		final Path written = new TranslationFailureArtifacts(this.baseDir).record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/doc.md"),
			"body/../../escape",
			List.of("nope"),
			Map.of()
		);

		// the separators are gone, so what is left is one ordinary directory name, not a traversal
		assertEquals(1, written.getFileName().getNameCount(), written.toString());
		assertEquals(
			this.baseDir, written.normalize().getParent().getParent().getParent(),
			written + " must sit exactly three levels under " + this.baseDir
		);
		assertTrue(Files.isDirectory(written));
	}

	@Test
	@DisplayName("shouldNotLetAnAttachmentNameEscapeTheUnitDirectory")
	void shouldNotLetAnAttachmentNameEscapeTheUnitDirectory() throws IOException {
		final Path written = new TranslationFailureArtifacts(this.baseDir).record(
			Locale.forLanguageTag("cs"),
			Path.of("/repo/doc.md"),
			"body",
			List.of("nope"),
			Map.of("../../../escaped.md", "content")
		);

		assertFalse(
			Files.exists(this.baseDir.resolve("escaped.md")),
			"an attachment name must not be able to write outside its unit directory"
		);
		try (var entries = Files.list(written)) {
			assertTrue(
				entries.allMatch(entry -> entry.getParent().equals(written)),
				"every artifact must land inside " + written
			);
		}
	}

	@Test
	@DisplayName("shouldCreateNothingUntilSomethingIsRecorded")
	void shouldCreateNothingUntilSomethingIsRecorded() throws IOException {
		final Path unused = this.baseDir.resolve("never-used");
		new TranslationFailureArtifacts(unused);

		assertFalse(Files.exists(unused), "a run in which nothing fails must leave no trace");
	}
}
