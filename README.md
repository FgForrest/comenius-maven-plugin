# comenius-maven-plugin

Maven plugin for automatically translating Markdown files using LLM (Large Language Model) engines. The plugin
recursively traverses directories, identifies Markdown files, and translates them into one or multiple target languages
while preserving formatting and structure.

## Features

- **Automatic Translation**: Translates Markdown files using configurable LLM providers (OpenAI, Anthropic)
- **Incremental Updates**: Only translates files that have changed since the last translation
- **Git Integration**: Tracks file changes using Git history for intelligent incremental updates
- **Link Validation**: Validates internal links before translation to prevent broken references
- **Parallel Processing**: Configurable parallelism for faster batch translations
- **Dry Run Mode**: Preview what would be translated without making changes
- **Custom Instructions**: Per-directory translation instructions via `.comenius-instructions` files
- **Large Document Splitting**: Automatically splits documents exceeding 32kB at heading boundaries for translation
- **Section-Based Incremental Updates**: Incremental translations compare document sections by hash and only retranslate changed sections
- **Heading Structure Validation**: Validates that LLM output preserves the source heading structure
- **Cross-Document Anchor Correction**: Automatically fixes stale anchor references in other translated files after retranslation
- **Fuzzy Anchor Matching**: Uses Levenshtein distance and token overlap for anchor correction in translated documents
- **Structural Validation**: Compares tag structure, heading order, and blank lines between source and translation, using a tag vocabulary derived automatically from your corpus
- **Untranslated-Content Autofix**: Detects text the model left untranslated and repairs it with a small, targeted follow-up request instead of a full retranslation
- **Structure Repair**: A dedicated `fix-structure` action restores headings a Markdown parser stopped recognising because of a missing blank line
- **Stale-Translation Detection**: Flags translations that were hand-updated ahead of their recorded commit instead of overwriting them
- **Rejected-Translation Diagnostics**: Keeps a copy of any translation a structural gate rejects on disk for inspection, instead of discarding it

## Quick Start

Add the plugin to your `pom.xml`:

```xml

<plugin>
    <groupId>one.edee.oss</groupId>
    <artifactId>comenius-maven-plugin</artifactId>
    <version>1.1.0</version>
    <configuration>
        <llmProvider>openai</llmProvider>
        <llmUrl>https://api.openai.com/v1</llmUrl>
        <llmToken>${env.OPENAI_API_KEY}</llmToken>
        <llmModel>gpt-4o</llmModel>
        <sourceDir>docs/en</sourceDir>
        <targets>
            <target>
                <locale>de</locale>
                <targetDir>docs/de</targetDir>
            </target>
            <target>
                <locale>fr</locale>
                <targetDir>docs/fr</targetDir>
            </target>
            <target>
                <locale>es</locale>
                <targetDir>docs/es</targetDir>
            </target>
        </targets>
    </configuration>
</plugin>
```

## Available Actions

The plugin provides five actions via the `comenius.action` parameter:

| Action          | Description                                                                         |
|-----------------|--------------------------------------------------------------------------------------|
| `show-config`   | Displays current plugin configuration (default)                                    |
| `check`         | Validates files - checks Git status and link validity, for `sourceDir` and every configured target |
| `translate`     | Executes the translation workflow                                                  |
| `fix-links`     | Corrects links in all translated files                                             |
| `fix-structure` | Restores headings a Markdown parser fails to recognise due to a missing blank line |

## Configuration Parameters

| Parameter              | Property                        | Default       | Description                                         |
|------------------------|---------------------------------|---------------|-----------------------------------------------------|
| `action`               | `comenius.action`               | `show-config` | Action to perform                                   |
| `llmProvider`          | `comenius.llmProvider`          | `openai`      | LLM provider: `openai` or `anthropic`               |
| `llmUrl`               | `comenius.llmUrl`               | -             | LLM API endpoint URL                                |
| `llmToken`             | `comenius.llmToken`             | -             | API authentication token                            |
| `llmModel`             | `comenius.llmModel`             | `gpt-4o`      | Model name to use                                   |
| `sourceDir`            | `comenius.sourceDir`            | -             | Source directory containing files to translate      |
| `fileRegex`            | `comenius.fileRegex`            | `(?i).*\.md`  | Regex pattern to match files                        |
| `targets`              | `comenius.targets`              | -             | List of target languages and directories            |
| `limit`                | `comenius.limit`                | `2147483647`  | Maximum number of files to process                  |
| `dryRun`               | `comenius.dryRun`               | `false`       | When true, simulates without writing                |
| `parallelism`          | `comenius.parallelism`          | `4`           | Number of parallel translation threads              |
| `excludedFilePatterns` | `comenius.excludedFilePatterns` | -             | List of regex patterns to exclude directories/files |
| `translatableFrontMatterFields` | `comenius.translatableFrontMatterFields` | - | Front matter fields to translate (e.g., title, perex) |
| `customFrontMatter` | `comenius.customFrontMatter` | - | Custom key-value pairs to add to translated files' front matter |
| `failureDir` | `comenius.failureDir` | `${project.build.directory}/comenius-failures` | Directory to keep translations rejected by a structural gate, for diagnosis; blank disables it |
| `allowShallowRepository` | `comenius.allowShallowRepository` | `false` | Permit `translate` to run in a shallow clone, where recorded commit provenance is wrong (see below) |

### Shallow clones

`translate` refuses to run in a shallow repository. Each translated file records the commit its
source was last changed in, taken from `git log -1 -- <file>`. A shallow clone cannot see past its
graft boundaries, so it reports the boundary commit for every file whose real last change is older
than the truncation point — a plausible-looking hash with nothing to do with that file, written into
the front matter of the whole corpus.

Run `git fetch --unshallow` before translating, or pass
`-Dcomenius.allowShallowRepository=true` to accept the wrong provenance deliberately.

If a recorded `commit:` value cannot be found in the repository at all — history was rewritten, or
the translation came from a different clone — the file is retranslated in full with a warning
instead of failing.

## Recommended Workflow

Follow this step-by-step approach when setting up translations for your project:

### Step 1: Verify Configuration

First, check that your configuration is correct:

```bash
mvn comenius:run -Dcomenius.action=show-config
```

This displays all configured parameters and warns about missing required values.

### Step 2: Run Pre-flight Checks

Before translating, validate that all source files are properly committed and links are valid:

```bash
mvn comenius:run -Dcomenius.action=check
```

The check action verifies:

- All matched files are committed to Git (no uncommitted changes)
- All internal links point to existing files
- No broken references that would cause issues in translations
- Each configured target directory, validated the same way against its own translated anchors (a
  Czech document linking to a Czech anchor is checked in Czech)

**Fix any reported errors before proceeding.**

### Step 3: Dry Run Preview

Preview what would be translated without making any changes:

```bash
mvn comenius:run -Dcomenius.action=translate -Dcomenius.dryRun=true
```

This shows:

- **New files**: Files that don't exist in the target directory
- **Files to update**: Files that have changed since last translation
- **Skipped files**: Files that are already up-to-date

### Step 4: Limited Test Run

Test the translation with a small number of files first:

```bash
mvn comenius:run -Dcomenius.action=translate -Dcomenius.limit=3
```

Review the translated files to ensure quality meets your standards before proceeding with a full translation.

### Step 5: Full Translation

Once satisfied with the test results, run the full translation:

```bash
mvn comenius:run -Dcomenius.action=translate   
```

### Step 6: CI/CD Integration

Integrate the plugin into your CI/CD pipeline for continuous translation of documentation.

#### GitHub Actions Example

```yaml
name: Translate Documentation

on:
  push:
    branches: [ main ]
    paths:
      - 'docs/en/**'

jobs:
  translate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # Required for Git history

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Check documentation
        run: |
          mvn comenius:run \
            -Dcomenius.action=check \
            -Dcomenius.sourceDir=docs/en

      - name: Translate documentation
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
        run: |
          mvn comenius:run \
            -Dcomenius.action=translate \
            -Dcomenius.sourceDir=docs/en \
            -Dcomenius.llmUrl=https://api.openai.com/v1 \
            -Dcomenius.llmToken=$OPENAI_API_KEY \
            -Dcomenius.dryRun=false

      - name: Commit translations
        run: |
          git config --local user.email "action@github.com"
          git config --local user.name "GitHub Action"
          git add docs/de docs/fr docs/es
          git diff --staged --quiet || git commit -m "chore: update translations"
          git push
```

#### GitLab CI Example

```yaml
translate-docs:
  stage: build
  image: maven:3.9-eclipse-temurin-17
  only:
    changes:
      - docs/en/**
  script:
    - mvn comenius:run -Dcomenius.action=check -Dcomenius.sourceDir=docs/en
    - mvn comenius:run
      -Dcomenius.action=translate
      -Dcomenius.sourceDir=docs/en
      -Dcomenius.llmUrl=https://api.openai.com/v1
      -Dcomenius.llmToken=$OPENAI_API_KEY
      -Dcomenius.dryRun=false
  artifacts:
    paths:
      - docs/de/
      - docs/fr/
      - docs/es/
```

## Custom Translation Instructions

You can provide per-directory translation instructions using special instruction files:

### `.comenius-instructions`

Create a `.comenius-instructions` file in any directory containing custom instructions for the translation. The file
directly contains the instruction text that will be passed to the LLM.

Instructions accumulate as the traverser descends into subdirectories, allowing you to:

- Define project-wide instructions at the root
- Add topic-specific instructions in subdirectories

### `.comenius-instructions.replace`

Use `.comenius-instructions.replace` instead to reset instruction accumulation and start fresh with only the
instructions in that file.

### Example Directory Structure

```
docs/en/
├── .comenius-instructions      # Contains project-wide glossary and style guide
├── getting-started/
│   ├── .comenius-instructions  # Contains API-specific terminology
│   └── quickstart.md           # Translated with root + getting-started instructions
└── advanced/
    ├── .comenius-instructions.replace  # Resets and contains advanced-only instructions
    └── architecture.md         # Translated with only advanced instructions
```

### Example Instruction File Content

A `.comenius-instructions` file might contain:

```
Use the following terminology consistently:
- "evitaDB" - never translate, always keep as-is
- "entity" -> "Entität" (German)
- "attribute" -> "Attribut" (German)

Style guidelines:
- Use formal "Sie" form in German translations
- Keep code examples unchanged
- Preserve all markdown formatting
```

## Translating Front Matter Fields

By default, YAML front matter at the beginning of Markdown files is **not translated**. This includes fields like
`author`, `date`, `motive`, and other metadata that should remain unchanged.

However, some front matter fields contain user-facing text that should be translated, such as `title`, `perex`, or
`description`. You can configure which fields should be translated using `translatableFrontMatterFields`.

### Configuration

```xml
<configuration>
    <translatableFrontMatterFields>
        <field>title</field>
        <field>perex</field>
        <field>description</field>
    </translatableFrontMatterFields>
</configuration>
```

### Example

**Source file (English):**
```yaml
---
title: Getting Started
perex: Learn how to set up and configure your first project
author: John Doe
date: 2024-01-15
---
# Getting Started
...
```

**Translated file (German):**
```yaml
---
title: Erste Schritte
perex: Erfahren Sie, wie Sie Ihr erstes Projekt einrichten und konfigurieren
author: John Doe
date: 2024-01-15
commit: abc123def456
---
# Erste Schritte
...
```

Note that:
- Only `title` and `perex` are translated (as configured)
- `author` and `date` remain unchanged
- The `commit` field is automatically added to track the source version

### Common Translatable Fields

| Field | Description |
|-------|-------------|
| `title` | Page or article title |
| `perex` | Short description or lead paragraph |
| `description` | Meta description for SEO |
| `summary` | Brief content summary |
| `keywords` | SEO keywords (if localized) |

### Incremental Updates

When using incremental translation mode, front matter is always **fully retranslated** for all configured fields.
This is because the plugin cannot safely detect whether changes occurred specifically in front matter fields, so
all configured fields are sent to the LLM for translation on every incremental update.

## Custom Front Matter Properties

You can add custom key-value pairs to the front matter of all translated files. This is useful for distinguishing
translated files from their originals - for example, to display an "auto-translated" disclaimer on your website.

### Configuration

```xml
<configuration>
    <customFrontMatter>
        <translated>true</translated>
        <generator>comenius</generator>
    </customFrontMatter>
</configuration>
```

### Example

With the configuration above, translated files will include the custom properties:

```yaml
---
title: Erste Schritte
author: John Doe
translated: 'true'
generator: comenius
commit: abc123def456
---
```

Custom properties are applied after source and translated fields but before the system-managed `commit` field.
The `commit` field cannot be overridden via custom front matter.

## Repairing Heading Structure

Under CommonMark, a heading that immediately follows a custom tag without a blank line in between is
absorbed into that tag's HTML block and never recognised as a heading - invisible to tag-balance checks
and to heading-structure validation alike, since the heading simply isn't in the parsed sequence. Section
splitting and anchor mapping both depend on the heading being seen, so a swallowed heading silently breaks
both. The `fix-structure` action finds and restores these, inserting a single blank line and touching
nothing else.

```bash
mvn comenius:run -Dcomenius.action=fix-structure
```

Detection is authoritative, not a guess: a candidate is only kept once re-parsing the document confirms
the parser now sees one more heading than before. Headings a blank line does not fix are reported as
needing a human look.

**Run this before `fix-links`** - anchors are mapped by heading position, and a swallowed heading shifts
every anchor after it by one.

Required: `targets`. Also respects `fileRegex`, `excludedFilePatterns`, and `dryRun` (previews repairs
as `[DRY-RUN] would restore ...` without writing).

## Fixing Links in Translated Files

The `fix-links` action corrects links in all translated files without performing new translations. This is useful for:

- Fixing links after manual edits to translated files
- Re-running link correction after source file structure changes
- Batch-correcting links across all target directories

### Running the Fix-Links Action

```bash
mvn comenius:run -Dcomenius.action=fix-links
```

### What Gets Corrected

1. **Asset links** - Relative paths to images, PDFs, and other assets are recalculated from the target
   directory to the source assets. Absolute links are never rewritten on principle - a model
   sometimes localises a language segment it finds in one (e.g. `/documentation/user/en/...` becomes
   `/documentation/user/cs/...`), so an absolute link is repaired only when it is broken (the file is
   missing where it points) and the original resolves at the same relative path under `sourceDir`;
   anything that already resolves is left untouched
2. **Anchor links** - Internal anchors (e.g., `#section-title`) are corrected using a two-phase
   fuzzy matching algorithm (see below)
3. **Front matter links** - Links in both translatable and non-translatable front matter fields are
   corrected. Translatable fields receive full markdown link correction. Non-translatable fields are
   checked for file path values and corrected if the resolved file exists in the source directory.

### Anchor Correction Algorithm

Anchor references need correction because heading text gets translated, changing the generated slug.
The plugin uses a two-phase strategy:

**Phase A - Target Language Matching** (compare anchor against the translated document's headings):
1. Exact match (case-insensitive)
2. Exact match against the *source* document's heading at the same position, position-mapped
   directly into the translation (only when source and translated heading counts match). An
   anchor that still names a source heading verbatim is not a similarity problem, and trying this
   before any fuzzy step keeps it from being "corrected" onto a heading that merely looks similar
3. Levenshtein distance (threshold: `max(2, anchor.length() / 3)`; a candidate whose token count
   differs from the anchor's is skipped unless the two are equal once hyphens are stripped, since a
   dropped word is otherwise cheap enough in edit distance to pass)
4. Token overlap (split on hyphens; requires a strict majority of the anchor's own tokens to match,
   and those matched tokens must also cover at least half of the candidate heading's own tokens)

**Phase B - Source Language Matching** (if Phase A fails, match against the source document then
position-map to translated):
1. Find the anchor in the source document's heading index
2. Map the matched position to the same position in the translated document's heading index
3. Uses the same fuzzy matching strategies (exact, Levenshtein) against the source

This layered approach tries exact, same-document mappings before any fuzzy guess, then falls back to
handle minor slug variations and full heading translations.

### Required Parameters

The `fix-links` action requires:
- `sourceDir` - The source directory containing original files (used for anchor mapping)
- `targets` - List of target directories to process

### Optional Parameters

The following parameters are also respected by `fix-links`:
- `fileRegex` - Pattern to match files in target directories (default: `(?i).*\.md`)
- `excludedFilePatterns` - Patterns to exclude from processing
- `translatableFrontMatterFields` - Determines which front matter fields receive full link correction
- `parallelism` - Number of parallel threads for link correction
- `dryRun` - Previews corrections without writing them (`[DRY-RUN] would correct N link(s) in ...`)

**Note:** `limit` has no effect on the `fix-links` action - it always processes all matching files.

### Example

```bash
mvn comenius:run \
  -Dcomenius.action=fix-links \
  -Dcomenius.sourceDir=docs/en
```

The action will process all target directories configured in your `pom.xml` and:
1. Find all markdown files matching the `fileRegex` pattern in each target directory
2. For each file, locate the corresponding source file at the same relative path in `sourceDir`
3. Correct asset links (recalculate paths from target to source assets)
4. Correct anchor links (two-phase fuzzy matching against translated and source headings)
5. Correct front matter links (translatable fields: full correction; non-translatable: path correction)
6. Write corrected files back to disk
7. Validate all links after correction and report any remaining errors

**Note:** Each file in the target directory must have a corresponding source file at the same
relative path. For example, if processing `docs/de/guide/intro.md`, the source file
`docs/en/guide/intro.md` must exist for anchor correction to work correctly.

**Note:** Unlike the `translate` action, `fix-links` does **not** perform cross-document anchor
correction (scanning other translated files for stale references). It only corrects links within
the files it processes.

## Excluding Directories and Files

Use `excludedFilePatterns` to skip directories or files from processing. This is useful for excluding
asset directories that contain images or other non-translatable content.

### Configuration

```xml

<excludedFilePatterns>
    <excludedFilePattern>.*/assets/.*</excludedFilePattern>
    <excludedFilePattern>.*/images/.*</excludedFilePattern>
    <excludedFilePattern>(?i).*/node_modules/.*</excludedFilePattern>
</excludedFilePatterns>
```

### Pattern Matching

- Patterns are matched against the **full absolute path** of files and directories
- Use `(?i)` prefix for case-insensitive matching
- Excluded directories are **skipped entirely** during traversal (efficient for large asset folders)

### Common Exclusion Patterns

| Pattern                  | Excludes                                    |
|--------------------------|---------------------------------------------|
| `.*/assets/.*`           | All files in any `assets` directory         |
| `.*/images/.*`           | All files in any `images` directory         |
| `.*/_.*\.md`             | Markdown files starting with underscore     |
| `(?i).*/node_modules/.*` | node_modules directories (case-insensitive) |

## LLM Provider Configuration

### OpenAI

```xml

<configuration>
    <llmProvider>openai</llmProvider>
    <llmUrl>https://api.openai.com/v1</llmUrl>
    <llmToken>${env.OPENAI_API_KEY}</llmToken>
    <llmModel>gpt-4o</llmModel>
</configuration>
```

### Anthropic

```xml

<configuration>
    <llmProvider>anthropic</llmProvider>
    <llmUrl>https://api.anthropic.com</llmUrl>
    <llmToken>${env.ANTHROPIC_API_KEY}</llmToken>
    <llmModel>claude-sonnet-4-20250514</llmModel>
</configuration>
```

### Azure OpenAI

```xml

<configuration>
    <llmProvider>openai</llmProvider>
    <llmUrl>https://your-resource.openai.azure.com</llmUrl>
    <llmToken>${env.AZURE_OPENAI_KEY}</llmToken>
    <llmModel>your-deployment-name</llmModel>
</configuration>
```

## Translation Summary

After a translation run, the plugin reports:

- **Successful**: Number of files successfully translated
- **Failed**: Number of files that failed to translate
- **Skipped**: Number of files already up-to-date
- **Already current, only the commit field is stale**: Reported separately (see
  [Detecting Hand-Updated Translations](#detecting-hand-updated-translations-ahead)) when a translation
  was hand-updated ahead of its recorded commit
- **Input tokens**: Total tokens sent to the LLM
- **Output tokens**: Total tokens received from the LLM

## Best Practices

1. **Always run `check` first**: Ensure all files are committed and links are valid before translating.

2. **Use dry run**: Preview changes before executing translations, especially for large documentation sets.

3. **Start with limits**: Use `-Dcomenius.limit=5` to test with a few files before full runs.

4. **Version control translations**: Commit translated files to track changes over time.

5. **Review incrementally**: When updating existing translations, review the diff to ensure quality.

6. **Secure your tokens**: Never commit API tokens. Use environment variables or CI/CD secrets.

7. **Monitor token usage**: Track input/output tokens to manage API costs.

8. **Use appropriate parallelism**: Adjust `-Dcomenius.parallelism` based on your API rate limits.

## Large Document Handling

For documents exceeding 32kB, the plugin automatically splits them into smaller chunks based on heading structure
and translates each chunk separately. This prevents LLM context window limitations and improves translation quality.

### Splitting Algorithm

- **Target chunk size**: 32kB (+/- 20% tolerance, i.e., 25.6kB - 38.4kB per chunk)
- **Heading preference**: H1 > H2 > H3 > H4 > H5 > H6 (higher-level headings are preferred split points)
- **Sequential translation**: Chunks are translated one at a time to maintain consistency and respect rate limits

The algorithm prefers splitting at higher-level headings (H1, H2) to maintain logical document sections. If no
suitable heading exists within the acceptable size range, it will split at the next available heading.

### Behavior

- Documents under 32kB are translated as a single unit (unchanged behavior)
- Documents over 32kB are automatically split at heading boundaries
- Translated chunks are rejoined in original order
- If any chunk fails, the entire document translation fails
- Content before the first heading (intro content) may become its own chunk if large enough

### Example

A 100kB document with this structure:

```markdown
# Introduction
(content)

# Getting Started
(content)

## Installation
(content)

## Configuration
(content)

# Advanced Topics
(content)
```

Would be split preferentially at H1 headings (`# Introduction`, `# Getting Started`, `# Advanced Topics`),
resulting in approximately 3 chunks that are each translated separately.

## Incremental Translation (Section-Based)

When updating existing translations, the plugin uses a section-based approach that compares document
sections by content hash and only retranslates sections that have actually changed.

### How It Works

1. The source document (both old and new versions) and the existing translation are split into
   sections at heading boundaries
2. Each section's content is hashed using SHA-256
3. Old and new source sections are aligned using a Longest Common Subsequence (LCS) algorithm
4. Sections are classified as UNCHANGED, MODIFIED, ADDED, or DELETED
5. Only MODIFIED and ADDED sections are sent to the LLM for translation
6. UNCHANGED sections reuse the existing translation as-is
7. DELETED sections are removed from the output

### Benefits

- **Reduced token usage**: Only changed sections are sent to the LLM, not the entire document
- **Stable translations**: Unchanged sections keep their existing translations, avoiding unnecessary drift
- **Context-aware**: Each section is translated with surrounding already-translated sections as context
- **Structural safety**: Heading structure is validated after each section translation

### Section Splitting

- Documents are split at ATX-style heading boundaries (`#`, `##`, `###`, etc.)
- Content before the first heading becomes an "intro" section
- Each heading starts a new section
- Sections are flat (not hierarchical) - every heading at any level starts a new section

### Context-Aware Prompts

When translating a section, the LLM receives:
- The section content wrapped in `[[TRANSLATE]]...[[/TRANSLATE]]` markers
- Preceding already-translated sections (for reference only)
- Following already-translated sections (for reference only)
- Custom translation instructions (if configured)

This ensures translation consistency across sections.

### Heading Structure Validation

After each section is translated, the plugin validates that the LLM preserved the heading
structure (same number of headings at the same levels). If validation fails:

1. The plugin retries once with a corrective prompt specifying the exact heading structure required
2. If the retry also fails, the translation job is marked as failed

### Fallback to Full Retranslation

If the existing translation's heading structure doesn't match the old source (e.g., the translation
was created before heading validation was added), the plugin falls back to a full retranslation of
the entire document instead of section-based updates.

### Unchanged Sections

If no source sections have changed (all hashes match), the existing translation is preserved
completely unchanged with no LLM calls for the body.

### Detecting Hand-Updated Translations (`[AHEAD]`)

Staleness is normally judged from the translation's `commit` front-matter field alone. If a
translation is hand-edited in the same commit as the source change, though, its content is already
current while the field still points at the old commit. Treating it as an ordinary incremental
update is worse than a no-op: section mapping needs the translation to match the source *at the
recorded commit*, but this one matches the *newer* source instead, so the mapping fails and the
plugin [falls back to a full-body retranslation](#fallback-to-full-retranslation) - overwriting the
hand-written text it was trying to preserve.

Before queuing a job, the plugin compares heading-level sequences (the one part of a document's
structure that survives translation) between the source at the recorded commit, the current source,
and the existing translation. It skips the job, with a warning, only when both hold: the source's
structure genuinely changed since the recorded commit, *and* the translation already carries the new
structure. Ordinary edits that reword prose without moving a heading are still translated as before.

```
[AHEAD] docs/de/guide.md: the translation already has the structure of the source at abc123, but its
commit field still says def456 - it was updated without that field being bumped. Skipping it: ...
Set 'commit: abc123' in its front matter once you have confirmed it is current.
```

This is reported, not auto-fixed - update the translation's `commit` field yourself once you've
confirmed it is current. The count is summarized separately from ordinary "Skipped" counts, in both
dry-run and real translation runs.

## Structural & Content Validation

Before a translated body is accepted, the plugin checks more than heading levels - it compares the
full set of tags, blank lines, and leaf text against the source.

### Tag Vocabulary

At the start of a `translate` run, the plugin scans every Markdown file under `sourceDir` and derives
a **tag vocabulary**: the custom/component tags actually used in your corpus (e.g. `<Note>`,
`<SourceClass>`), matched case-sensitively so a literal-HTML `<tr>` and a component `<Tr>` are never
confused. This vocabulary powers the three checks below. If derivation fails, they are disabled for
that run and a warning is logged, but translation continues.

### Structural Comparison

Compares a translated section against its source: block-level tags and headings are compared as an
ordered sequence (their order *is* document structure), inline tags as a multiset (word order
legitimately moves them within a translated sentence), and blank lines by count. A mismatch rejects
the translation and triggers a retry or failure, the same as a heading-structure mismatch.

### Tag-Case Repair

If the model renders a component tag with the wrong case (e.g. closing `<Td>` as `</td>`), the
casing is deterministically restored from the source - no model call involved - instead of the
translation being rejected outright.

### Untranslated-Content Autofix

Leaf text the model left untranslated is detected and repaired with a small, single-phrase follow-up
request instead of retranslating the whole section. If the model, asked to translate only that
phrase in isolation, still returns it unchanged, that is accepted as its considered answer (a
loanword or proper noun) rather than flagged again.

## Diagnosing Rejected Translations

When a structural gate rejects a translation - a tag mismatch, a swallowed heading, a section-count
mismatch - the rejected output is kept on disk by default instead of discarded, so a failure can be
diagnosed without paying for another LLM run just to reproduce it.

Configure the location with `failureDir` (default: `target/comenius-failures`; blank disables it).
Each rejection writes `reason.txt` plus the source and every attempted output under:

```
<failureDir>/<language>/<source-file>/<unit>/
```

Diagnostics never abort a run - if writing them fails, that failure is logged and swallowed.

## Two-Phase Translation Pipeline

Each file is translated in two separate LLM calls:

1. **Phase 1 - Front Matter**: Translatable front matter fields (e.g., `title`, `perex`) are extracted and
   sent to the LLM as structured `[[fieldName]]...[[/fieldName]]` blocks. The LLM returns translated fields
   in the same format. This phase is skipped if no `translatableFrontMatterFields` are configured.

2. **Phase 2 - Body**: The markdown body is translated. For new files, the entire body is sent (or chunked
   if over 32kB). For incremental updates, section-based translation is used.

The two phases use separate prompt templates, allowing the LLM to focus on each task independently.
Token usage from both phases is accumulated and reported in the summary.

## Translation Workflow Phases

When the `translate` action runs, it executes the following phases:

1. **File Collection**: Scans the source directory, respects `fileRegex`, `excludedFilePatterns`, and `limit`
2. **Job Creation**: For each file, creates a `TranslateNewJob` (no existing translation) or
   `TranslateIncrementalJob` (updating existing translation). Files already up-to-date are skipped.
3. **Translation Execution** (skipped if `dryRun=true`): Translates files in parallel using a ForkJoinPool.
   Each job goes through the two-phase translation pipeline.
4. **Link Correction**: Fixes asset paths and anchor references in all newly translated files
5. **Cross-Document Anchor Correction**: Scans other existing translated files for stale anchor references
   pointing to retranslated files, and updates them using fuzzy matching

### Permanent Failure Handling

If the LLM returns a permanent error (e.g., authentication failure, quota exceeded), the plugin:
- Signals the LLM client to reject new requests
- Shuts down the parallel executor immediately
- Reports remaining jobs as cancelled

## Cross-Document Anchor Correction

After translating files, the plugin scans all other translated files in the target directory for anchor
references that may have become stale due to heading changes in the retranslated files.

### How It Works

1. During translation, the plugin tracks which files had heading anchor changes
2. After all translations complete, it scans existing translated files (excluding just-translated ones)
3. For each link pointing to a retranslated file, it checks if the anchor still exists
4. Stale anchors are corrected using a multi-strategy approach:
   - **Exact match**: Anchor exists in the new document - no change needed
   - **Position-based mapping**: If old and new heading counts match, maps by position
   - **Fuzzy matching**: Levenshtein distance (threshold: `max(2, anchor.length() / 3)`) and
     token overlap (split on hyphens, requires strict majority match)
5. Uncorrectable anchors are logged as warnings

## Troubleshooting

### "Source directory not specified"

Ensure `sourceDir` is set either in `pom.xml` configuration or via `-Dcomenius.sourceDir`.

### "Not inside a git repository"

The plugin requires Git for change tracking. Initialize a Git repository or ensure your source directory is within one.

### "Check failed with N error(s)"

Run the `check` action and fix reported issues:

- Commit uncommitted files
- Fix or remove broken links

### Translation quality issues

- Add custom instructions with terminology glossaries
- Use more capable models (e.g., `gpt-4o` instead of `gpt-4o-mini`)
- Provide context through instruction files
- A rejected translation is kept under `failureDir` (default: `target/comenius-failures`) - inspect
  `reason.txt` and the attempted outputs there instead of re-running to reproduce the failure

### `[AHEAD]` warning during translate

The translation was hand-updated in the same commit as the source change, so its `commit`
front-matter field lags behind content that is already current. See
[Detecting Hand-Updated Translations](#detecting-hand-updated-translations-ahead). Update the
`commit` field once you've confirmed the translation is current; the plugin will not do this for you.

## License

MIT License