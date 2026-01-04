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

## Quick Start

Add the plugin to your `pom.xml`:

```xml

<plugin>
    <groupId>io.evitadb</groupId>
    <artifactId>comenius-maven-plugin</artifactId>
    <version>1.0.0</version>
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

The plugin provides four actions via the `comenius.action` parameter:

| Action        | Description                                           |
|---------------|-------------------------------------------------------|
| `show-config` | Displays current plugin configuration (default)       |
| `check`       | Validates files - checks Git status and link validity |
| `translate`   | Executes the translation workflow                     |
| `fix-links`   | Corrects links in all translated files                |

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
| `dryRun`               | `comenius.dryRun`               | `true`        | When true, simulates without writing                |
| `parallelism`          | `comenius.parallelism`          | `4`           | Number of parallel translation threads              |
| `excludedFilePatterns` | `comenius.excludedFilePatterns` | -             | List of regex patterns to exclude directories/files |
| `translatableFrontMatterFields` | `comenius.translatableFrontMatterFields` | - | Front matter fields to translate (e.g., title, perex) |

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

When using incremental translation mode, only front matter fields that have **changed** in the source file are
re-translated. Unchanged fields preserve their existing translations.

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

1. **Asset links** - Relative paths to images, PDFs, and other assets are recalculated from the target directory to the source assets
2. **Anchor links** - Internal anchors (e.g., `#section-title`) are translated by mapping heading positions between source and translated documents
3. **Front matter links** - Links in translatable front matter fields are also corrected

### Required Parameters

The `fix-links` action requires:
- `sourceDir` - The source directory containing original files (used for anchor mapping)
- `targets` - List of target directories to process

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
4. Correct anchor links (map heading positions from source to translated document)
5. Write corrected files back to disk
6. Validate all links after correction

**Note:** Each file in the target directory must have a corresponding source file at the same
relative path. For example, if processing `docs/de/guide/intro.md`, the source file
`docs/en/guide/intro.md` must exist for anchor correction to work correctly.

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

## License

Apache License 2.0
