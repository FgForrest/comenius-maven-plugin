package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

/**
 * A contiguous run of sibling nodes handed to the translator as one piece, together with the
 * chain of containers it sits inside.
 *
 * A unit is always a *sibling run*, never an arbitrary slice of the document. That is the whole
 * point: because siblings tile their parent's content range, a unit boundary can never fall
 * strictly inside a structural tag, and a translated unit can therefore be substituted back
 * without disturbing anything around it.
 *
 * The ancestor chain is carried but **not** included in the text. It is prompt context - "this
 * fragment sits inside `&lt;LS to="j"&gt;` then `&lt;Note type="info"&gt;`" - and is re-attached
 * in Java afterwards, so the model is never in a position to drop, rename or duplicate an
 * enclosing tag.
 *
 * @param nodes     the sibling run, in document order; never empty
 * @param ancestors the containers enclosing the run, outermost first; may be empty
 * @param start     inclusive offset of the first byte of the run
 * @param end       exclusive offset just past the last byte of the run
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record TranslationUnit(
	@Nonnull List<ScopeNode> nodes,
	@Nonnull List<ScopeNode> ancestors,
	int start,
	int end
) {

	/**
	 * Validates that the unit holds a non-empty run whose offsets agree with its nodes.
	 */
	public TranslationUnit {
		Objects.requireNonNull(nodes, "nodes must not be null");
		Objects.requireNonNull(ancestors, "ancestors must not be null");
		if (nodes.isEmpty()) {
			throw new IllegalArgumentException("a translation unit must hold at least one node");
		}
		if (start != nodes.get(0).start() || end != nodes.get(nodes.size() - 1).end()) {
			throw new IllegalArgumentException(
				"unit offsets [" + start + "," + end + ") disagree with its nodes ["
					+ nodes.get(0).start() + "," + nodes.get(nodes.size() - 1).end() + ")"
			);
		}
	}

	/**
	 * Creates a unit from a sibling run.
	 *
	 * @param nodes     the run, in document order; must not be empty
	 * @param ancestors the enclosing containers, outermost first
	 * @return the unit
	 */
	@Nonnull
	public static TranslationUnit of(
		@Nonnull List<ScopeNode> nodes,
		@Nonnull List<ScopeNode> ancestors
	) {
		return new TranslationUnit(
			List.copyOf(nodes), List.copyOf(ancestors),
			nodes.get(0).start(), nodes.get(nodes.size() - 1).end()
		);
	}

	/**
	 * Extracts the text of this unit from the source the tree was built over.
	 *
	 * @param source the exact string the tree indexes
	 * @return the unit's markup, delimiters included
	 */
	@Nonnull
	public String text(@Nonnull String source) {
		return source.substring(this.start, this.end);
	}

	/**
	 * Returns the number of bytes the unit covers.
	 *
	 * @return `end - start`
	 */
	public int length() {
		return this.end - this.start;
	}

	/**
	 * Returns this unit's text with leading and trailing whitespace-only padding removed.
	 *
	 * This is what actually gets sent to the model. A unit's own edge whitespace is frequently the
	 * only thing separating it from whatever the packer placed next, and the model does not
	 * reliably return it - it strips trailing whitespace from its answer as a matter of course.
	 * Rather than depend on that, the edges are never the model's problem: they are cut off here
	 * and reattached verbatim by {@link #wrapTranslation(String, String)}.
	 *
	 * @param source the exact string the tree indexes
	 * @return the unit's core content, with edges stripped
	 */
	@Nonnull
	public String core(@Nonnull String source) {
		return Edges.of(text(source)).core();
	}

	/**
	 * Re-wraps a translated core with this unit's original leading and trailing whitespace, copied
	 * verbatim from the source - never from the model's answer, which is stripped of its own edge
	 * whitespace first. This guarantees the separator between two spliced units survives
	 * regardless of what the model does at the edges of its response.
	 *
	 * @param source         the exact string the tree indexes
	 * @param translatedCore the model's answer for {@link #core(String)}
	 * @return the translated unit, with this unit's original edges restored
	 */
	@Nonnull
	public String wrapTranslation(@Nonnull String source, @Nonnull String translatedCore) {
		final Edges edges = Edges.of(text(source));
		return edges.leading() + translatedCore.strip() + edges.trailing();
	}

	/**
	 * A text split into its leading whitespace-only padding, its core, and its trailing padding.
	 *
	 * @param leading the leading whitespace-only run, possibly empty
	 * @param core    the text between the two padding runs
	 * @param trailing the trailing whitespace-only run, possibly empty
	 */
	private record Edges(@Nonnull String leading, @Nonnull String core, @Nonnull String trailing) {

		@Nonnull
		static Edges of(@Nonnull String text) {
			int start = 0;
			while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
				start++;
			}
			int end = text.length();
			while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
				end--;
			}
			return new Edges(text.substring(0, start), text.substring(start, end), text.substring(end));
		}

	}

	/**
	 * Returns `true` when the unit holds nothing a translator could act on - whitespace, code,
	 * or comments only. Such units are reconstructed verbatim and never sent anywhere.
	 *
	 * @return `true` when the unit has no translatable descendant
	 */
	public boolean isTranslatable() {
		for (final ScopeNode node : this.nodes) {
			if (hasTranslatableContent(node)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Renders the enclosing containers for use as prompt context, with attributes intact.
	 *
	 * @param source the exact string the tree indexes
	 * @return a description such as `&lt;LS to="j"&gt; &gt; &lt;Note type="info"&gt;`, or an
	 *         empty string when the unit sits at the top level
	 */
	@Nonnull
	public String describeContext(@Nonnull String source) {
		final StringBuilder result = new StringBuilder(64);
		for (final ScopeNode ancestor : this.ancestors) {
			if (ancestor.kind() == ScopeNode.Kind.DOCUMENT) {
				continue;
			}
			if (!result.isEmpty()) {
				result.append(" > ");
			}
			if (ancestor.kind() == ScopeNode.Kind.TAG) {
				result.append(source, ancestor.start(), ancestor.contentStart());
			} else if (ancestor.kind() == ScopeNode.Kind.HEADING_SECTION) {
				result.append("h").append(ancestor.headingLevel()).append(" section");
			}
		}
		return result.toString();
	}

	/**
	 * Builds the ancestor half of a content-addressed translation memory key.
	 *
	 * Attributes are deliberately excluded: `&lt;LS to="e,j"&gt;` and `&lt;LS to="j"&gt;` are the
	 * same translation context, and any difference that actually matters is already carried by
	 * the content hash. Including them would turn a harmless attribute edit into a cache miss for
	 * every node beneath it.
	 *
	 * @return a stable description of the chain by kind, name and heading level
	 */
	@Nonnull
	public String contextKey() {
		final StringBuilder result = new StringBuilder(64);
		for (final ScopeNode ancestor : this.ancestors) {
			if (ancestor.kind() == ScopeNode.Kind.DOCUMENT) {
				continue;
			}
			result.append('/').append(ancestor.kind());
			if (ancestor.name() != null) {
				result.append(':').append(ancestor.name());
			}
			if (ancestor.headingLevel() > 0) {
				result.append('#').append(ancestor.headingLevel());
			}
		}
		return result.toString();
	}

	/**
	 * Recursively determines whether a node or any of its descendants holds translatable prose.
	 *
	 * @param node the node to inspect
	 * @return `true` when something under this node would be sent to a translator
	 */
	private static boolean hasTranslatableContent(@Nonnull ScopeNode node) {
		if (node.isTranslatable()) {
			return true;
		}
		for (final ScopeNode child : node.children()) {
			if (hasTranslatableContent(child)) {
				return true;
			}
		}
		return false;
	}

}
