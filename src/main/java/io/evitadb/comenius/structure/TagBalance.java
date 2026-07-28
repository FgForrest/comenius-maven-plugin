package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Matches opening and closing tag tokens and reports which occurrences fail to pair.
 *
 * Shared by {@link ScopeTreeBuilder}, which needs a balanced token stream before it can build a
 * tree, and by {@link TagVocabularyDeriver}, which needs to tell a component apart from a Java
 * generic in prose. The distinction the deriver depends on is the one this class makes visible:
 * a name that pairs *somewhere* is markup that may have a forgotten closer, whereas a name that
 * never pairs anywhere was never markup to begin with.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TagBalance {

	/**
	 * Outcome of matching one token stream.
	 *
	 * @param brokenIndices indices into the token list of occurrences that failed to pair
	 * @param pairedNames   names that matched at least once
	 */
	public record Result(@Nonnull Set<Integer> brokenIndices, @Nonnull Set<String> pairedNames) {

		/**
		 * Returns `true` when every tag occurrence paired.
		 *
		 * @return `true` when nothing is broken
		 */
		public boolean isBalanced() {
			return this.brokenIndices.isEmpty();
		}

	}

	/**
	 * Private constructor, this class is a pure function holder.
	 */
	private TagBalance() {
	}

	/**
	 * Matches tag tokens using innermost-first resolution.
	 *
	 * A closing tag pairs with the innermost still-open tag of the same name; any tags opened
	 * after that one are reported as never closed. A closing tag with no open counterpart is
	 * reported as never opened. Self-closing tags need no partner and are ignored.
	 *
	 * @param tokens scanner output in ascending offset order; must not be null
	 * @return the indices that failed to pair, plus the names that paired at least once
	 */
	@Nonnull
	public static Result match(@Nonnull List<MarkupToken> tokens) {
		Objects.requireNonNull(tokens, "tokens must not be null");
		final Set<Integer> broken = new HashSet<>();
		final Set<String> paired = new TreeSet<>();
		final Deque<Integer> open = new ArrayDeque<>();
		for (int i = 0; i < tokens.size(); i++) {
			final MarkupToken token = tokens.get(i);
			if (token.type() == MarkupToken.Type.TAG_OPEN) {
				open.push(i);
			} else if (token.type() == MarkupToken.Type.TAG_CLOSE) {
				if (!holdsName(tokens, open, token.name())) {
					broken.add(i);
					continue;
				}
				while (!Objects.equals(tokens.get(Objects.requireNonNull(open.peek())).name(), token.name())) {
					broken.add(open.pop());
				}
				open.pop();
				paired.add(Objects.requireNonNull(token.name()));
			}
		}
		broken.addAll(open);
		return new Result(Collections.unmodifiableSet(broken), Collections.unmodifiableSet(paired));
	}

	/**
	 * Returns `true` when the stack of open tags holds an entry with the given name.
	 *
	 * @param tokens all tokens
	 * @param open   indices of currently open tags, innermost first
	 * @param name   the name to look for
	 * @return `true` when a matching opener is still open
	 */
	private static boolean holdsName(
		@Nonnull List<MarkupToken> tokens,
		@Nonnull Deque<Integer> open,
		String name
	) {
		for (final Integer index : open) {
			if (Objects.equals(tokens.get(index).name(), name)) {
				return true;
			}
		}
		return false;
	}

}
