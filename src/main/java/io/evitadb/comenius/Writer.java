package io.evitadb.comenius;

import io.evitadb.comenius.model.MarkdownDocument;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writer outputs a Markdown document to a target file, including YAML front matter.
 * The document is written with its front matter properties serialized at the beginning,
 * followed by the markdown body content.
 */
public final class Writer {

	/**
	 * Writes the specified Markdown document to the target file, ensuring that
	 * the file's parent directory structure exists.
	 *
	 * The method serializes the front matter properties and prepends them to the
	 * body content before writing to the target file in UTF-8 encoding.
	 *
	 * @param markdown   the Markdown document to be written; must not be null
	 * @param targetFile the path to the target file where the Markdown document will be written;
	 *                   must not be null
	 * @throws IOException          if an I/O error occurs while creating directories or writing the file
	 * @throws NullPointerException if the markdown or targetFile parameter is null
	 */
	public void write(
		@Nonnull final MarkdownDocument markdown,
		@Nonnull final Path targetFile
	) throws IOException {
		Objects.requireNonNull(markdown, "markdown must not be null");
		Objects.requireNonNull(targetFile, "targetFile must not be null");

		final Path absolute = targetFile.toAbsolutePath().normalize();
		final Path parent = absolute.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		// Serialize front matter and get body content
		final String frontMatter = markdown.serializeFrontMatter();
		final String body = markdown.getBodyContent();

		// Combine front matter and body
		final String toWrite = frontMatter + body;
		final byte[] bytes = toWrite.getBytes(StandardCharsets.UTF_8);
		Files.write(absolute, bytes);
	}
}
