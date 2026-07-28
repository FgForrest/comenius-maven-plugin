package io.evitadb.comenius.model;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Thrown when a translated document's markup structure - tag sequence, inline tag multiset, or
 * blank line count - does not match the source document.
 *
 * Deliberately separate from {@link HeadingStructureMismatchException}: that check only sees
 * headings, so it passes a translation that silently dropped a heading-free block (a language
 * switch span, an include, an unclosed note) - exactly the class of defect this exception exists
 * to catch instead.
 */
public class StructuralMismatchException extends Exception {

	@Nonnull
	private final List<String> problems;

	/**
	 * Creates a new exception describing every structural problem found.
	 *
	 * @param problems non-empty list of problem descriptions
	 */
	public StructuralMismatchException(@Nonnull List<String> problems) {
		super(problems.size() + " structural problem(s): " + String.join("; ", problems));
		this.problems = problems;
	}

	/**
	 * Returns the individual problems found.
	 *
	 * @return the problem descriptions
	 */
	@Nonnull
	public List<String> getProblems() {
		return this.problems;
	}

}
