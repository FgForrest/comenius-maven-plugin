package io.evitadb.comenius.model;

/**
 * Represents the alignment relationship between a section in the old document
 * and a section in the new document. Used by {@link SectionAligner} to determine
 * which sections need retranslation during incremental updates.
 *
 * @param type     the alignment type (UNCHANGED, MODIFIED, ADDED, DELETED)
 * @param oldIndex index in the old section list, or -1 for ADDED sections
 * @param newIndex index in the new section list, or -1 for DELETED sections
 */
public record SectionAlignment(
	Type type,
	int oldIndex,
	int newIndex
) {

	/**
	 * The type of alignment between old and new sections.
	 */
	public enum Type {
		/** Section content hash matches — keep existing translation. */
		UNCHANGED,
		/** Section exists in both but content changed — retranslate. */
		MODIFIED,
		/** Section only exists in new document — translate from scratch. */
		ADDED,
		/** Section only exists in old document — drop from translation. */
		DELETED
	}
}
