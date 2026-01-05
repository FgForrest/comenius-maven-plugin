package io.evitadb.comenius.diff;

/**
 * Enumeration of unified diff line types.
 * Each line in a diff hunk is prefixed with a character indicating its type.
 */
public enum DiffLineType {

	/**
	 * Context line - unchanged line shown for context.
	 * Prefixed with a space character in unified diff format.
	 */
	CONTEXT,

	/**
	 * Added line - new line that should be inserted.
	 * Prefixed with '+' character in unified diff format.
	 */
	ADD,

	/**
	 * Removed line - existing line that should be deleted.
	 * Prefixed with '-' character in unified diff format.
	 */
	REMOVE
}
