package io.evitadb.comenius.llm;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for loading and interpolating prompt templates from classpath resources.
 * Templates are loaded from `META-INF/prompts/` and cached for performance.
 * Placeholders in the format `{{name}}` are replaced with provided values.
 */
public final class PromptLoader {

	private static final String PROMPTS_PATH = "META-INF/prompts/";
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

	private final Map<String, String> templateCache = new ConcurrentHashMap<>();

	/**
	 * Loads a prompt template from the classpath.
	 *
	 * @param templateName the template file name (e.g., "translate-new-system.txt")
	 * @return the template content
	 * @throws IllegalArgumentException if template is not found
	 */
	@Nonnull
	public String loadTemplate(@Nonnull String templateName) {
		Objects.requireNonNull(templateName, "templateName must not be null");

		return this.templateCache.computeIfAbsent(templateName, this::loadTemplateFromClasspath);
	}

	/**
	 * Loads a template and replaces placeholders with provided values.
	 *
	 * @param templateName the template file name
	 * @param values       map of placeholder names to their values (without {{ }})
	 * @return the interpolated template
	 * @throws IllegalArgumentException if template is not found
	 */
	@Nonnull
	public String loadAndInterpolate(@Nonnull String templateName, @Nonnull Map<String, String> values) {
		Objects.requireNonNull(values, "values must not be null");

		final String template = loadTemplate(templateName);
		return interpolate(template, values);
	}

	/**
	 * Replaces placeholders in a template string with provided values.
	 * Placeholders use the format `{{name}}`.
	 * If a placeholder has no corresponding value, it is left unchanged.
	 * Empty or null values result in an empty string replacement.
	 *
	 * @param template the template string with placeholders
	 * @param values   map of placeholder names to their values
	 * @return the interpolated string
	 */
	@Nonnull
	public String interpolate(@Nonnull String template, @Nonnull Map<String, String> values) {
		Objects.requireNonNull(template, "template must not be null");
		Objects.requireNonNull(values, "values must not be null");

		final Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
		final StringBuilder result = new StringBuilder();

		while (matcher.find()) {
			final String placeholder = matcher.group(1);
			final String value = values.get(placeholder);
			// If value is null or not in map, leave placeholder as-is
			// If value is empty string, replace with empty string
			if (value != null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(value));
			}
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Loads a template from the classpath.
	 *
	 * @param templateName the template file name
	 * @return the template content
	 * @throws IllegalArgumentException if template is not found
	 */
	@Nonnull
	private String loadTemplateFromClasspath(@Nonnull String templateName) {
		final String resourcePath = PROMPTS_PATH + templateName;
		final InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);

		if (inputStream == null) {
			throw new IllegalArgumentException("Prompt template not found: " + resourcePath);
		}

		try (final BufferedReader reader = new BufferedReader(
			new InputStreamReader(inputStream, StandardCharsets.UTF_8)
		)) {
			final StringBuilder content = new StringBuilder();
			String line;
			boolean first = true;
			while ((line = reader.readLine()) != null) {
				if (!first) {
					content.append("\n");
				}
				content.append(line);
				first = false;
			}
			return content.toString();
		} catch (IOException e) {
			throw new IllegalArgumentException("Failed to read prompt template: " + resourcePath, e);
		}
	}

	/**
	 * Clears the template cache. Useful for testing.
	 */
	public void clearCache() {
		this.templateCache.clear();
	}
}
