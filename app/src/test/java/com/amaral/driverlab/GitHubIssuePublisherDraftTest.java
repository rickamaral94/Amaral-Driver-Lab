package com.amaral.driverlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GitHubIssuePublisherDraftTest {
    @Test
    public void shortDraftRemainsInsideBrowserUrl() {
        assertFalse(GitHubIssuePublisher.requiresClipboardFallback(
                "rickamaral94",
                "Amaral-Driver-Lab",
                "[Turnip Validation] short",
                "## Result\n\nSmall report."));
    }

    @Test
    public void richQualificationReportUsesClipboardFallback() {
        String stage = "### Stage\n\n| Metric | Reference | Candidate | Delta |\n"
                + "|---|---:|---:|---:|\n| P99 | 10.25 ms | 9.75 ms | +4.88% |\n\n";
        String body = stage.repeat(120);

        assertTrue(GitHubIssuePublisher.requiresClipboardFallback(
                "rickamaral94",
                "Amaral-Driver-Lab",
                "[Turnip Validation] CANDIDATO alpha7 vs REFERÊNCIA alpha6",
                body));
    }

    @Test
    public void encodedUnicodeAndMarkdownAreCounted() {
        String body = "Comparação CANDIDATO × REFERÊNCIA — métricas, confiança e validação. ".repeat(80);

        assertTrue(GitHubIssuePublisher.estimatedDraftUrlLength(
                "rickamaral94", "Amaral-Driver-Lab", "Validação", body) > body.length());
    }
}
