package io.evitadb.comenius.structure;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.evitadb.comenius.llm.ChatModelFactory;
import io.evitadb.comenius.llm.LlmClient;
import io.evitadb.comenius.llm.PromptLoader;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A **manual probe**, not a test. It spends real money and is skipped unless explicitly asked for.
 *
 * Everything else in this package can be verified for free, and is. What no amount of structural
 * testing can answer is whether a 32 kB tag-scoped unit actually produces good Czech - so this
 * runs a handful of real documents through the new packer and the live model, writes the output
 * somewhere harmless, and fails on any structural regression: tag sequence, inline tag multiset,
 * or blank line count. That last one exists because a blank line carries no token at all - a
 * model that silently drops one is invisible to a tag-only comparison.
 *
 * It deliberately does **not** touch the production translation path, the real `documentation/user/cs`
 * tree, or any front matter. Output goes to a scratch directory for reading and diffing.
 *
 * ```shell
 * export OPENAI_API_KEY=...
 * cd /www/oss/comenius-maven-plugin
 * rtk mvn -o test -Dtest=ScopeTreeTranslationProbe \
 *   -Dcomenius.corpus.dir=/www/oss/evita/evitaDB-dev/documentation/user/en \
 *   -Dcomenius.probe.files=use/api/query-data.md,operate/tls.md
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Scope tree translation probe (manual, spends money)")
public class ScopeTreeTranslationProbe {

	private static final String CORPUS_PROPERTY = "comenius.corpus.dir";
	private static final String FILES_PROPERTY = "comenius.probe.files";
	private static final String OUTPUT_PROPERTY = "comenius.probe.out";
	private static final String MODEL_PROPERTY = "comenius.probe.model";
	private static final String LOCALE_PROPERTY = "comenius.probe.locale";
	private static final String CUSTOM_INSTRUCTIONS_PROPERTY = "comenius.probe.customInstructions";
	private static final String TEMPERATURE_PROPERTY = "comenius.probe.temperature";

	/**
	 * Above this many units the probe refuses to run without an explicit override, so that a
	 * mistyped file list cannot quietly turn into a large bill. The incident this work exists to
	 * fix began with exactly that kind of overrun.
	 */
	private static final int DEFAULT_UNIT_BUDGET = 40;

	@Test
	@EnabledIfSystemProperty(named = FILES_PROPERTY, matches = ".+")
	@DisplayName("translates real documents through the packer and fails on structural regression")
	public void shouldTranslateSelectedDocumentsWhenProbeIsExplicitlyRequested() throws IOException {
		final Path corpusRoot = Path.of(requireProperty(CORPUS_PROPERTY));
		final Path outputRoot = Path.of(System.getProperty(OUTPUT_PROPERTY, "target/probe"));
		final String localeTag = System.getProperty(LOCALE_PROPERTY, "cs");
		final Locale locale = Locale.forLanguageTag(localeTag);
		final int unitBudget = Integer.getInteger("comenius.probe.unitBudget", DEFAULT_UNIT_BUDGET);

		final boolean dryRun = Boolean.getBoolean("comenius.probe.dryRun");

		// derivation must see the whole corpus: a tag means the same thing everywhere or nothing
		final Map<String, String> corpus = readCorpus(corpusRoot);
		final TagVocabularyDeriver deriver = new TagVocabularyDeriver();
		corpus.forEach(deriver::add);
		final TagVocabulary vocabulary = deriver.derive(
			Set.of("Table", "Thead", "Tbody", "Tr", "CodeTabs", "CodeTabsBlock", "SourceCodeTabs"),
			// a file path or resource path is never a candidate for the untranslated-content
			// check either - it is supposed to echo, same as code
			Set.of("SourceClass", "MDInclude"), false
		);

		final List<String> selected = List.of(requireProperty(FILES_PROPERTY).split("\\s*,\\s*"));
		final ScopeTreeBuilder builder = new ScopeTreeBuilder(vocabulary);
		// a smaller target is worth probing on purpose: at the production 32 kB most of these
		// documents pack into a single unit, which tests translation quality but says nothing
		// about whether unit *boundaries* hold together
		final int targetSize = Integer.getInteger(
			"comenius.probe.targetSize", UnitPacker.Settings.defaults().targetUnitSize()
		);
		final UnitPacker packer = new UnitPacker(vocabulary, new UnitPacker.Settings(
			targetSize, Math.max(targetSize + targetSize / 2, 4096), Math.min(2048, targetSize / 4)
		));
		final String customInstructions = System.getProperty(CUSTOM_INSTRUCTIONS_PROPERTY, "");

		// ---- plan the work and show the bill before spending anything -----------------------
		final Map<String, ScopeTree> trees = new LinkedHashMap<>();
		final Map<String, List<TranslationUnit>> plan = new LinkedHashMap<>();
		int plannedUnits = 0;
		int plannedBytes = 0;
		for (final String relative : selected) {
			final String body = corpus.get(relative);
			if (body == null) {
				fail("no such document under " + corpusRoot + ": " + relative);
			}
			final ScopeTree tree = builder.build(body, relative);
			final List<TranslationUnit> units = packer.pack(tree).stream()
				.filter(TranslationUnit::isTranslatable).toList();
			trees.put(relative, tree);
			plan.put(relative, units);
			plannedUnits += units.size();
			for (final TranslationUnit unit : units) {
				plannedBytes += unit.length();
			}
		}
		System.out.printf(
			"[probe] %d document(s), %d translatable unit(s), %d bytes -> %s%n",
			selected.size(), plannedUnits, plannedBytes, outputRoot.toAbsolutePath()
		);
		if (plannedUnits > unitBudget) {
			fail(
				plannedUnits + " units exceeds the budget of " + unitBudget
					+ " - narrow the file list, or raise -Dcomenius.probe.unitBudget deliberately"
			);
		}
		if (dryRun) {
			for (final Map.Entry<String, List<TranslationUnit>> entry : plan.entrySet()) {
				final ScopeTree tree = trees.get(entry.getKey());
				System.out.println("[probe] " + entry.getKey() + ":");
				int position = 0;
				for (final TranslationUnit unit : entry.getValue()) {
					position++;
					final String context = unit.describeContext(tree.getSource());
					System.out.printf(
						"           unit %2d  %6d bytes  line %-5d %s%n",
						position, unit.length(), tree.lineOf(unit.start()),
						context.isEmpty() ? "(top level)" : context
					);
				}
			}
			System.out.println("[probe] dry run - nothing was sent, nothing was spent");
			return;
		}

		final String apiKey = System.getenv("OPENAI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			fail("OPENAI_API_KEY is not set in the environment - export it before running the probe");
		}

		// ---- translate ----------------------------------------------------------------------
		final ChatModel model = ChatModelFactory.create(
			ChatModelFactory.PROVIDER_OPENAI,
			"https://api.openai.com/v1",
			apiKey,
			System.getProperty(MODEL_PROPERTY, "gpt-4.1"),
			readTemperature()
		);
		final LlmClient client = new LlmClient(model);
		final PromptLoader prompts = new PromptLoader();
		final List<String> problems = new ArrayList<>();
		Files.createDirectories(outputRoot);

		for (final Map.Entry<String, List<TranslationUnit>> entry : plan.entrySet()) {
			final String relative = entry.getKey();
			final ScopeTree tree = trees.get(relative);
			final String source = tree.getSource();
			final Map<TranslationUnit, String> translated = new HashMap<>();
			int index = 0;
			for (final TranslationUnit unit : entry.getValue()) {
				index++;
				final String context = unit.describeContext(source);
				System.out.printf(
					"[probe] %s unit %d/%d (%d bytes) line %d %s%n",
					relative, index, entry.getValue().size(), unit.length(),
					tree.lineOf(unit.start()), context.isEmpty() ? "" : "in " + context
				);
				final String result = translate(
					client, prompts, unit, source, locale, localeTag, context, customInstructions
				);
				// case must be repaired first: a case-drifted tag is invisible to the real
				// vocabulary (it is not a mismatch, it is simply not recognised as a tag at all),
				// which would otherwise desynchronize both checks that run after it
				final String caseRepaired = repairTagCase(vocabulary, unit.text(source), result);
				final String fixed = autofixUntranslatedContent(
					client, prompts, vocabulary, unit.text(source), caseRepaired, locale, localeTag
				);
				translated.put(unit, fixed);
				problems.addAll(compareStructure(vocabulary, relative, index, unit.text(source), fixed));
			}

			final String body = ScopeTreeReconstructor.reconstructUnits(tree, translated);
			final Path target = outputRoot.resolve(relative);
			Files.createDirectories(target.getParent());
			// front matter is left in the source language on purpose - translating it is a
			// separate, already-working path and would only add noise to what is being judged here
			final String frontMatter = new MarkdownDocument(
				new String(Files.readAllBytes(corpusRoot.resolve(relative)), StandardCharsets.UTF_8)
			).serializeFrontMatter();
			Files.write(target, (frontMatter + body).getBytes(StandardCharsets.UTF_8));

			// the same gate the source had to pass: the translation must itself be parseable
			try {
				new ScopeTreeBuilder(vocabulary).build(body, relative + " (translated)");
			} catch (UnbalancedMarkupException exception) {
				problems.add(relative + ": the translated document does not parse - "
					+ exception.getMessage().lines().findFirst().orElse(""));
			}
			System.out.println("[probe] wrote " + target);
		}

		System.out.println();
		if (problems.isEmpty()) {
			System.out.println("[probe] structure survived intact in every unit");
			System.out.println("[probe] read the output under " + outputRoot.toAbsolutePath());
		} else {
			System.out.println("[probe] " + problems.size() + " structural problem(s):");
			problems.forEach(problem -> System.out.println("  " + problem));
			System.out.println("[probe] read the output under " + outputRoot.toAbsolutePath());
			// this check has already caught a real defect (block structure changed) that a plain
			// log line let through as a passing test - a probe that only reports is not a gate
			fail(problems.size() + " structural problem(s) - see stdout above");
		}
	}

	// ---------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------

	/**
	 * Sends one unit to the model and returns the translated fragment.
	 *
	 * @param client              the LLM client
	 * @param prompts             the template loader
	 * @param unit                the unit to translate
	 * @param source              the document the unit indexes
	 * @param locale              the target locale
	 * @param localeTag           the target locale tag
	 * @param context             the rendered ancestor chain
	 * @param customInstructions  extra instructions prepended to the user prompt, or empty
	 * @return the model's reply, with any stray code fence stripped
	 */
	@Nonnull
	private static String translate(
		@Nonnull LlmClient client,
		@Nonnull PromptLoader prompts,
		@Nonnull TranslationUnit unit,
		@Nonnull String source,
		@Nonnull Locale locale,
		@Nonnull String localeTag,
		@Nonnull String context,
		@Nonnull String customInstructions
	) {
		final String system = prompts.loadAndInterpolate("translate-unit-system.txt", Map.of(
			"locale", locale.getDisplayLanguage(Locale.ENGLISH),
			"localeTag", localeTag
		));
		final String user = prompts.loadAndInterpolate("translate-unit-user.txt", Map.of(
			"customInstructions", customInstructions,
			"context", context.isEmpty() ? "the top level of the document" : context,
			// only the trimmed core is ever sent - the unit's own edge whitespace is not the
			// model's problem, see TranslationUnit.core()
			"sourceContent", unit.core(source)
		));
		final List<ChatMessage> messages = List.of(SystemMessage.from(system), UserMessage.from(user));
		final ChatResponse response = client.chat(messages);
		System.out.printf(
			"[probe]   finishReason=%s tokens=%s%n",
			response.metadata().finishReason(), response.metadata().tokenUsage()
		);
		final String translatedCore = stripFences(response.aiMessage().text());
		return unit.wrapTranslation(source, translatedCore);
	}

	/**
	 * Restores tag-name casing that drifted during translation, with no model call involved.
	 *
	 * The correct casing is not a guess: it is already known from the unit's own source, which the
	 * model never touches. A prompt instruction to preserve case was measured to be probabilistic
	 * rather than a fix (0 lowercase closes on one run, 4 on the next, same document, same
	 * instruction) - so this repair is purely mechanical, and runs before every other check because
	 * a case-drifted tag is invisible to the real vocabulary rather than merely mismatched.
	 *
	 * @param vocabulary the corpus-derived vocabulary, consulted only to veto ambiguous folds
	 * @param source     the unit's original text
	 * @param translated the unit's translated text, edges already restored
	 * @return the translated text with every fixable case drift corrected
	 */
	@Nonnull
	private static String repairTagCase(
		@Nonnull TagVocabulary vocabulary, @Nonnull String source, @Nonnull String translated
	) {
		final List<TagCaseRepairer.Fix> fixes = TagCaseRepairer.find(vocabulary, source, translated);
		if (fixes.isEmpty()) {
			return translated;
		}
		System.out.printf("[probe]   %d tag-case fix(es)%n", fixes.size());
		for (final TagCaseRepairer.Fix fix : fixes) {
			System.out.printf("[probe]     <%s> -> <%s>%n", fix.from(), fix.to());
		}
		return TagCaseRepairer.repair(vocabulary, source, translated);
	}

	/**
	 * Detects untranslated leaf content and repairs it with small, targeted follow-up requests.
	 *
	 * A structural round-trip cannot see this class of defect - a leaf tag's content can be
	 * perfectly well-formed markup and still just be the source-language text, untouched. Fixing
	 * it is cheap because only the suspect phrase itself is sent back to the model, not the whole
	 * unit; a unit with no suspects costs nothing beyond the detection scan.
	 *
	 * A dedicated, single-phrase request is a stronger signal than the heuristic that flagged it:
	 * if the model, asked to translate *only* this phrase with no surrounding distraction, still
	 * returns it unchanged, that is the model confirming the phrase does not need translation
	 * (a loanword, a proper noun), not a repeat of the same failure. Such a suspect is accepted,
	 * not re-flagged - re-scanning the whole fragment again would just rediscover the identical
	 * text and report a false positive as if autofix had failed.
	 *
	 * @param client     the LLM client
	 * @param prompts    the template loader
	 * @param vocabulary the vocabulary to scan with
	 * @param source     the unit's original text
	 * @param translated the unit's translated text, edges already restored
	 * @param locale     the target locale
	 * @param localeTag  the target locale tag
	 * @return the translated text with every fixable suspect replaced
	 */
	@Nonnull
	private static String autofixUntranslatedContent(
		@Nonnull LlmClient client,
		@Nonnull PromptLoader prompts,
		@Nonnull TagVocabulary vocabulary,
		@Nonnull String source,
		@Nonnull String translated,
		@Nonnull Locale locale,
		@Nonnull String localeTag
	) {
		final List<UntranslatedContentChecker.Suspect> suspects =
			UntranslatedContentChecker.find(vocabulary, source, translated);
		if (suspects.isEmpty()) {
			return translated;
		}
		System.out.printf(
			"[probe]   %d untranslated-content suspect(s), attempting autofix%n", suspects.size()
		);
		// applied back to front so that earlier offsets stay valid as later ones are spliced in
		final List<UntranslatedContentChecker.Suspect> sorted = new ArrayList<>(suspects);
		sorted.sort(Comparator.comparingInt(UntranslatedContentChecker.Suspect::start).reversed());
		final StringBuilder fixed = new StringBuilder(translated);
		for (final UntranslatedContentChecker.Suspect suspect : sorted) {
			final String fix = translatePhrase(client, prompts, suspect.text(), locale, localeTag);
			if (fix.equalsIgnoreCase(suspect.text().strip())) {
				System.out.printf(
					"[probe]     '%s' -> unchanged (model confirms no translation needed)%n", suspect.text()
				);
				continue;
			}
			System.out.printf("[probe]     '%s' -> '%s'%n", suspect.text(), fix);
			fixed.replace(suspect.start(), suspect.end(), fix);
		}
		return fixed.toString();
	}

	/**
	 * Translates a single short phrase in isolation - the follow-up request an autofix sends.
	 *
	 * @param client    the LLM client
	 * @param prompts   the template loader
	 * @param phrase    the phrase to translate
	 * @param locale    the target locale
	 * @param localeTag the target locale tag
	 * @return the model's answer, whitespace-trimmed
	 */
	@Nonnull
	private static String translatePhrase(
		@Nonnull LlmClient client,
		@Nonnull PromptLoader prompts,
		@Nonnull String phrase,
		@Nonnull Locale locale,
		@Nonnull String localeTag
	) {
		final String system = prompts.loadAndInterpolate("translate-phrase-system.txt", Map.of(
			"locale", locale.getDisplayLanguage(Locale.ENGLISH),
			"localeTag", localeTag
		));
		final String user = prompts.loadAndInterpolate("translate-phrase-user.txt", Map.of("phrase", phrase));
		final List<ChatMessage> messages = List.of(SystemMessage.from(system), UserMessage.from(user));
		final ChatResponse response = client.chat(messages);
		return stripFences(response.aiMessage().text()).strip();
	}

	/**
	 * Removes a code fence the model may have wrapped its answer in, despite being told not to.
	 *
	 * @param text the model's reply
	 * @return the reply without an enclosing fence
	 */
	@Nonnull
	private static String stripFences(@Nonnull String text) {
		final String trimmed = text.strip();
		if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
			return text;
		}
		final int firstNewline = trimmed.indexOf('\n');
		if (firstNewline < 0) {
			return text;
		}
		return trimmed.substring(firstNewline + 1, trimmed.length() - 3);
	}

	/**
	 * Compares the markup of a source unit against its translation.
	 *
	 * Block-level tags and headings are compared as an **ordered sequence**, because their order
	 * is document structure. Inline tags are compared as a **multiset**, because Czech word order
	 * legitimately moves them within a sentence and demanding sequence equality there would
	 * report a fault on a correct translation.
	 *
	 * @param vocabulary the vocabulary to scan with
	 * @param document   the document being probed, for messages
	 * @param index      the unit's 1-based index, for messages
	 * @param before     the source fragment
	 * @param after      the translated fragment
	 * @return the problems found, empty when the structure survived
	 */
	@Nonnull
	private static List<String> compareStructure(
		@Nonnull TagVocabulary vocabulary,
		@Nonnull String document,
		int index,
		@Nonnull String before,
		@Nonnull String after
	) {
		final List<String> problems = new ArrayList<>();
		final MarkupScanner scanner = new MarkupScanner(vocabulary);
		final List<MarkupToken> sourceTokens = scanner.scan(before);
		final List<MarkupToken> targetTokens = scanner.scan(after);

		final String sourceBlocks = describeBlocks(sourceTokens);
		final String targetBlocks = describeBlocks(targetTokens);
		if (!sourceBlocks.equals(targetBlocks)) {
			problems.add(document + " unit " + index + ": block structure changed\n      was: "
				+ sourceBlocks + "\n      now: " + targetBlocks);
		}
		final Map<String, Integer> sourceInline = countInline(sourceTokens);
		final Map<String, Integer> targetInline = countInline(targetTokens);
		if (!sourceInline.equals(targetInline)) {
			problems.add(document + " unit " + index + ": inline tags changed\n      was: "
				+ sourceInline + "\n      now: " + targetInline);
		}

		// tags and headings are not the only casualty: a blank line carries no token at all, so a
		// model that silently drops one - the seam-whitespace defect this probe exists to catch -
		// is invisible to both checks above unless blank lines are counted directly
		final long sourceBlankLines = countBlankLines(before);
		final long targetBlankLines = countBlankLines(after);
		if (sourceBlankLines != targetBlankLines) {
			problems.add(document + " unit " + index + ": blank line count changed ("
				+ sourceBlankLines + " -> " + targetBlankLines + ")");
		}
		return problems;
	}

	/**
	 * Counts blank (whitespace-only) lines in a fragment.
	 *
	 * @param text the fragment to scan
	 * @return the number of blank lines
	 */
	private static long countBlankLines(@Nonnull String text) {
		return text.lines().filter(String::isBlank).count();
	}

	/**
	 * Renders the ordered block-level skeleton of a fragment.
	 *
	 * @param tokens scanner output
	 * @return a comparable description of block structure
	 */
	@Nonnull
	private static String describeBlocks(@Nonnull List<MarkupToken> tokens) {
		final StringBuilder result = new StringBuilder(64);
		for (final MarkupToken token : tokens) {
			switch (token.type()) {
				case HEADING -> result.append("h").append(token.level()).append(' ');
				case CODE -> result.append("code ");
				case TAG_OPEN -> appendIfBlock(result, token, "<" + token.name() + ">");
				case TAG_CLOSE -> appendIfBlock(result, token, "</" + token.name() + ">");
				case TAG_SELF_CLOSING -> appendIfBlock(result, token, "<" + token.name() + "/>");
				case COMMENT -> result.append("comment ");
			}
		}
		return result.toString().trim();
	}

	/**
	 * Appends a tag description only when the tag stands alone on its line.
	 *
	 * @param result accumulator
	 * @param token  the tag token
	 * @param text   the description to append
	 */
	private static void appendIfBlock(
		@Nonnull StringBuilder result,
		@Nonnull MarkupToken token,
		@Nonnull String text
	) {
		if (token.blockLevel()) {
			result.append(text).append(' ');
		}
	}

	/**
	 * Counts inline tag occurrences by name.
	 *
	 * @param tokens scanner output
	 * @return occurrence count keyed by tag name
	 */
	@Nonnull
	private static Map<String, Integer> countInline(@Nonnull List<MarkupToken> tokens) {
		final Map<String, Integer> counts = new java.util.TreeMap<>();
		for (final MarkupToken token : tokens) {
			if (token.isTag() && !token.blockLevel()) {
				counts.merge(token.name(), 1, Integer::sum);
			}
		}
		return counts;
	}

	/**
	 * Returns a required system property or fails the probe with a usable message.
	 *
	 * @param name the property name
	 * @return the property value
	 */
	@Nonnull
	private static String requireProperty(@Nonnull String name) {
		final String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			fail("-D" + name + " must be set");
		}
		return value;
	}

	/**
	 * Reads the sampling temperature to use, if any.
	 *
	 * Unset keeps the probe's historical behaviour (0.3), so every prior run stays comparable.
	 * `-Dcomenius.probe.temperature=default` omits it entirely - required for current-generation
	 * reasoning models, which reject any request that sets a custom temperature at all.
	 *
	 * @return the temperature to pass to {@link ChatModelFactory#create}, or null to omit it
	 */
	@Nullable
	private static Double readTemperature() {
		final String value = System.getProperty(TEMPERATURE_PROPERTY);
		if (value == null) {
			return 0.3;
		}
		if (value.equalsIgnoreCase("default")) {
			return null;
		}
		return Double.valueOf(value);
	}

	/**
	 * Reads every Markdown body under the given root, skipping generated example directories.
	 *
	 * @param root the corpus root
	 * @return document body keyed by path relative to the root
	 * @throws IOException when the tree cannot be walked
	 */
	@Nonnull
	private static Map<String, String> readCorpus(@Nonnull Path root) throws IOException {
		final Map<String, String> corpus = new LinkedHashMap<>();
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
							).getBodyContent()
						);
					} catch (IOException exception) {
						throw new UncheckedIOException(exception);
					}
				});
		}
		return corpus;
	}

}
