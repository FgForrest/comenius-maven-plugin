package io.evitadb.comenius;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Visitor that processes the matched file content.
 *
 * Use this interface to receive the matched file contents and the ordered collection of
 * instruction files that should be composed together for this file.
 */
public interface Visitor {
	/**
	 * Called for each file that matches the configured pattern.
	 *
	 * @param file path to the file that matched
	 * @param content full textual contents of the file
	 * @param instructionFiles ordered collection of instruction files to use (may be empty)
	 */
	void visit(@Nonnull Path file, @Nonnull String content, @Nonnull Collection<Path> instructionFiles);
}
