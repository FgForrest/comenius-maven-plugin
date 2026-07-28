package io.evitadb.comenius.structure;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The set of tag names the scanner treats as markup, and how each of them behaves.
 *
 * The plugin deliberately bakes in **no project-specific tag name**. A vocabulary is either
 * derived from the corpus by {@link TagVocabularyDeriver} or configured explicitly; anything
 * outside it is literal text, which is what keeps `List&lt;SealedEntity&gt;` in prose from being
 * mistaken for markup.
 *
 * All matching is **case-sensitive**, and that is load-bearing rather than incidental: a corpus
 * may legitimately use lowercase `&lt;dl&gt;`/`&lt;dt&gt;` as literal HTML while capitalized
 * `&lt;Table&gt;`/`&lt;Tr&gt;` are components with entirely different splitting rules.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class TagVocabulary {

	/**
	 * Standard lowercase HTML block containers that must never be split across translation
	 * units. These are HTML semantics rather than project semantics, so they are safe to
	 * hard-code; capitalized look-alikes are components and must be configured explicitly.
	 */
	public static final Set<String> DEFAULT_ATOMIC_TAGS = Set.of(
		"table", "thead", "tbody", "tfoot", "tr", "ul", "ol", "dl"
	);

	private final Set<String> structural;
	private final Set<String> atomic;
	private final Set<String> opaque;
	private final boolean discovering;
	private final boolean lenient;

	/**
	 * Creates a vocabulary with an explicit set of names.
	 *
	 * @param structural names treated as markup; anything else is literal text
	 * @param atomic     names whose content must never be split across translation units
	 * @param opaque     names whose content is preserved verbatim instead of translated
	 * @param lenient    when `true`, an unbalanced occurrence degrades to literal text instead
	 *                   of failing the build
	 */
	private TagVocabulary(
		@Nonnull Set<String> structural,
		@Nonnull Set<String> atomic,
		@Nonnull Set<String> opaque,
		boolean discovering,
		boolean lenient
	) {
		this.structural = Collections.unmodifiableSet(new LinkedHashSet<>(structural));
		this.atomic = Collections.unmodifiableSet(new LinkedHashSet<>(atomic));
		this.opaque = Collections.unmodifiableSet(new LinkedHashSet<>(opaque));
		this.discovering = discovering;
		this.lenient = lenient;
	}

	/**
	 * Creates a vocabulary that accepts every syntactically valid tag.
	 *
	 * This mode exists to break the chicken-and-egg between scanning and derivation: the
	 * deriver needs a scan to learn which names exist, and the scan needs a vocabulary to know
	 * which names are markup. Discovery scans are never used to build a tree for translation.
	 *
	 * @return a permissive vocabulary
	 */
	@Nonnull
	public static TagVocabulary discovering() {
		return new TagVocabulary(Set.of(), DEFAULT_ATOMIC_TAGS, Set.of(), true, true);
	}

	/**
	 * Creates an explicitly configured vocabulary.
	 *
	 * @param structural names treated as markup; must not be null
	 * @param atomic     names whose content must never be split; merged with
	 *                   {@link #DEFAULT_ATOMIC_TAGS}
	 * @param opaque     names whose content is preserved verbatim; must not be null
	 * @param lenient    when `true`, unbalanced occurrences degrade to literal text
	 * @return the configured vocabulary
	 */
	@Nonnull
	public static TagVocabulary of(
		@Nonnull Set<String> structural,
		@Nonnull Set<String> atomic,
		@Nonnull Set<String> opaque,
		boolean lenient
	) {
		Objects.requireNonNull(structural, "structural must not be null");
		Objects.requireNonNull(atomic, "atomic must not be null");
		Objects.requireNonNull(opaque, "opaque must not be null");
		final Set<String> allAtomic = new LinkedHashSet<>(DEFAULT_ATOMIC_TAGS);
		allAtomic.addAll(atomic);
		return new TagVocabulary(structural, allAtomic, opaque, false, lenient);
	}

	/**
	 * Returns `true` when occurrences of the given name are markup rather than literal text.
	 *
	 * @param name the tag name, matched case-sensitively
	 * @return `true` when the name belongs to the vocabulary
	 */
	public boolean isStructural(@Nonnull String name) {
		return this.discovering || this.structural.contains(name);
	}

	/**
	 * Returns `true` when the content of the given tag must never be split across units.
	 *
	 * @param name the tag name, matched case-sensitively
	 * @return `true` for atomic containers
	 */
	public boolean isAtomic(@Nonnull String name) {
		return this.atomic.contains(name);
	}

	/**
	 * Returns `true` when the content of the given tag is preserved verbatim rather than
	 * translated - a tag whose body is a file path, for instance.
	 *
	 * @param name the tag name, matched case-sensitively
	 * @return `true` for opaque containers
	 */
	public boolean isOpaque(@Nonnull String name) {
		return this.opaque.contains(name);
	}

	/**
	 * Returns `true` when an unbalanced occurrence should degrade to literal text instead of
	 * failing the build.
	 *
	 * @return the lenient flag
	 */
	public boolean isLenient() {
		return this.lenient;
	}

	/**
	 * Returns `true` when this vocabulary accepts any syntactically valid tag.
	 *
	 * @return the discovery flag
	 */
	public boolean isDiscovering() {
		return this.discovering;
	}

	/**
	 * Returns the structural names, in insertion order.
	 *
	 * @return an unmodifiable set of structural tag names
	 */
	@Nonnull
	public Set<String> getStructuralTags() {
		return this.structural;
	}

	/**
	 * Returns the atomic names, in insertion order.
	 *
	 * @return an unmodifiable set of atomic tag names
	 */
	@Nonnull
	public Set<String> getAtomicTags() {
		return this.atomic;
	}

	/**
	 * Returns the opaque names, in insertion order.
	 *
	 * @return an unmodifiable set of opaque tag names
	 */
	@Nonnull
	public Set<String> getOpaqueTags() {
		return this.opaque;
	}

	/**
	 * Returns a copy of this vocabulary with the lenient flag overridden.
	 *
	 * @param newLenient the desired lenient flag
	 * @return a vocabulary identical to this one except for the lenient flag
	 */
	@Nonnull
	public TagVocabulary withLenient(boolean newLenient) {
		return new TagVocabulary(this.structural, this.atomic, this.opaque, this.discovering, newLenient);
	}

}
