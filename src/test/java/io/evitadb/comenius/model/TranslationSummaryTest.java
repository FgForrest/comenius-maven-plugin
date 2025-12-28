package io.evitadb.comenius.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TranslationSummary should track translation statistics")
public class TranslationSummaryTest {

	@Test
	@DisplayName("shouldCreateEmptySummary")
	void shouldCreateEmptySummary() {
		final TranslationSummary summary = TranslationSummary.empty();

		assertEquals(0, summary.successCount());
		assertEquals(0, summary.failedCount());
		assertEquals(0, summary.skippedCount());
		assertEquals(0, summary.inputTokens());
		assertEquals(0, summary.outputTokens());
		assertEquals(0, summary.getTotalCount());
	}

	@Test
	@DisplayName("shouldTrackSuccessWithTokens")
	void shouldTrackSuccessWithTokens() {
		final TranslationSummary summary = TranslationSummary.empty()
			.withSuccess(100, 50);

		assertEquals(1, summary.successCount());
		assertEquals(0, summary.failedCount());
		assertEquals(100, summary.inputTokens());
		assertEquals(50, summary.outputTokens());
	}

	@Test
	@DisplayName("shouldTrackMultipleSuccesses")
	void shouldTrackMultipleSuccesses() {
		final TranslationSummary summary = TranslationSummary.empty()
			.withSuccess(100, 50)
			.withSuccess(200, 100)
			.withSuccess(150, 75);

		assertEquals(3, summary.successCount());
		assertEquals(450, summary.inputTokens());
		assertEquals(225, summary.outputTokens());
	}

	@Test
	@DisplayName("shouldTrackFailure")
	void shouldTrackFailure() {
		final TranslationSummary summary = TranslationSummary.empty()
			.withFailure();

		assertEquals(0, summary.successCount());
		assertEquals(1, summary.failedCount());
		assertEquals(0, summary.inputTokens());
		assertEquals(0, summary.outputTokens());
	}

	@Test
	@DisplayName("shouldTrackSkipped")
	void shouldTrackSkipped() {
		final TranslationSummary summary = TranslationSummary.empty()
			.withSkipped();

		assertEquals(0, summary.successCount());
		assertEquals(0, summary.failedCount());
		assertEquals(1, summary.skippedCount());
	}

	@Test
	@DisplayName("shouldCalculateTotalCount")
	void shouldCalculateTotalCount() {
		final TranslationSummary summary = TranslationSummary.empty()
			.withSuccess(100, 50)
			.withSuccess(100, 50)
			.withFailure()
			.withSkipped()
			.withSkipped()
			.withSkipped();

		assertEquals(6, summary.getTotalCount());
		assertEquals(2, summary.successCount());
		assertEquals(1, summary.failedCount());
		assertEquals(3, summary.skippedCount());
	}

	@Test
	@DisplayName("shouldReportAllSuccessful")
	void shouldReportAllSuccessful() {
		final TranslationSummary allSuccess = TranslationSummary.empty()
			.withSuccess(100, 50)
			.withSkipped();

		assertTrue(allSuccess.isAllSuccessful());
		assertFalse(allSuccess.hasFailures());

		final TranslationSummary hasFailure = allSuccess.withFailure();

		assertFalse(hasFailure.isAllSuccessful());
		assertTrue(hasFailure.hasFailures());
	}

	@Test
	@DisplayName("shouldAddSummaries")
	void shouldAddSummaries() {
		final TranslationSummary first = new TranslationSummary(2, 1, 3, 1000, 500);
		final TranslationSummary second = new TranslationSummary(3, 2, 1, 2000, 1000);

		final TranslationSummary combined = first.add(second);

		assertEquals(5, combined.successCount());
		assertEquals(3, combined.failedCount());
		assertEquals(4, combined.skippedCount());
		assertEquals(3000, combined.inputTokens());
		assertEquals(1500, combined.outputTokens());
	}

	@Test
	@DisplayName("shouldFormatToString")
	void shouldFormatToString() {
		final TranslationSummary summary = new TranslationSummary(10, 2, 5, 5000, 2500);

		final String str = summary.toString();

		assertTrue(str.contains("success=10"));
		assertTrue(str.contains("failed=2"));
		assertTrue(str.contains("skipped=5"));
		assertTrue(str.contains("tokens=5000/2500"));
	}

	@Test
	@DisplayName("shouldBeImmutable")
	void shouldBeImmutable() {
		final TranslationSummary original = TranslationSummary.empty();
		final TranslationSummary withSuccess = original.withSuccess(100, 50);

		assertNotSame(original, withSuccess);
		assertEquals(0, original.successCount());
		assertEquals(1, withSuccess.successCount());
	}

	@Test
	@DisplayName("shouldHandleLargeTokenCounts")
	void shouldHandleLargeTokenCounts() {
		final long largeInput = 10_000_000L;
		final long largeOutput = 5_000_000L;

		final TranslationSummary summary = TranslationSummary.empty()
			.withSuccess(largeInput, largeOutput)
			.withSuccess(largeInput, largeOutput);

		assertEquals(20_000_000L, summary.inputTokens());
		assertEquals(10_000_000L, summary.outputTokens());
	}
}
