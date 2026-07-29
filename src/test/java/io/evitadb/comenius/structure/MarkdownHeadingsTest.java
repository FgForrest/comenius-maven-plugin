package io.evitadb.comenius.structure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("MarkdownHeadings should report the headings a reader sees")
class MarkdownHeadingsTest {

	@Test
	@DisplayName("shouldReportLevelsInDocumentOrder")
	void shouldReportLevelsInDocumentOrder() {
		final String body = """
			## Label

			Some text.

			### Cardinality

			More text.

			#### Detail
			""";

		assertEquals(List.of(2, 3, 4), MarkdownHeadings.levels(body));
	}

	@Test
	@DisplayName("shouldIgnoreShellCommentsInsideFences")
	void shouldIgnoreShellCommentsInsideFences() {
		// a shell comment is indistinguishable from a heading to a plain regex, and these documents
		// are full of them - counting one as a heading would make two identical documents disagree
		final String body = """
			## Run

			```shell
			# run in foreground
			docker run evitadb
			```

			### Afterwards
			""";

		assertEquals(List.of(2, 3), MarkdownHeadings.levels(body));
	}

	@Test
	@DisplayName("shouldReportTheSameLevelsForADocumentAndItsTranslation")
	void shouldReportTheSameLevelsForADocumentAndItsTranslation() {
		final String english = "## Label\n\nLabels tag a query.\n\n### Cardinality\n\nBounded.\n";
		final String czech = "## Stitek\n\nStitky oznacuji dotaz.\n\n### Kardinalita\n\nOmezena.\n";

		assertEquals(MarkdownHeadings.levels(english), MarkdownHeadings.levels(czech));
	}

	@Test
	@DisplayName("shouldReportNoLevelsForABodyWithoutHeadings")
	void shouldReportNoLevelsForABodyWithoutHeadings() {
		assertEquals(List.of(), MarkdownHeadings.levels("Just a paragraph.\n\nAnd another.\n"));
	}
}
