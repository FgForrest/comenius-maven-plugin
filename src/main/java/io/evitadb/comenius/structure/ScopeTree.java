package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A scope tree bound to the exact source string it was built from.
 *
 * Pairing the two is deliberate: every offset in the tree is meaningless without the string it
 * indexes, and silently mixing a tree with a different revision of a document would produce
 * plausible-looking garbage rather than an error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ScopeTree {

	private final String source;
	private final ScopeNode root;

	/**
	 * Creates a scope tree over the given source.
	 *
	 * @param source the exact string the offsets index; must not be null
	 * @param root   the root node; must span the whole source
	 */
	public ScopeTree(@Nonnull String source, @Nonnull ScopeNode root) {
		this.source = Objects.requireNonNull(source, "source must not be null");
		this.root = Objects.requireNonNull(root, "root must not be null");
	}

	/**
	 * Returns the source string the tree indexes.
	 *
	 * @return the original document text
	 */
	@Nonnull
	public String getSource() {
		return this.source;
	}

	/**
	 * Returns the root node.
	 *
	 * @return the document node
	 */
	@Nonnull
	public ScopeNode getRoot() {
		return this.root;
	}

	/**
	 * Verifies the tiling invariant over the whole tree.
	 *
	 * This is the assertion that a byte-exact identity round-trip *cannot* make on its own: a
	 * reconstructor that copies the gaps between sibling spans reproduces the input perfectly
	 * while leaving prose in those gaps unowned by any node - and therefore invisible to both
	 * the packer and the translation memory. Checking that children exactly cover their parent's
	 * content range is what rules that out.
	 *
	 * @throws IllegalStateException when any node's children fail to tile its content range
	 */
	public void validate() {
		if (this.root.start() != 0 || this.root.end() != this.source.length()) {
			throw new IllegalStateException(
				"root must span the whole source: root=[" + this.root.start() + "," + this.root.end() +
					") source length=" + this.source.length()
			);
		}
		validateNode(this.root);
	}

	/**
	 * Recursively checks that the children of the given node tile its content range.
	 *
	 * @param node the node to check
	 */
	private void validateNode(@Nonnull ScopeNode node) {
		final List<ScopeNode> children = node.children();
		if (!children.isEmpty()) {
			final ScopeNode first = children.get(0);
			if (first.start() != node.contentStart()) {
				throw new IllegalStateException(
					describe(node) + ": first child starts at " + first.start() +
						" but content starts at " + node.contentStart()
				);
			}
			for (int i = 0; i < children.size() - 1; i++) {
				final ScopeNode current = children.get(i);
				final ScopeNode next = children.get(i + 1);
				if (current.end() != next.start()) {
					throw new IllegalStateException(
						describe(node) + ": child " + i + " ends at " + current.end() +
							" but child " + (i + 1) + " starts at " + next.start() +
							" (gap or overlap of " + (next.start() - current.end()) + " chars)"
					);
				}
			}
			final ScopeNode last = children.get(children.size() - 1);
			if (last.end() != node.contentEnd()) {
				throw new IllegalStateException(
					describe(node) + ": last child ends at " + last.end() +
						" but content ends at " + node.contentEnd()
				);
			}
		}
		for (final ScopeNode child : children) {
			validateNode(child);
		}
	}

	/**
	 * Builds a short human-readable description of a node, used in invariant failure messages.
	 *
	 * @param node the node to describe
	 * @return a description including kind, name and the line the node starts on
	 */
	@Nonnull
	private String describe(@Nonnull ScopeNode node) {
		return node.kind() + (node.name() == null ? "" : "<" + node.name() + ">") +
			" at line " + lineOf(node.start());
	}

	/**
	 * Computes the 1-based line number of the given offset.
	 *
	 * @param offset offset into the source
	 * @return 1-based line number
	 */
	public int lineOf(int offset) {
		int line = 1;
		final int limit = Math.min(offset, this.source.length());
		for (int i = 0; i < limit; i++) {
			if (this.source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * Visits every node of the tree in document order, parents before children.
	 *
	 * @param visitor callback invoked for each node
	 */
	public void forEach(@Nonnull Consumer<ScopeNode> visitor) {
		Objects.requireNonNull(visitor, "visitor must not be null");
		final Deque<ScopeNode> stack = new ArrayDeque<>();
		stack.push(this.root);
		while (!stack.isEmpty()) {
			final ScopeNode node = stack.pop();
			visitor.accept(node);
			final List<ScopeNode> children = node.children();
			for (int i = children.size() - 1; i >= 0; i--) {
				stack.push(children.get(i));
			}
		}
	}

	/**
	 * Collects every node matching the given predicate, in document order.
	 *
	 * @param predicate the filter to apply
	 * @return matching nodes, ordered by their position in the document
	 */
	@Nonnull
	public List<ScopeNode> collect(@Nonnull Predicate<ScopeNode> predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		final List<ScopeNode> result = new ArrayList<>();
		collectInto(this.root, predicate, result);
		return result;
	}

	/**
	 * Depth-first, document-order collection helper.
	 *
	 * @param node      the node to inspect
	 * @param predicate the filter to apply
	 * @param result    accumulator
	 */
	private static void collectInto(
		@Nonnull ScopeNode node,
		@Nonnull Predicate<ScopeNode> predicate,
		@Nonnull List<ScopeNode> result
	) {
		if (predicate.test(node)) {
			result.add(node);
		}
		for (final ScopeNode child : node.children()) {
			collectInto(child, predicate, result);
		}
	}

}
