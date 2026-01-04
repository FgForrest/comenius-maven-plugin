package io.evitadb.comenius.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@DisplayName("LlmClient permanent failure handling")
class LlmClientTest {

	private ChatModel mockModel;

	@BeforeEach
	void setUp() {
		mockModel = mock(ChatModel.class);
	}

	@Test
	@DisplayName("succeeds on first attempt when no error")
	void shouldSucceedOnFirstAttempt() {
		final ChatResponse expectedResponse = ChatResponse.builder()
			.aiMessage(AiMessage.from("Hello"))
			.build();
		when(mockModel.chat(anyList())).thenReturn(expectedResponse);

		final LlmClient client = new LlmClient(mockModel);
		final ChatResponse response = client.chat(List.of(UserMessage.from("Hi")));

		assertEquals("Hello", response.aiMessage().text());
		verify(mockModel, times(1)).chat(anyList());
	}

	@Test
	@DisplayName("stops immediately on permanent exception")
	void shouldStopOnPermanentException() {
		when(mockModel.chat(anyList())).thenThrow(new AuthenticationException("Invalid API key"));

		final LlmClient client = new LlmClient(mockModel);

		assertThrows(NonRetriableException.class, () ->
			client.chat(List.of(UserMessage.from("Hi"))));
		verify(mockModel, times(1)).chat(anyList());
	}

	@Test
	@DisplayName("sets permanent failure flag on auth error")
	void shouldSetPermanentFailureFlag() {
		when(mockModel.chat(anyList())).thenThrow(new AuthenticationException("Invalid API key"));

		final LlmClient client = new LlmClient(mockModel);

		assertFalse(client.hasPermanentFailure());
		assertThrows(NonRetriableException.class, () ->
			client.chat(List.of(UserMessage.from("Hi"))));
		assertTrue(client.hasPermanentFailure());
		assertNotNull(client.getFailureCause());
	}

	@Test
	@DisplayName("fast-fails after permanent failure is set")
	void shouldFastFailAfterPermanentFailure() {
		// First call succeeds
		when(mockModel.chat(anyList())).thenReturn(
			ChatResponse.builder().aiMessage(AiMessage.from("OK")).build()
		);

		final LlmClient client = new LlmClient(mockModel);
		client.chat(List.of(UserMessage.from("Hi"))); // Should succeed

		// Simulate another thread setting permanent failure
		client.signalShutdown();

		// Next call should fail immediately without calling model
		reset(mockModel);
		assertThrows(LangChain4jException.class, () ->
			client.chat(List.of(UserMessage.from("Hi"))));
		verify(mockModel, never()).chat(anyList());
	}

	@Test
	@DisplayName("preserves failure cause in subsequent calls")
	void shouldPreserveFailureCause() {
		when(mockModel.chat(anyList())).thenThrow(new AuthenticationException("Invalid API key"));

		final LlmClient client = new LlmClient(mockModel);

		// First call sets the cause
		assertThrows(NonRetriableException.class, () ->
			client.chat(List.of(UserMessage.from("Hi"))));

		final NonRetriableException originalCause = client.getFailureCause();
		assertNotNull(originalCause);

		// Second call should reference the original cause
		final LangChain4jException thrown = assertThrows(LangChain4jException.class, () ->
			client.chat(List.of(UserMessage.from("Hi again"))));

		assertTrue(thrown.getMessage().contains("previous permanent failure"));
	}

	@Test
	@DisplayName("propagates retriable exceptions without setting shutdown flag")
	void shouldPropagateRetriableExceptionWithoutShutdown() {
		when(mockModel.chat(anyList())).thenThrow(new RateLimitException("Rate limit exceeded"));

		final LlmClient client = new LlmClient(mockModel);

		// Should throw the retriable exception
		assertThrows(RateLimitException.class, () ->
			client.chat(List.of(UserMessage.from("Hi"))));

		// But NOT set permanent failure flag
		assertFalse(client.hasPermanentFailure());
		assertNull(client.getFailureCause());
	}

	@Test
	@DisplayName("throws on null messages")
	void shouldThrowOnNullMessages() {
		final LlmClient client = new LlmClient(mockModel);

		assertThrows(NullPointerException.class, () -> client.chat(null));
	}

	@Test
	@DisplayName("signalShutdown with cause sets both flag and cause")
	void shouldSignalShutdownWithCause() {
		final LlmClient client = new LlmClient(mockModel);
		final AuthenticationException cause = new AuthenticationException("Test shutdown");

		assertFalse(client.hasPermanentFailure());
		assertNull(client.getFailureCause());

		client.signalShutdown(cause);

		assertTrue(client.hasPermanentFailure());
		assertSame(cause, client.getFailureCause());
	}
}
