package io.evitadb.comenius;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * Visitor that processes the matched file content.
 *
 * Use this interface to receive the matched file contents and the accumulated
 * instructions from `.comenius-instructions` files in the directory hierarchy.
 */
public interface Visitor {
	/**
	 * Called for each file that matches the configured pattern.
	 *
	 * @param file path to the file that matched
	 * @param content full textual contents of the file
	 * @param instructions accumulated instructions from `.comenius-instructions` files (may be null)
	 */
	void visit(@Nonnull Path file, @Nonnull String content, @Nullable String instructions);
}
