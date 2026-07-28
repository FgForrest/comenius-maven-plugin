package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a scope tree into the list of pieces that will actually be translated.
 *
 * Granularity is what the tree makes *available*; packing decides what is *used*. Measurement on
 * a real corpus put the largest indivisible leaf at 632 bytes, so sending leaves directly would
 * mean thousands of tiny requests and a translator that never sees enough context to choose a
 * pronoun. The packer therefore merges greedily up to `targetUnitSize` and only descends into a
 * subtree when that subtree alone is too large to send whole.
 *
 * Two rules carry the correctness of the whole design:
 *
 * - **A cut may only fall on a block boundary.** An inline tag - and the prose on either side of
 *   it - is never separated, because "The `&lt;Term&gt;entity&lt;/Term&gt;` is stored" splits into
 *   three fragments no translator can work with, and Czech word order will not put them back.
 * - **A cut may never fall strictly inside a structural tag.** This follows from packing sibling
 *   runs rather than byte ranges, and is asserted directly rather than inferred, because a
 *   splitter that violates it still concatenates back perfectly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class UnitPacker {

	private final TagVocabulary vocabulary;
	private final Settings settings;

	/**
	 * Size policy for packing.
	 *
	 * `maxUnitSize` exists because atomicity would otherwise be a hard floor: the corpus this was
	 * designed against contains a 17 kB definition list, which is atomic purely because `dl` is a
	 * standard HTML list container. Treating atomicity as a preference that yields above a hard
	 * ceiling keeps that from becoming an unsplittable unit.
	 *
	 * @param targetUnitSize the size packing aims for when merging siblings
	 * @param maxUnitSize    hard ceiling above which even an atomic container is descended into
	 * @param minUnitSize    below this, a unit keeps accumulating even past the target, so that a
	 *                       five-word `&lt;NoteTitle&gt;` is never translated on its own
	 */
	public record Settings(int targetUnitSize, int maxUnitSize, int minUnitSize) {

		/**
		 * Validates that the three thresholds are ordered and positive.
		 */
		public Settings {
			if (minUnitSize < 0 || targetUnitSize <= 0 || maxUnitSize < targetUnitSize) {
				throw new IllegalArgumentException(
					"require 0 <= minUnitSize and 0 < targetUnitSize <= maxUnitSize, got "
						+ minUnitSize + "/" + targetUnitSize + "/" + maxUnitSize
				);
			}
		}

		/**
		 * Returns the defaults: a 32 kB target, a 48 kB ceiling and a 2 kB floor.
		 *
		 * @return the default settings
		 */
		@Nonnull
		public static Settings defaults() {
			return new Settings(32 * 1024, 48 * 1024, 2 * 1024);
		}

	}

	/**
	 * Creates a packer.
	 *
	 * @param vocabulary decides which containers are atomic
	 * @param settings   the size policy
	 */
	public UnitPacker(@Nonnull TagVocabulary vocabulary, @Nonnull Settings settings) {
		this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
	}

	/**
	 * Packs the whole tree into translation units covering every byte of the document.
	 *
	 * @param tree the tree to pack; must not be null
	 * @return units in document order
	 */
	@Nonnull
	public List<TranslationUnit> pack(@Nonnull ScopeTree tree) {
		Objects.requireNonNull(tree, "tree must not be null");
		final List<TranslationUnit> units = new ArrayList<>();
		packContainer(tree.getSource(), tree.getRoot(), List.of(tree.getRoot()), units);
		return units;
	}

	/**
	 * Packs one container's children, descending where a child is too large to send whole.
	 *
	 * @param source    the exact string the tree indexes
	 * @param container the node whose children are being packed
	 * @param ancestors the chain enclosing those children, outermost first
	 * @param units     accumulator
	 */
	private void packContainer(
		@Nonnull String source,
		@Nonnull ScopeNode container,
		@Nonnull List<ScopeNode> ancestors,
		@Nonnull List<TranslationUnit> units
	) {
		final List<List<ScopeNode>> items = coalesce(source, container.children());
		final List<ScopeNode> buffer = new ArrayList<>();
		int bufferLength = 0;
		for (final List<ScopeNode> item : items) {
			final int itemLength = lengthOf(item);
			if (item.size() == 1 && canDescend(item.get(0))) {
				flush(buffer, ancestors, units);
				bufferLength = 0;
				final ScopeNode child = item.get(0);
				final List<ScopeNode> deeper = new ArrayList<>(ancestors);
				deeper.add(child);
				packContainer(source, child, deeper, units);
				continue;
			}
			if (!buffer.isEmpty()
				&& bufferLength >= this.settings.minUnitSize()
				&& bufferLength + itemLength > this.settings.targetUnitSize()) {
				flush(buffer, ancestors, units);
				bufferLength = 0;
			}
			buffer.addAll(item);
			bufferLength += itemLength;
		}
		flush(buffer, ancestors, units);
	}

	/**
	 * Emits the buffered run as a unit and clears the buffer.
	 *
	 * @param buffer    the accumulated sibling run; may be empty, in which case nothing happens
	 * @param ancestors the chain enclosing the run
	 * @param units     accumulator
	 */
	private static void flush(
		@Nonnull List<ScopeNode> buffer,
		@Nonnull List<ScopeNode> ancestors,
		@Nonnull List<TranslationUnit> units
	) {
		if (buffer.isEmpty()) {
			return;
		}
		units.add(TranslationUnit.of(buffer, ancestors));
		buffer.clear();
	}

	/**
	 * Returns `true` when a child is too large to send whole and can be opened up.
	 *
	 * An atomic container yields only above the hard ceiling; everything else yields above the
	 * target. A leaf can never be descended into and is emitted whole however large it is.
	 *
	 * @param node the child under consideration
	 * @return `true` when the packer should recurse into this node instead of emitting it
	 */
	private boolean canDescend(@Nonnull ScopeNode node) {
		if (node.isLeaf()) {
			return false;
		}
		final boolean atomic = node.kind() == ScopeNode.Kind.TAG
			&& node.name() != null && this.vocabulary.isAtomic(node.name());
		final int threshold = atomic ? this.settings.maxUnitSize() : this.settings.targetUnitSize();
		return node.length() > threshold;
	}

	/**
	 * Groups a sibling list into the smallest pieces a cut is allowed to fall between.
	 *
	 * Everything that is not preceded by a block boundary is glued to the run before it, which is
	 * what keeps a paragraph together even though inline tags fragment it into several nodes.
	 *
	 * @param source   the exact string the tree indexes
	 * @param children the sibling list
	 * @return runs of siblings, in document order; each run is an indivisible packing item
	 */
	@Nonnull
	private static List<List<ScopeNode>> coalesce(
		@Nonnull String source,
		@Nonnull List<ScopeNode> children
	) {
		final List<List<ScopeNode>> items = new ArrayList<>();
		for (int i = 0; i < children.size(); i++) {
			if (i == 0 || endsBlock(source, children.get(i - 1))) {
				items.add(new ArrayList<>());
			}
			items.get(items.size() - 1).add(children.get(i));
		}
		return items;
	}

	/**
	 * Returns `true` when a cut is permitted immediately after the given node.
	 *
	 * @param source the exact string the tree indexes
	 * @param node   the node preceding the candidate cut point
	 * @return `true` when the node closes a block
	 */
	private static boolean endsBlock(@Nonnull String source, @Nonnull ScopeNode node) {
		return switch (node.kind()) {
			case TEXT -> hasBlankLineSuffix(source, node);
			case TAG -> node.blockLevel();
			case CODE, COMMENT, HEADING, HEADING_SECTION, DOCUMENT -> true;
		};
	}

	/**
	 * Returns `true` when the whitespace a prose node owns after its content spans a blank line -
	 * i.e. the node ends a paragraph rather than trailing off mid-sentence into an inline tag.
	 *
	 * A node holding nothing but whitespace - the separator between two block-level tags, say -
	 * has an empty content range, so all of its newlines sit in the *prefix* and the suffix scan
	 * would find none. Such a node is measured over its whole span instead; missing that case
	 * glues the tag after it onto the tag before it and quietly defeats the descent rule.
	 *
	 * @param source the exact string the tree indexes
	 * @param node   the prose node to inspect
	 * @return `true` when at least two newlines follow the node's content
	 */
	private static boolean hasBlankLineSuffix(@Nonnull String source, @Nonnull ScopeNode node) {
		final int from = node.contentEnd() > node.contentStart() ? node.contentEnd() : node.start();
		int newlines = 0;
		for (int i = from; i < node.end(); i++) {
			if (source.charAt(i) == '\n') {
				newlines++;
				if (newlines >= 2) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Sums the byte length of a sibling run.
	 *
	 * @param nodes the run
	 * @return total bytes covered
	 */
	private static int lengthOf(@Nonnull List<ScopeNode> nodes) {
		return nodes.get(nodes.size() - 1).end() - nodes.get(0).start();
	}

}
