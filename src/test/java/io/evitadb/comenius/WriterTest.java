package io.evitadb.comenius;

import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Writer should validate markdown and append graymatter header")
public class WriterTest {

	@Test
	@DisplayName("shouldWriteToFileAndCreateParents")
	public void shouldWriteToFileAndCreateParents() throws IOException {
		// Create document with front matter
		final MarkdownDocument md = new MarkdownDocument("---\nauthor: John Doe\n---\n# Header\nContent");
		final Path tempDir = Files.createTempDirectory("writer-out-");
		final Path target = tempDir.resolve("a/b/c.md");

		new Writer().write(md, target);

		final String fileContent = Files.readString(target, StandardCharsets.UTF_8);
		final String normalized = fileContent.replace("\r\n", "\n").replace('\r', '\n');
		// header verification
		final int end = normalized.indexOf("---\n", 4);
		assertTrue(end > 0, "Front matter must end with closing fence");
		final String header = normalized.substring(0, end + 4);
		assertTrue(header.startsWith("---\n"));
		assertTrue(header.contains("author:"));
		assertTrue(header.contains("John Doe"));
		// body verification (renderer may add blank lines/newlines)
		final String body = normalized.substring(end + 4);
		assertTrue(body.contains("# Header"));
		assertTrue(body.contains("Content"));
	}

	@Test
	@DisplayName("shouldOverwriteExistingFileWhenPresent")
	public void shouldOverwriteExistingFileWhenPresent() throws IOException {
		final Path tempDir = Files.createTempDirectory("writer-over-");
		final Path target = tempDir.resolve("x/y.md");
		Files.createDirectories(target.getParent());
		Files.writeString(target, "OLD", StandardCharsets.UTF_8);

		// Create document with front matter
		final MarkdownDocument md = new MarkdownDocument("---\nk: v\n---\nBody");
		new Writer().write(md, target);

		final String fileContent = Files.readString(target, StandardCharsets.UTF_8);
		final String normalized = fileContent.replace("\r\n", "\n").replace('\r', '\n');
		final int end = normalized.indexOf("---\n", 4);
		assertTrue(end > 0, "Front matter must end with closing fence");
		final String header = normalized.substring(0, end + 4);
		assertTrue(header.contains("k:"));
		assertTrue(header.contains("v"));
		final String body = normalized.substring(end + 4);
		assertTrue(body.contains("Body"));
	}
}
