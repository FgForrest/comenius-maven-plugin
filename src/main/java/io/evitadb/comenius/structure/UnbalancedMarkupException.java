package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Raised when a document contains a tag from the vocabulary that never closes, closes without
 * opening, or closes out of order.
 *
 * This is a hard failure by design. An unbalanced tag is nearly always a forgotten closer, and
 * quietly demoting it to literal text reproduces the exact defect this machinery exists to
 * remove - a translation pipeline that splits a document in the middle of a component and
 * scatters its markup. Failing the build puts the problem where it can be fixed. Projects that
 * cannot clean their corpus immediately can set the vocabulary to lenient, which restores the
 * demote-to-text behaviour explicitly rather than silently.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class UnbalancedMarkupException extends RuntimeException {

	@Serial private static final long serialVersionUID = 1L;

	private final transient List<Defect> defects;

	/**
	 * A single unbalanced tag occurrence.
	 *
	 * @param name   the tag name
	 * @param offset character offset of the offending `&lt;`
	 * @param line   1-based line number of the offending occurrence
	 * @param reason human-readable explanation
	 */
	public record Defect(@Nonnull String name, int offset, int line, @Nonnull String reason) {
	}

	/**
	 * Creates the exception from a list of defects.
	 *
	 * @param location a human-readable description of what was being parsed, such as a file path
	 * @param defects  the unbalanced occurrences, in document order; must not be empty
	 */
	public UnbalancedMarkupException(@Nonnull String location, @Nonnull List<Defect> defects) {
		super(buildMessage(location, defects));
		this.defects = Collections.unmodifiableList(List.copyOf(defects));
	}

	/**
	 * Returns the individual defects, in document order.
	 *
	 * @return an unmodifiable list of defects
	 */
	@Nonnull
	public List<Defect> getDefects() {
		return this.defects;
	}

	/**
	 * Renders a message that names every offending tag with its line, so the failure points at
	 * the document rather than at the parser.
	 *
	 * @param location description of the document being parsed
	 * @param defects  the defects to report
	 * @return the exception message
	 */
	@Nonnull
	private static String buildMessage(@Nonnull String location, @Nonnull List<Defect> defects) {
		Objects.requireNonNull(location, "location must not be null");
		Objects.requireNonNull(defects, "defects must not be null");
		final StringBuilder message = new StringBuilder(128);
		message.append("Unbalanced markup in ").append(location).append(" (")
			.append(defects.size()).append(defects.size() == 1 ? " defect" : " defects").append("):");
		final int reported = Math.min(defects.size(), 10);
		for (int i = 0; i < reported; i++) {
			final Defect defect = defects.get(i);
			message.append("\n  line ").append(defect.line())
				.append(": <").append(defect.name()).append("> - ").append(defect.reason());
		}
		if (defects.size() > reported) {
			message.append("\n  ...and ").append(defects.size() - reported).append(" more");
		}
		message.append("\nFix the document, or set the markup vocabulary to lenient to treat ")
			.append("unbalanced tags as literal text.");
		return message.toString();
	}

}
