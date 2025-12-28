# Claude Code Instructions for comenius-maven-plugin

## Project Overview

**comenius-maven-plugin** is a Maven plugin that automatically translates Markdown files using LLM (Large Language Model) engines via LangChain4j. It recursively traverses directories to find Markdown files, parses them with YAML front matter support, and writes translated content to specified target directories.

- **Group ID**: io.evitadb
- **Artifact ID**: comenius-maven-plugin
- **License**: Apache License 2.0
- **Java Version**: 17

## Code Style

### General

- **Indentation**: Use tabs for indentation (not spaces)
- **Line Length**: Limit lines to 100 characters
- **No `var` keyword**: Always use explicit types
- **Use `final`**: Apply to all local variables
- **Use `this`**: Always prefix instance variables with `this`
- **JavaDoc**: Use Markdown syntax for formatting - never use HTML tags

### Null Annotations

Automatically add annotations from `javax.annotation`:
- `@Nonnull` for non-nullable parameters and return types
- `@Nullable` for nullable parameters and return types

Example:
```java
public CompletionStage<String> translate(
    @Nullable String instructions,
    @Nonnull String text,
    @Nonnull Locale locale
)
```

### Documentation

- Add JavaDoc to all classes and public methods
- Add line comments to complex logic
- Use `@param`, `@return`, `@throws` tags appropriately

### Performance Guidelines

- Prefer performance over readability in performance-critical code
- Avoid unnecessary memory allocations
- Avoid unnecessary object boxing
- Avoid using exceptions for control flow
- Cache computed values when appropriate (see `Traverser.computeInstructions`)

## Package Structure

```
io.evitadb.comenius/
├── ComeniusMojo.java      # Maven plugin entry point
├── Translator.java        # LLM interface via LangChain4j
├── Traverser.java         # File system traversal with Visitor pattern
├── Visitor.java           # Functional interface for processing files
├── Writer.java            # Markdown output handling
└── model/
    └── MarkdownDocument.java  # Markdown parsing with YAML front matter
```

## Key Design Patterns

### Visitor Pattern
The `Visitor` interface enables pluggable file processing:
```java
void visit(Path file, String content, @Nullable String instructions)
```

### Async/CompletionStage Pattern
`Translator` returns `CompletionStage<String>` for non-blocking LLM calls.

### Deterministic Ordering
All files are visited in lexicographical order for reproducible behavior.

## Key Dependencies

| Library | Purpose |
|---------|---------|
| LangChain4j 1.10.0 | LLM integration (ChatModel, ChatMessage, TokenUsage) |
| CommonMark 0.25.1 | Markdown parsing with extensions (tables, task lists, strikethrough, YAML front matter) |
| Jackson 2.19.2 | JSON serialization/deserialization |
| JSR-305 | @Nonnull and @Nullable annotations |

## Building

```bash
# Build the project
mvn clean install

# Run tests
mvn test

# Generate plugin descriptor
mvn plugin:descriptor
```

## Testing

### Framework
- JUnit 5 (Jupiter) for unit tests
- Mockito for mocking

### Test Naming Convention
Use format: `shouldDoSomethingWhenCondition` or `shouldThrowExceptionWhenCondition`

Examples:
- `shouldVisitFilesInOrderWhenPatternMatches()`
- `shouldAccumulateInstructionsFromParentDirectories()`

### Test Annotations
- `@DisplayName` at class level for overall test focus
- `@DisplayName` at method level for clear descriptions (do not repeat class description)

### Test Structure Pattern
```java
@DisplayName("Traverser file visiting")
public class TraverserTest {
    @Test
    @DisplayName("visits files in lexicographical order")
    public void shouldVisitFilesInOrderWhenPatternMatches() throws Exception {
        final Path root = Files.createTempDirectory("traverser-test-");
        try {
            // Setup and execute
            // Assert
        } finally {
            deleteRecursively(root);
        }
    }
}
```

## Maven Plugin Configuration

### Goals
- `comenius:run` - Main goal with actions: `show-config`, `translate`
- `comenius:help` - Auto-generated help

### Parameters
| Parameter | Description | Default |
|-----------|-------------|---------|
| `comenius.action` | Action to perform | - |
| `comenius.llmUrl` | LLM endpoint URL | - |
| `comenius.llmToken` | Authentication token | - |
| `comenius.sourceDir` | Root directory for traversal | - |
| `comenius.fileRegex` | Pattern to match files | `(?i).*\.md` |
| `comenius.targets` | List of Target (locale, targetDir) | - |
| `comenius.limit` | Max files to process | `Integer.MAX_VALUE` |
| `comenius.dryRun` | Simulation mode | `true` |

## Project-Specific Conventions

### Instruction File System
Per-directory translation instructions:
- `.comenius-instructions` - Contains translation instructions directly (accumulated from parent dirs)
- `.comenius-instructions.replace` - Resets instruction accumulation and starts fresh

### Path Handling
- Normalize all paths: `.toAbsolutePath().normalize()`
- Use `Path.relativize()` for display

### File I/O
- Always use UTF-8 encoding (`StandardCharsets.UTF_8`)
- Use `Files.readAllBytes()` and `Files.write()` consistently

### Null Validation
Use `Objects.requireNonNull()` for explicit validation:
```java
Objects.requireNonNull(markdown, "markdown must not be null");
```

## Common Tasks

### Adding a New Markdown Extension
1. Add CommonMark extension dependency to `pom.xml`
2. Register extension in `MarkdownDocument` parser configuration
3. Update `Writer` if special rendering is needed

### Adding a New Mojo Parameter
1. Add field with `@Parameter` annotation in `ComeniusMojo`
2. Add setter method for testing
3. Update `showConfig()` method to display the parameter
4. Update documentation

### Implementing New Visitor Logic
1. Create implementation of `Visitor` interface
2. Handle `file`, `content`, and `instructions` parameters
3. Use with `Traverser.traverse()`
