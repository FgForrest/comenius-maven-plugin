package io.evitadb.comenius.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatModelFactory should create ChatModel instances for different providers")
public class ChatModelFactoryTest {

	@Test
	@DisplayName("shouldCreateOpenAiModelWithUrl")
	void shouldCreateOpenAiModelWithUrl() {
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"https://api.openai.com/v1",
			"test-api-key",
			"gpt-4o"
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldCreateAnthropicModelWithUrl")
	void shouldCreateAnthropicModelWithUrl() {
		final ChatModel model = ChatModelFactory.create(
			"anthropic",
			"https://api.anthropic.com",
			"test-api-key",
			"claude-sonnet-4-20250514"
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldThrowOnInvalidProvider")
	void shouldThrowOnInvalidProvider() {
		final Exception exception = assertThrows(IllegalArgumentException.class, () ->
			ChatModelFactory.create("unknown", "https://example.com", "key", null)
		);

		assertTrue(exception.getMessage().contains("Unknown provider"));
		assertTrue(exception.getMessage().contains("openai"));
		assertTrue(exception.getMessage().contains("anthropic"));
	}

	@Test
	@DisplayName("shouldThrowOnNullUrl")
	void shouldThrowOnNullUrl() {
		assertThrows(NullPointerException.class, () ->
			ChatModelFactory.create("openai", null, "key", null)
		);
	}

	@Test
	@DisplayName("shouldThrowOnBlankUrl")
	void shouldThrowOnBlankUrl() {
		final Exception exception = assertThrows(IllegalArgumentException.class, () ->
			ChatModelFactory.create("openai", "   ", "key", null)
		);

		assertTrue(exception.getMessage().contains("blank"));
	}

	@Test
	@DisplayName("shouldHandleNullToken")
	void shouldHandleNullToken() {
		// Should not throw - null token is allowed for local endpoints
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"http://localhost:11434/v1",
			null,
			"llama2"
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldHandleBlankToken")
	void shouldHandleBlankToken() {
		// Should not throw - blank token is allowed for local endpoints
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"http://localhost:11434/v1",
			"   ",
			null
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldNormalizeUrlTrailingSlash")
	void shouldNormalizeUrlTrailingSlash() {
		// Should work with trailing slashes
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"https://api.openai.com/v1///",
			"key",
			null
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldAcceptCaseInsensitiveProvider")
	void shouldAcceptCaseInsensitiveProvider() {
		final ChatModel openai = ChatModelFactory.create(
			"OPENAI",
			"https://api.openai.com/v1",
			"key",
			null
		);
		assertNotNull(openai);

		final ChatModel anthropic = ChatModelFactory.create(
			"Anthropic",
			"https://api.anthropic.com",
			"key",
			null
		);
		assertNotNull(anthropic);
	}

	@Test
	@DisplayName("shouldTrimProviderString")
	void shouldTrimProviderString() {
		final ChatModel model = ChatModelFactory.create(
			"  openai  ",
			"https://api.openai.com/v1",
			"key",
			null
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldUseDefaultModelWhenNull")
	void shouldUseDefaultModelWhenNull() {
		// Should use default model when modelName is null
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"https://api.openai.com/v1",
			"key",
			null
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldUseDefaultModelWhenBlank")
	void shouldUseDefaultModelWhenBlank() {
		// Should use default model when modelName is blank
		final ChatModel model = ChatModelFactory.create(
			"openai",
			"https://api.openai.com/v1",
			"key",
			"   "
		);

		assertNotNull(model);
	}

	@Test
	@DisplayName("shouldThrowOnNullProvider")
	void shouldThrowOnNullProvider() {
		assertThrows(NullPointerException.class, () ->
			ChatModelFactory.create(null, "https://example.com", "key", null)
		);
	}
}
