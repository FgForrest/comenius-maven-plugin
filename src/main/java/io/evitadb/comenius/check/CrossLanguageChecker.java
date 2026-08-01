package io.evitadb.comenius.check;

import io.evitadb.comenius.git.GitService;
import io.evitadb.comenius.model.MarkdownDocument;
import io.evitadb.comenius.structure.MarkupScanner;
import io.evitadb.comenius.structure.MarkupToken;
import io.evitadb.comenius.structure.TagVocabulary;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates a translated document's markup shape against its English source - the checks a
 * translation run itself can skip entirely, because it only ever revisits units the source
 * changed.
 *
 * Two independent checks, both gated to avoid false positives on legitimately divergent or stale
 * documents:
 *
 * <p><b>Tag-scope check</b> - for each heading, compares the chain of enclosing block-level tags
 * (e.g. a language-switch {@code <LS to="...">}) against the same-position heading in the source.
 * A heading routed to the wrong language audience leaves every count identical - headings, tag
 * balance, blank lines - so no existing check sees it; only the *shape* of the nesting does. See
 * the 2026-07-28 {@code capture-changes.md} incident this exists to catch.</p>
 *
 * <p><b>Structural-completeness check</b> - when a translation's {@code commit:} field claims to
 * match a source revision that is still current (the source has not moved on since), its
 * structural token count (headings and tags) must match the source's. A drop is the signature of
 * a translation run that silently lost content - the class of defect the tag-blind chunked
 * new-job path allowed before {@link io.evitadb.comenius.Translator#translateChunkedBody} gained
 * its own per-chunk validation. Gating on the commit field is what tells a genuine loss apart from
 * a translation that is simply stale, which is expected and not an error.</p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public final class CrossLanguageChecker {

	@Nonnull
	private final Path sourceDir;
	@Nonnull
	private final Path targetDir;
	@Nonnull
	private final GitService gitService;
	@Nonnull
	private final TagVocabulary vocabulary;
	@Nonnull
	private final List<StructuralError> errors = new ArrayList<>();

	/**
	 * Creates a CrossLanguageChecker comparing files under {@code targetDir} against their
	 * counterparts at the same relative path under {@code sourceDir}.
	 *
	 * @param sourceDir  the English source directory
	 * @param targetDir  the translated target directory
	 * @param gitService git service for the shared repository, used to gate the completeness check
	 * @param vocabulary corpus-derived tag vocabulary, used to scan both sides identically
	 */
	public CrossLanguageChecker(
		@Nonnull Path sourceDir,
		@Nonnull Path targetDir,
		@Nonnull GitService gitService,
		@Nonnull TagVocabulary vocabulary
	) {
		this.sourceDir = Objects.requireNonNull(sourceDir, "sourceDir must not be null")
			.toAbsolutePath().normalize();
		this.targetDir = Objects.requireNonNull(targetDir, "targetDir must not be null")
			.toAbsolutePath().normalize();
		this.gitService = Objects.requireNonNull(gitService, "gitService must not be null");
		this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary must not be null");
	}

	/**
	 * Checks a single translated file against its source counterpart, if one exists.
	 *
	 * @param translatedFile    the translated file
	 * @param translatedContent the translated file's full content, including front matter
	 */
	public void checkFile(@Nonnull Path translatedFile, @Nonnull String translatedContent) {
		Objects.requireNonNull(translatedFile, "translatedFile must not be null");
		Objects.requireNonNull(translatedContent, "translatedContent must not be null");

		final Path normalizedTranslated = translatedFile.toAbsolutePath().normalize();
		final Path sourceFile = calculateSourceFile(normalizedTranslated);
		if (!Files.isRegularFile(sourceFile)) {
			// no English counterpart to compare against - nothing to check
			return;
		}

		final String sourceContent;
		try {
			sourceContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			// unreadable source is not this checker's problem to report
			return;
		}

		final String sourceBody = new MarkdownDocument(sourceContent).getBodyContent();
		final MarkdownDocument translatedDoc = new MarkdownDocument(translatedContent);
		final String translatedBody = translatedDoc.getBodyContent();

		checkTagScope(normalizedTranslated, sourceBody, translatedBody);
		checkStructuralCompleteness(normalizedTranslated, sourceFile, sourceBody, translatedDoc, translatedBody);
	}

	/**
	 * Returns the collected structural errors.
	 *
	 * @return immutable list of structural errors found so far
	 */
	@Nonnull
	public List<StructuralError> getErrors() {
		return List.copyOf(this.errors);
	}

	/**
	 * Compares, for each heading in document order, the chain of enclosing block-level tags in
	 * the source against the same-position heading in the translation.
	 *
	 * @param translatedFile the translated file, for error reporting
	 * @param sourceBody     the source body content
	 * @param translatedBody the translated body content
	 */
	private void checkTagScope(
		@Nonnull Path translatedFile,
		@Nonnull String sourceBody,
		@Nonnull String translatedBody
	) {
		final ScopeWalkResult sourceWalk = headingScopes(sourceBody);
		final ScopeWalkResult translatedWalk = headingScopes(translatedBody);

		// An unmatched closing tag means the translation's own structure is already broken -
		// continuing would compare scope chains computed past a point that no longer reflects
		// real nesting, producing a cascade of confusing mismatches instead of one clear cause.
		if (translatedWalk.unmatchedClose()) {
			this.errors.add(new StructuralError(
				translatedFile,
				StructuralError.StructuralErrorType.UNMATCHED_CLOSING_TAG,
				"translation has a closing tag with no matching open - language scope cannot be" +
					" reliably compared past that point"
			));
			return;
		}

		final List<List<String>> sourceScopes = sourceWalk.headingScopes();
		final List<List<String>> translatedScopes = translatedWalk.headingScopes();

		if (sourceScopes.size() != translatedScopes.size()) {
			this.errors.add(new StructuralError(
				translatedFile,
				StructuralError.StructuralErrorType.HEADING_COUNT_MISMATCH,
				"cannot compare language scope: source has " + sourceScopes.size() +
					" heading(s), translation has " + translatedScopes.size()
			));
			return;
		}

		for (int i = 0; i < sourceScopes.size(); i++) {
			final List<String> sourceScope = sourceScopes.get(i);
			final List<String> translatedScope = translatedScopes.get(i);
			if (!sourceScope.equals(translatedScope)) {
				this.errors.add(new StructuralError(
					translatedFile,
					StructuralError.StructuralErrorType.TAG_SCOPE_MISMATCH,
					"heading " + (i + 1) + " sits in a different language scope than its source" +
						" (source: " + sourceScope + ", translation: " + translatedScope + ")"
				));
			}
		}
	}

	/**
	 * When the translation's {@code commit:} field shows the source has not moved on, its
	 * structural token count must match the source's - a divergence there is content loss, not
	 * staleness.
	 *
	 * @param translatedFile the translated file, for error reporting
	 * @param sourceFile     the source file, for git history lookups
	 * @param sourceBody     the source body content
	 * @param translatedDoc  the parsed translated document, for its front matter
	 * @param translatedBody the translated body content
	 */
	private void checkStructuralCompleteness(
		@Nonnull Path translatedFile,
		@Nonnull Path sourceFile,
		@Nonnull String sourceBody,
		@Nonnull MarkdownDocument translatedDoc,
		@Nonnull String translatedBody
	) {
		final Optional<String> commitField = translatedDoc.getProperty("commit");
		if (commitField.isEmpty() || commitField.get().isBlank()) {
			// nothing to gate on - a translation with no commit field predates that convention
			return;
		}

		final int commitsSince;
		try {
			commitsSince = this.gitService.getCommitCount(sourceFile, commitField.get(), "HEAD");
		} catch (IOException e) {
			// commit unresolvable (renamed file, shallow clone, ...) - not this checker's problem
			// to guess about
			return;
		}
		if (commitsSince > 0) {
			// source has moved on since this translation was made - legitimately stale, not a defect
			return;
		}

		final int sourceTokens = structuralTokenCount(sourceBody);
		final int translatedTokens = structuralTokenCount(translatedBody);
		if (sourceTokens != translatedTokens) {
			this.errors.add(new StructuralError(
				translatedFile,
				StructuralError.StructuralErrorType.LIKELY_CONTENT_LOSS,
				"translation claims commit " + commitField.get() + " (source has no commits since)" +
					" but has " + translatedTokens + " structural token(s) (headings + tags) against" +
					" the source's " + sourceTokens
			));
		}
	}

	/**
	 * For each heading in document order, the chain of enclosing block-level tags at that point,
	 * outermost first - e.g. a heading nested two language-switch tags deep yields a two-element
	 * chain. Only block-level tags participate: an inline tag carries no structural meaning and
	 * legitimately moves within a translated sentence.
	 *
	 * @param body the markdown body content
	 * @return the per-heading scope chains, and whether a closing tag with no matching open was
	 *         seen - in which case the chains past that point do not reflect real nesting
	 */
	@Nonnull
	private ScopeWalkResult headingScopes(@Nonnull String body) {
		final List<MarkupToken> tokens = new MarkupScanner(this.vocabulary).scan(body);
		final List<String> stack = new ArrayList<>();
		final List<List<String>> scopes = new ArrayList<>();
		boolean unmatchedClose = false;
		for (final MarkupToken token : tokens) {
			if (token.type() == MarkupToken.Type.HEADING) {
				scopes.add(List.copyOf(stack));
			} else if (!token.blockLevel()) {
				continue;
			} else if (token.type() == MarkupToken.Type.TAG_OPEN) {
				stack.add(body.substring(token.start(), token.end()));
			} else if (token.type() == MarkupToken.Type.TAG_CLOSE) {
				if (stack.isEmpty()) {
					unmatchedClose = true;
				} else {
					stack.remove(stack.size() - 1);
				}
			}
		}
		return new ScopeWalkResult(scopes, unmatchedClose);
	}

	/**
	 * @param headingScopes  the per-heading scope chains, in document order
	 * @param unmatchedClose whether a closing tag with no matching open was seen during the walk
	 */
	private record ScopeWalkResult(@Nonnull List<List<String>> headingScopes, boolean unmatchedClose) {
	}

	/**
	 * Counts headings and tags (open, close, and self-closing) - the same signature
	 * {@code StructuralComparator} compares within a single translation unit, applied here across
	 * an entire document to catch loss a per-unit check never had the chance to see.
	 *
	 * @param body the markdown body content
	 * @return the number of heading and tag tokens
	 */
	private int structuralTokenCount(@Nonnull String body) {
		int count = 0;
		for (final MarkupToken token : new MarkupScanner(this.vocabulary).scan(body)) {
			if (token.type() == MarkupToken.Type.HEADING || token.isTag()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Calculates the corresponding source file path for a translated file, mirroring
	 * {@code LinkCorrector.calculateSourceFile}: the source file has the same relative path from
	 * {@code sourceDir} as the translated file has from {@code targetDir}.
	 *
	 * @param translatedFile the translated file path
	 * @return the corresponding source file path
	 */
	@Nonnull
	private Path calculateSourceFile(@Nonnull Path translatedFile) {
		final Path relativePath = this.targetDir.relativize(translatedFile);
		return this.sourceDir.resolve(relativePath).normalize();
	}
}
