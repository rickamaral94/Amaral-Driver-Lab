package com.amaral.driverlab.report

import com.amaral.driverlab.stats.AbConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuePayloadTest {

    private val builder = ReportBuilder(appVersion = "1.0.0-test", clock = { 1_757_100_000_000 })
    private val config = AbConfig(bootstrapIterations = 500)

    private fun report(frameCount: Int = 60, runsPerArm: Int = 5) = builder.build(
        outcome = ReportFixtures.outcome(
            medianA = 15_000_000,
            medianB = 16_600_000,
            runsPerArm = runsPerArm,
            frameCount = frameCount,
        ),
        device = ReportFixtures.device,
        preflight = ReportFixtures.cleanPreflight(),
        session = ReportFixtures.session(),
        nullTestResult = ReportFixtures.passingNullTest(),
        config = config,
    )

    @Test
    fun `a small report goes inline in the issue body`() {
        val route = IssuePayload.route(report(frameCount = 30, runsPerArm = 2))
        assertTrue(route is IssuePayload.Route.InlineIssue)
        val inline = route as IssuePayload.Route.InlineIssue
        assertTrue(inline.body.contains("<details>"))
        assertTrue(inline.body.contains("\"schemaVersion\""))
    }

    /**
     * The raw series is the point of the payload, so it is never trimmed to fit —
     * a report too large for an issue body goes to a Gist whole.
     */
    @Test
    fun `a large report goes to a gist and the issue links to it`() {
        val route = IssuePayload.route(report(frameCount = 900, runsPerArm = 5))
        assertTrue("expected the gist route, got ${route::class.simpleName}", route is IssuePayload.Route.GistAndIssue)
        val gist = route as IssuePayload.Route.GistAndIssue

        assertTrue(gist.gistContent.contains("\"frametimesNs\""))
        assertTrue(gist.bodyTemplate.contains(IssuePayload.GIST_PLACEHOLDER))

        val body = gist.bodyWith("https://gist.github.com/example/abc123")
        assertTrue(body.contains("https://gist.github.com/example/abc123"))
        assertTrue("the placeholder must be gone once filled", !body.contains(IssuePayload.GIST_PLACEHOLDER))
    }

    @Test
    fun `the summary is readable without expanding anything`() {
        val markdown = IssuePayload.summaryMarkdown(report())
        assertTrue(markdown.contains("Odin2 Portal"))
        assertTrue(markdown.contains("Adreno (TM) 740"))
        assertTrue(markdown.contains("Null test"))
        assertTrue(markdown.contains("Comparisons"))
        assertTrue("the checksum is what identifies the build", markdown.contains("a".repeat(64)))
    }

    @Test
    fun `labels carry the gpu, the driver version and the schema`() {
        val labels = IssuePayload.labelsOf(report())
        assertTrue(labels.contains("benchmark-report"))
        assertTrue(labels.contains("schema/v${ReportSchema.VERSION}"))
        assertTrue(labels.contains("gpu/adreno-740"))
        assertTrue(labels.any { it.startsWith("driver/") })
    }

    @Test
    fun `a result that cannot be ranked is labelled so the pipeline can refuse it`() {
        val unranked = builder.build(
            ReportFixtures.outcome(medianA = 15_000_000, medianB = 16_600_000),
            ReportFixtures.device,
            ReportFixtures.cleanPreflight(),
            ReportFixtures.session(),
            nullTestResult = null,
            config = config,
        )
        assertTrue(IssuePayload.labelsOf(unranked).contains("unranked/null-test"))
    }

    @Test
    fun `the title names the device and both drivers`() {
        val title = IssuePayload.titleOf(report())
        assertTrue(title.contains("Odin2 Portal"))
        assertTrue(title.contains("Turnip v3 vs Turnip v4"))
    }

    /**
     * The deep link carries only the summary, so it stays small even when the raw
     * series would not fit anywhere near a URL. What grows it is the per-execution
     * detail table, which is why the limit is checked against a long protocol
     * rather than against a long workload.
     */
    @Test
    fun `a deep link is offered while the summary fits in a URL`() {
        val small = IssuePayload.deepLink(report(frameCount = 900, runsPerArm = 5))
        assertNotNull("ten executions should still fit in a link", small)
        assertTrue(small!!.startsWith("https://github.com/${IssuePayload.REPOSITORY}/issues/new"))
        assertTrue(small.toByteArray(Charsets.UTF_8).size <= IssuePayload.MAXIMUM_DEEP_LINK_BYTES)
    }

    @Test
    fun `a summary too large for a URL withdraws the deep link rather than truncating it`() {
        // Silently truncating loses the end of the report, and the user would have no
        // way to tell. The manual export takes over instead.
        assertNull(IssuePayload.deepLink(report(frameCount = 30, runsPerArm = 60)))
    }

    @Test
    fun `the inline threshold is measured in bytes, not characters`() {
        // Accents in a device name are multi-byte; a character count would let a
        // payload past the limit that the API then rejects.
        val json = ReportJson.encode(report(frameCount = 30, runsPerArm = 2))
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= IssuePayload.MAXIMUM_INLINE_BYTES)
    }
}
