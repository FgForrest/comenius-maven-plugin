package io.evitadb.comenius.structure;

import io.evitadb.comenius.check.HeadingAnchorIndex;
import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A **read-only, zero-LLM-cost audit**, not a test with a fixed pass/fail outcome - it exists to
 * answer one question with a programmatic, repeatable check instead of eyeballing grep output:
 * *did this file's translation actually recover the content the tag-unaware splitter dropped?*
 * (see the incident this whole rewrite exists to fix).
 *
 * Comparing a translated document against the **current** EN source conflates two very different
 * things: real damage (content the translation dropped), and ordinary staleness (EN gained a
 * section after this file was translated, which is not a defect at all - see the 2026-07-28
 * correction in the project's plan file, a whole "12 files damaged" finding that turned out to be
 * 10 stale files and 1 real one). Every translated document's front matter carries the `commit`
 * it was translated from, so this audit compares against EN **at that commit** whenever the field
 * is present, falling back to current EN only when it is absent (e.g. auditing fresh probe output
 * that never went through the commit-stamping phase).
 *
 * Two independent uses:
 * - point it at the whole corpus (`comenius.audit.translated.dir` = `documentation/user/cs`) to
 *   find every file with real, commit-independent damage;
 * - point it at one retranslated file (`comenius.audit.translated.dir` = a `target/probe-*` or
 *   `target/retranslate-*` output, `comenius.audit.files` = that one file) as the after-check once
 *   a damaged file has been retranslated - heading *count* alone is not proof, so this also
 *   compares the heading *level sequence*, which position-matches even though the text itself is
 *   now in a different language.
 *
 * ```shell
 * cd /www/oss/comenius-maven-plugin
 * rtk mvn -o test -Dtest=TranslationAuditTest \
 *   -Dcomenius.audit.en.dir=/www/oss/evita/evitaDB-dev/documentation/user/en \
 *   -Dcomenius.audit.translated.dir=/www/oss/evita/evitaDB-dev/documentation/user/cs
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Translation audit against known-good source (manual, zero LLM cost)")
public class TranslationAuditTest {

	private static final String EN_DIR_PROPERTY = "comenius.audit.en.dir";
	private static final String TRANSLATED_DIR_PROPERTY = "comenius.audit.translated.dir";
	private static final String FILES_PROPERTY = "comenius.audit.files";

	@Test
	@EnabledIfSystemProperty(named = EN_DIR_PROPERTY, matches = ".+")
	@DisplayName("compares every translated document against its source for structural loss")
	public void shouldAuditTranslationsAgainstSource() throws IOException {
		final Path enRoot = Path.of(requireProperty(EN_DIR_PROPERTY));
		final Path translatedRoot = Path.of(requireProperty(TRANSLATED_DIR_PROPERTY));
		final GitService gitService = findGitService(enRoot);

		final Map<String, String> enCorpusAtHead = readCorpus(enRoot);
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		enCorpusAtHead.forEach(deriver::add);
		final TagVocabulary vocabulary = deriver.derive(
			Set.of("Table", "Thead", "Tbody", "Tr", "CodeTabs", "CodeTabsBlock", "SourceCodeTabs"),
			// dt/dd carry EvitaQL grammar notation (`argument:string!`) in the constraint-reference
			// pages, not prose - proven a false-positive source at corpus scale, see the 2026-07-28
			// audit. SourceClass/MDInclude are file paths, same reasoning as the probe.
			Set.of("SourceClass", "MDInclude", "dt", "dd"), false
		);

		final Map<String, MarkdownDocument> translatedCorpus = readRawCorpus(translatedRoot);
		final String filesProperty = System.getProperty(FILES_PROPERTY);
		final List<String> selected = filesProperty == null || filesProperty.isBlank()
			? new ArrayList<>(enCorpusAtHead.keySet())
			: List.of(filesProperty.split("\\s*,\\s*"));

		final List<String> clean = new ArrayList<>();
		final List<String> structuralLoss = new ArrayList<>();
		final List<String> caseDriftOnly = new ArrayList<>();
		final List<String> untranslatedOnly = new ArrayList<>();
		final List<String> missing = new ArrayList<>();
		final List<String> swallowedHeadings = new ArrayList<>();

		for (final String relative : selected) {
			if (!enCorpusAtHead.containsKey(relative)) {
				fail("no such document under " + enRoot + ": " + relative);
			}
			final MarkdownDocument translatedDoc = translatedCorpus.get(relative);
			if (translatedDoc == null) {
				missing.add(relative);
				System.out.println("MISSING: " + relative);
				continue;
			}
			final String translated = translatedDoc.getBodyContent();
			final String en = resolveComparisonBaseline(
				enRoot, relative, translatedDoc, gitService, enCorpusAtHead.get(relative)
			);

			final MarkupScanner scanner = new MarkupScanner(vocabulary);
			final List<MarkupToken> enTokens = scanner.scan(en);
			final List<MarkupToken> translatedTokens = scanner.scan(translated);

			final boolean balanced = TagBalance.match(translatedTokens).isBalanced();
			final String enLevels = headingLevelSequence(enTokens);
			final String translatedLevels = headingLevelSequence(translatedTokens);
			final boolean headingsMatch = enLevels.equals(translatedLevels);

			final List<TagCaseRepairer.Fix> caseFixes = TagCaseRepairer.find(vocabulary, en, translated);
			final List<UntranslatedContentChecker.Suspect> suspects = balanced
				? UntranslatedContentChecker.find(vocabulary, en, translated)
				: List.of();

			// Both sides are parsed by commonmark, so a "#" line inside a fenced code block is
			// treated identically in source and translation and cannot raise a false alarm. A
			// raw regex scan cannot do this - it reads shell comments like "# run in foreground"
			// as headings, which differs per document and produces phantom mismatches.
			final int enHeadingCount = HeadingAnchorIndex.fromDocument(
				new MarkdownDocument(en).getDocument()
			).size();
			final int translatedHeadingCount = HeadingAnchorIndex.fromDocument(
				new MarkdownDocument(translated).getDocument()
			).size();
			final boolean headingSwallowed = enHeadingCount != translatedHeadingCount;

			final boolean structurallySound = balanced && headingsMatch && !headingSwallowed;
			if (structurallySound && caseFixes.isEmpty() && suspects.isEmpty()) {
				clean.add(relative);
				continue;
			}

			System.out.println("--- " + relative + " ---");
			if (!balanced) {
				System.out.println("  UNBALANCED under the real vocabulary");
			}
			if (!headingsMatch) {
				System.out.println("  heading levels EN: " + enLevels);
				System.out.println("  heading levels  T: " + translatedLevels);
			}
			if (headingSwallowed) {
				System.out.println(
					"  HEADING SWALLOWED BY PRECEDING BLOCK: source exposes " + enHeadingCount
						+ " parsed headings, translation only " + translatedHeadingCount
						+ " (missing blank line before a heading)"
				);
			}
			if (!caseFixes.isEmpty()) {
				System.out.println("  " + caseFixes.size() + " tag-case fix(es) available");
			}
			if (!suspects.isEmpty()) {
				System.out.println("  " + suspects.size() + " untranslated-content suspect(s)");
			}

			if (headingSwallowed) {
				swallowedHeadings.add(relative);
			}
			if (!structurallySound) {
				structuralLoss.add(relative);
			} else if (!caseFixes.isEmpty() && suspects.isEmpty()) {
				caseDriftOnly.add(relative);
			} else if (caseFixes.isEmpty()) {
				untranslatedOnly.add(relative);
			}
		}

		System.out.println();
		System.out.println("=== SUMMARY ===");
		System.out.println("clean: " + clean.size() + " " + clean);
		System.out.println("structural loss (unbalanced or heading levels changed, vs EN at the "
			+ "file's own tracked commit): " + structuralLoss.size() + " " + structuralLoss);
		System.out.println("case-drift only: " + caseDriftOnly.size() + " " + caseDriftOnly);
		System.out.println("untranslated-content only: " + untranslatedOnly.size() + " " + untranslatedOnly);
		System.out.println("headings swallowed by a preceding block (subset already counted in structural "
			+ "loss above, listed separately since TagBalance cannot see it): " + swallowedHeadings.size()
			+ " " + swallowedHeadings);
		System.out.println("missing counterpart: " + missing.size() + " " + missing);
		System.out.println("total audited: " + selected.size());
	}

	/**
	 * Resolves the EN text to compare a translated document against: EN at the commit its front
	 * matter is pinned to when that commit is resolvable, otherwise EN at HEAD.
	 *
	 * @param enRoot         the EN corpus root
	 * @param relative       the document's path relative to both roots
	 * @param translatedDoc  the translated document, for its `commit` front matter field
	 * @param gitService     git access rooted at the repository containing {@code enRoot}, or null
	 *                       if the EN root is not inside a git repository
	 * @param enBodyAtHead   EN body content at HEAD, used as the fallback
	 * @return the EN body content to compare against
	 */
	@Nonnull
	private static String resolveComparisonBaseline(
		@Nonnull Path enRoot,
		@Nonnull String relative,
		@Nonnull MarkdownDocument translatedDoc,
		@Nullable GitService gitService,
		@Nonnull String enBodyAtHead
	) {
		if (gitService == null) {
			return enBodyAtHead;
		}
		final Optional<String> commit = translatedDoc.getProperty("commit")
			.map(value -> value.replaceAll("^'|'$", ""));
		if (commit.isEmpty()) {
			return enBodyAtHead;
		}
		try {
			final Optional<String> atCommit = gitService.getFileAtCommit(
				enRoot.resolve(relative), commit.get()
			);
			if (atCommit.isPresent()) {
				return new MarkdownDocument(atCommit.get()).getBodyContent();
			}
		} catch (final IOException ignored) {
			// falls through to the HEAD fallback below
		}
		return enBodyAtHead;
	}

	/**
	 * Finds the git repository root containing the given path and builds a {@link GitService} for
	 * it, or returns {@code null} when the path is not inside a git repository - in which case
	 * every comparison falls back to EN at HEAD.
	 *
	 * @param path any path inside the repository
	 * @return a GitService rooted at the repository root, or null
	 */
	@Nullable
	private static GitService findGitService(@Nonnull Path path) {
		Path current = path.toAbsolutePath().normalize();
		while (current != null) {
			if (Files.exists(current.resolve(".git"))) {
				return new GitService(current);
			}
			current = current.getParent();
		}
		return null;
	}

	/**
	 * Renders the ordered heading-level skeleton of a document - comparable across languages
	 * because it is positional, not textual.
	 *
	 * @param tokens scanner output
	 * @return a comparable sequence such as `"2 5 3 3"`
	 */
	@Nonnull
	private static String headingLevelSequence(@Nonnull List<MarkupToken> tokens) {
		final StringBuilder result = new StringBuilder();
		for (final MarkupToken token : tokens) {
			if (token.type() == MarkupToken.Type.HEADING) {
				result.append(token.level()).append(' ');
			}
		}
		return result.toString().trim();
	}

	@Nonnull
	private static String requireProperty(@Nonnull String name) {
		final String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			fail("-D" + name + " must be set");
		}
		return value;
	}

	@Nonnull
	private static Map<String, String> readCorpus(@Nonnull Path root) throws IOException {
		final Map<String, String> corpus = new TreeMap<>();
		readRawCorpus(root).forEach((relative, doc) -> corpus.put(relative, doc.getBodyContent()));
		return corpus;
	}

	@Nonnull
	private static Map<String, MarkdownDocument> readRawCorpus(@Nonnull Path root) throws IOException {
		final Map<String, MarkdownDocument> corpus = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> {
					final String text = path.toString();
					return !text.contains("/examples/") && !text.contains("/example/")
						&& !text.contains("/assets/");
				})
				.sorted()
				.forEach(path -> {
					try {
						corpus.put(
							root.relativize(path).toString(),
							new MarkdownDocument(
								new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
							)
						);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				});
		}
		return corpus;
	}

}
