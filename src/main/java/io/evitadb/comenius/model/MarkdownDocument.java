package io.evitadb.comenius.model;

import org.commonmark.Extension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper class for parsed Markdown content with YAML front matter support.
 * Provides methods for parsing, modifying, and serializing markdown documents
 * with their front matter properties.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MarkdownDocument {

	/**
	 * List of CommonMark extensions used for parsing and rendering.
	 */
	public static final List<Extension> MARKDOWN_EXTENSIONS = List.of(
		YamlFrontMatterExtension.create(),
		StrikethroughExtension.create(),
		TablesExtension.create(),
		TaskListItemsExtension.create()
	);

	private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile(
		"^---\\s*\\n(.*?)\\n---\\s*\\n?",
		Pattern.DOTALL
	);

	private final Node document;
	private final Map<String, List<String>> properties;
	private final String rawMarkdown;

	/**
	 * Constructs a new MarkdownDocument by parsing the provided Markdown content.
	 * The document is processed to capture YAML front matter data and is stored for further access.
	 *
	 * @param markdown the Markdown content to parse; must not be null
	 */
	public MarkdownDocument(@Nonnull String markdown) {
		Objects.requireNonNull(markdown, "markdown must not be null");
		this.rawMarkdown = markdown;

		// Use all extensions for proper parsing (fixed bug - was only using YamlFrontMatterExtension)
		final Parser parser = Parser.builder()
			.extensions(MARKDOWN_EXTENSIONS)
			.build();
		this.document = parser.parse(markdown);

		final YamlFrontMatterVisitor visitor = new YamlFrontMatterVisitor();
		this.document.accept(visitor);
		// Create a mutable copy of the properties map, preserving insertion order
		this.properties = new LinkedHashMap<>(visitor.getData());
	}

	/**
	 * Returns the root Node of the parsed Markdown document.
	 *
	 * @return the root Node representing the parsed Markdown content
	 */
	@Nonnull
	public Node getDocument() {
		return this.document;
	}

	/**
	 * Retrieves the first value associated with the specified key from the properties map.
	 * If the key does not exist or has no associated values, an empty Optional is returned.
	 *
	 * @param key the key for which the associated property value is being retrieved
	 * @return an Optional containing the first value associated with the key,
	 *         or an empty Optional if no value is found
	 */
	@Nonnull
	public Optional<String> getProperty(@Nonnull String key) {
		Objects.requireNonNull(key, "key must not be null");
		final List<String> values = this.properties.get(key);
		if (values == null || values.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(values.get(0));
	}

	/**
	 * Sets a single-value front matter property.
	 * If the property already exists, its value is replaced.
	 *
	 * @param key   the property key; must not be null
	 * @param value the property value; must not be null
	 */
	public void setProperty(@Nonnull String key, @Nonnull String value) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(value, "value must not be null");
		this.properties.put(key, List.of(value));
	}

	/**
	 * Returns all front matter properties as an immutable map preserving insertion order.
	 *
	 * @return map of property names to their values (ordered by insertion)
	 */
	@Nonnull
	public Map<String, List<String>> getProperties() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(this.properties));
	}

	/**
	 * Merges additional properties into the existing properties map.
	 * If a key already exists, its values are replaced by the new values.
	 *
	 * @param additionalProperties the map of additional properties to merge; must not be null
	 */
	public void mergeFrontMatterProperties(@Nonnull Map<String, List<String>> additionalProperties) {
		Objects.requireNonNull(additionalProperties, "additionalProperties must not be null");
		this.properties.putAll(additionalProperties);
	}

	/**
	 * Serializes the front matter properties to YAML format with `---` delimiters.
	 * Returns an empty string if there are no properties.
	 *
	 * @return YAML front matter string including delimiters, or empty string if no properties
	 */
	@Nonnull
	public String serializeFrontMatter() {
		if (this.properties.isEmpty()) {
			return "";
		}

		final StringBuilder sb = new StringBuilder();
		sb.append("---\n");

		for (final Map.Entry<String, List<String>> entry : this.properties.entrySet()) {
			final String key = entry.getKey();
			final List<String> values = entry.getValue();

			if (values.isEmpty()) {
				continue;
			}

			if (values.size() == 1) {
				final String value = values.get(0);
				// Handle multiline values with block scalar
				if (value.contains("\n")) {
					sb.append(key).append(": |\n");
					for (final String line : value.split("\n", -1)) {
						sb.append("  ").append(line).append("\n");
					}
				} else {
					sb.append(key).append(": ").append(quoteIfNeeded(value)).append("\n");
				}
			} else {
				// Array format for multiple values
				sb.append(key).append(":\n");
				for (final String v : values) {
					sb.append("  - ").append(quoteIfNeeded(v)).append("\n");
				}
			}
		}

		sb.append("---\n");
		return sb.toString();
	}

	/**
	 * Returns the markdown body content without front matter.
	 * This extracts the content after the closing `---` delimiter.
	 *
	 * @return markdown body content, or the full content if no front matter
	 */
	@Nonnull
	public String getBodyContent() {
		final Matcher matcher = FRONT_MATTER_PATTERN.matcher(this.rawMarkdown);
		if (matcher.find()) {
			return this.rawMarkdown.substring(matcher.end());
		}
		return this.rawMarkdown;
	}

	/**
	 * Returns the raw markdown content that was used to create this document.
	 *
	 * @return the original raw markdown string
	 */
	@Nonnull
	public String getRawMarkdown() {
		return this.rawMarkdown;
	}

	/**
	 * Quotes a YAML value if it contains special characters that require quoting.
	 *
	 * @param value the value to potentially quote
	 * @return the quoted value if needed, or the original value
	 */
	@Nonnull
	private static String quoteIfNeeded(@Nonnull String value) {
		// Quote if value contains YAML special characters or starts with problematic chars
		if (value.isEmpty()) {
			return "''";
		}

		final boolean needsQuoting = value.contains(":") ||
			value.contains("#") ||
			value.contains("'") ||
			value.contains("\"") ||
			value.startsWith("-") ||
			value.startsWith("[") ||
			value.startsWith("{") ||
			value.startsWith("!") ||
			value.startsWith("&") ||
			value.startsWith("*") ||
			value.startsWith(">") ||
			value.startsWith("|") ||
			value.startsWith("@") ||
			value.startsWith("`") ||
			value.matches("^\\d.*") ||
			value.equalsIgnoreCase("true") ||
			value.equalsIgnoreCase("false") ||
			value.equalsIgnoreCase("null") ||
			value.equalsIgnoreCase("yes") ||
			value.equalsIgnoreCase("no");

		if (needsQuoting) {
			// Use single quotes and escape any existing single quotes
			return "'" + value.replace("'", "''") + "'";
		}

		return value;
	}
}
