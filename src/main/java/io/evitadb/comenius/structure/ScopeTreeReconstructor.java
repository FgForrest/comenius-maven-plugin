package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a {@link ScopeTree} back to text, substituting the content of selected nodes.
 *
 * The renderer only ever does two things: copy a range of the original source, or emit a
 * replacement string for a node the caller nominated. It never copies the *gaps* between
 * sibling spans, because under the tiling invariant there are none. That is what makes the
 * identity round-trip - reconstructing with no replacements at all - a meaningful acceptance
 * gate rather than a tautology: if a byte of the document is not owned by some leaf, it simply
 * disappears, and the comparison fails loudly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ScopeTreeReconstructor {

	/**
	 * Private constructor, this class is a pure function holder.
	 */
	private ScopeTreeReconstructor() {
	}

	/**
	 * Creates an empty replacement map with the identity semantics this class requires.
	 *
	 * {@link ScopeNode} is a record whose components include its child list, so its
	 * {@link Object#hashCode()} is deep and two structurally identical nodes at different places
	 * in the document compare equal. Only identity comparison addresses a node unambiguously.
	 *
	 * @return a fresh identity-keyed replacement map
	 */
	@Nonnull
	public static Map<ScopeNode, String> newReplacementMap() {
		return new IdentityHashMap<>();
	}

	/**
	 * Renders the tree with no replacements. The result must be byte-identical to the source.
	 *
	 * @param tree the tree to render; must not be null
	 * @return the reconstructed document
	 */
	@Nonnull
	public static String reconstruct(@Nonnull ScopeTree tree) {
		return reconstruct(tree, new IdentityHashMap<>());
	}

	/**
	 * Renders the tree, substituting the content range of every nominated node.
	 *
	 * A node's delimiters are always taken from the source, never from the replacement, so a
	 * translated paragraph cannot disturb the tag that encloses it or the `###` that introduces
	 * it. When a nominated node has children, the replacement covers the whole content range and
	 * the children are not rendered.
	 *
	 * @param tree         the tree to render; must not be null
	 * @param replacements new content keyed by node identity; use {@link #newReplacementMap()}
	 * @return the reconstructed document
	 * @throws IllegalArgumentException when a replacement key does not belong to the tree, which
	 *                                  would otherwise be silently ignored
	 */
	@Nonnull
	public static String reconstruct(
		@Nonnull ScopeTree tree,
		@Nonnull Map<ScopeNode, String> replacements
	) {
		Objects.requireNonNull(tree, "tree must not be null");
		Objects.requireNonNull(replacements, "replacements must not be null");
		final String source = tree.getSource();
		final StringBuilder result = new StringBuilder(source.length() + 64);
		final int[] applied = new int[1];
		render(source, tree.getRoot(), replacements, result, applied);
		if (applied[0] != replacements.size()) {
			throw new IllegalArgumentException(
				"replacement map holds " + replacements.size() + " entries but only " + applied[0] +
					" matched a node of this tree - the remaining keys belong to a different tree"
			);
		}
		return result.toString();
	}

	/**
	 * Renders the tree, substituting whole translation units.
	 *
	 * Unlike {@link #reconstruct(ScopeTree, Map)}, which replaces a node's *content* and keeps its
	 * delimiters, a unit replacement covers the run's full span - the markup inside a unit is what
	 * the translator saw and returned, so it comes back whole. The containers a unit sits inside
	 * are never part of it and are still taken from the source.
	 *
	 * @param tree         the tree to render; must not be null
	 * @param replacements translated text keyed by unit
	 * @return the reconstructed document
	 * @throws IllegalArgumentException when a unit does not align with a sibling run of this tree
	 */
	@Nonnull
	public static String reconstructUnits(
		@Nonnull ScopeTree tree,
		@Nonnull Map<TranslationUnit, String> replacements
	) {
		Objects.requireNonNull(tree, "tree must not be null");
		Objects.requireNonNull(replacements, "replacements must not be null");
		// keyed by node identity, not by offset: a container and its first child start at the very
		// same offset, so an offset key would hand a deeply nested unit to the level above it
		final Map<ScopeNode, TranslationUnit> byFirstNode = new IdentityHashMap<>();
		for (final TranslationUnit unit : replacements.keySet()) {
			final TranslationUnit clash = byFirstNode.put(unit.nodes().get(0), unit);
			if (clash != null) {
				throw new IllegalArgumentException(
					"two units both begin at the node at offset " + unit.start()
						+ " - replacements must not overlap"
				);
			}
		}
		final String source = tree.getSource();
		final StringBuilder result = new StringBuilder(source.length() + 64);
		renderNode(source, tree.getRoot(), byFirstNode, replacements, result);
		return result.toString();
	}

	/**
	 * Renders one node under unit-replacement semantics.
	 *
	 * @param source       the exact string the tree indexes
	 * @param node         the node to render
	 * @param byFirstNode  units indexed by the identity of the node they begin at
	 * @param replacements translated text keyed by unit
	 * @param out          accumulator
	 */
	private static void renderNode(
		@Nonnull String source,
		@Nonnull ScopeNode node,
		@Nonnull Map<ScopeNode, TranslationUnit> byFirstNode,
		@Nonnull Map<TranslationUnit, String> replacements,
		@Nonnull StringBuilder out
	) {
		out.append(source, node.start(), node.contentStart());
		if (node.isLeaf()) {
			out.append(source, node.contentStart(), node.contentEnd());
		} else {
			final List<ScopeNode> children = node.children();
			int index = 0;
			while (index < children.size()) {
				final ScopeNode child = children.get(index);
				final TranslationUnit unit = byFirstNode.get(child);
				if (unit == null) {
					renderNode(source, child, byFirstNode, replacements, out);
					index++;
					continue;
				}
				out.append(replacements.get(unit));
				final ScopeNode last = unit.nodes().get(unit.nodes().size() - 1);
				int cursor = index;
				while (cursor < children.size() && children.get(cursor) != last) {
					cursor++;
				}
				if (cursor >= children.size()) {
					throw new IllegalArgumentException(
						"unit [" + unit.start() + "," + unit.end() + ") does not align with a sibling "
							+ "run of this tree - it belongs to a different tree or a different packing"
					);
				}
				index = cursor + 1;
			}
		}
		out.append(source, node.contentEnd(), node.end());
	}

	/**
	 * Recursively renders one node.
	 *
	 * @param source       the exact string the tree indexes
	 * @param node         the node to render
	 * @param replacements new content keyed by node identity
	 * @param out          accumulator
	 * @param applied      single-element counter of replacements actually used
	 */
	private static void render(
		@Nonnull String source,
		@Nonnull ScopeNode node,
		@Nonnull Map<ScopeNode, String> replacements,
		@Nonnull StringBuilder out,
		@Nonnull int[] applied
	) {
		out.append(source, node.start(), node.contentStart());
		final String replacement = replacements.get(node);
		if (replacement != null) {
			out.append(replacement);
			applied[0]++;
		} else if (node.isLeaf()) {
			out.append(source, node.contentStart(), node.contentEnd());
		} else {
			for (final ScopeNode child : node.children()) {
				render(source, child, replacements, out, applied);
			}
		}
		out.append(source, node.contentEnd(), node.end());
	}

}
