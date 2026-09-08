package com.amaral.driverlab.report

import kotlin.math.abs

/**
 * How a report becomes a GitHub issue.
 *
 * Section 10 asks for three routes in order of preference: the authenticated API,
 * a pre-filled deep link, and a manual export. The API route splits again once the
 * payload grows past what belongs in an issue body, and that threshold is here
 * rather than in the network code so it can be tested without a network.
 */
public object IssuePayload {

    /** Past this, the JSON goes to a Gist and the issue links to it. */
    public const val MAXIMUM_INLINE_BYTES: Int = 60 * 1024

    /**
     * Practical ceiling for a URL. Browsers and servers both give up somewhere
     * above this, and a truncated body silently loses the end of the report.
     */
    public const val MAXIMUM_DEEP_LINK_BYTES: Int = 8 * 1024

    public const val REPOSITORY: String = "rickamaral94/Amaral-Driver-Lab"

    public sealed interface Route {
        /** JSON small enough to sit inside the issue body. */
        public data class InlineIssue(val title: String, val body: String, val labels: List<String>) : Route

        /** JSON goes to a Gist first; the issue body carries the summary and the link. */
        public data class GistAndIssue(
            val title: String,
            val gistFilename: String,
            val gistContent: String,
            val bodyTemplate: String,
            val labels: List<String>,
        ) : Route {
            /** The Gist URL is only known after it is created, hence the template. */
            public fun bodyWith(gistUrl: String): String = bodyTemplate.replace(GIST_PLACEHOLDER, gistUrl)
        }
    }

    public const val GIST_PLACEHOLDER: String = "{{GIST_URL}}"

    public fun route(report: BenchmarkReport): Route {
        val json = ReportJson.encode(report)
        val title = titleOf(report)
        val labels = labelsOf(report)

        return if (json.toByteArray(Charsets.UTF_8).size <= MAXIMUM_INLINE_BYTES) {
            Route.InlineIssue(title, inlineBody(report, json), labels)
        } else {
            Route.GistAndIssue(
                title = title,
                gistFilename = "amaral-driver-lab-${report.session.id}.json",
                gistContent = ReportJson.encodePretty(report),
                bodyTemplate = linkedBody(report),
                labels = labels,
            )
        }
    }

    public fun titleOf(report: BenchmarkReport): String {
        val device = report.device.model.ifBlank { report.device.device }
        val drivers = report.drivers.joinToString(" vs ") { it.label }
        return "[$device] ${drivers.ifBlank { "benchmark" }}"
    }

    public fun labelsOf(report: BenchmarkReport): List<String> = buildList {
        add("benchmark-report")
        add("schema/v${report.schemaVersion}")

        report.drivers.firstOrNull()?.identity?.deviceName
            ?.lowercase()
            ?.let { name ->
                // "Adreno (TM) 740" becomes "gpu/adreno-740".
                val slug = Regex("[^a-z0-9]+").replace(name.replace("(tm)", " "), "-").trim('-')
                if (slug.isNotBlank()) add("gpu/$slug")
            }

        report.drivers
            .firstOrNull { !it.systemDriver }
            ?.identity
            ?.driverVersionString()
            ?.takeIf { it.isNotBlank() }
            ?.let { add("driver/$it") }

        if (report.nullTest?.passed != true) add("unranked/null-test")
        if (report.preflight.lowConfidence) add("unranked/preflight-overridden")
        if (report.drivers.any { it.unverified }) add("unverified-build")
    }

    /** The human-readable half. Deliberately readable without expanding anything. */
    public fun summaryMarkdown(report: BenchmarkReport): String = buildString {
        appendLine("## ${report.plainVerdict}")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Device | ${report.device.manufacturer} ${report.device.model} (${report.device.soc}) |")
        appendLine("| Android | ${report.device.androidRelease} (API ${report.device.sdkInt}) |")
        for (driver in report.drivers) {
            val verified = if (driver.unverified) "unverified build" else "known build"
            appendLine("| Driver ${driver.arm} | ${driver.label} — ${driver.identity.summary()} ($verified) |")
            appendLine("| Driver ${driver.arm} SHA-256 | `${driver.libraryChecksum.ifBlank { "system driver" }}` |")
        }
        val nullTest = report.nullTest
        appendLine(
            "| Null test | " + when {
                nullTest == null -> "not run — **this result cannot be ranked**"
                nullTest.passed -> "passed, ${"%.1f".format(nullTest.appliedNoiseFloor * 100)}% noise floor"
                else -> "**failed** — ${nullTest.explanation}"
            } + " |",
        )
        appendLine("| Preflight | ${if (report.preflight.lowConfidence) "**overridden**" else "clean"} |")
        appendLine()

        if (report.comparisons.isNotEmpty()) {
            appendLine("### Comparisons")
            appendLine()
            appendLine("| Workload | Verdict | Difference | 95% interval | p | Cliff's δ | Runs |")
            appendLine("|---|---|---|---|---|---|---|")
            for (c in report.comparisons) {
                val difference = "%+.1f%%".format(c.percentDifference)
                val interval = "%.3f – %.3f".format(c.speedupInterval.low, c.speedupInterval.high)
                val p = if (c.pValue < 0.0001) "<0.0001" else "%.4f".format(c.pValue)
                appendLine(
                    "| ${c.workloadId} | ${c.verdict} (${c.reason}) | $difference | $interval | " +
                        "$p | ${"%.3f".format(c.cliffsDelta)} | ${c.runsA} vs ${c.runsB} |",
                )
            }
            appendLine()
        }

        if (report.failures.isNotEmpty()) {
            appendLine("### Failures")
            appendLine()
            for (failure in report.failures) {
                appendLine("- `${failure.stage}` on ${failure.workloadId} (${failure.label}): ${failure.message}")
            }
            appendLine()
        }

        appendLine("### Frametime detail")
        appendLine()
        appendLine("| Workload | Arm | Median | p99 (1% low) | p99.9 | Stutters | Degradation |")
        appendLine("|---|---|---|---|---|---|---|")
        for (execution in report.executions) {
            val s = execution.summary
            appendLine(
                "| ${execution.workload.workloadId} | ${execution.arm}#${execution.runIndexWithinArm} | " +
                    "${ms(s.medianNs)} | ${ms(s.p99Ns)} | ${ms(s.p999Ns)} | ${s.stutterCount} | " +
                    "${"%+.1f%%".format(s.thermalDegradation * 100)} |",
            )
        }
    }

    private fun ms(nanos: Double): String = "%.3f ms".format(nanos / 1_000_000.0)

    private fun inlineBody(report: BenchmarkReport, json: String): String = buildString {
        append(summaryMarkdown(report))
        appendLine()
        appendLine("<details><summary>Full result JSON (schema v${report.schemaVersion})</summary>")
        appendLine()
        appendLine("```json")
        appendLine(json)
        appendLine("```")
        appendLine()
        appendLine("</details>")
    }

    private fun linkedBody(report: BenchmarkReport): String = buildString {
        append(summaryMarkdown(report))
        appendLine()
        appendLine(
            "The full result is ${ReportJson.encode(report).length / 1024} KB, past what fits in an " +
                "issue body, so the raw frametime series live here: $GIST_PLACEHOLDER",
        )
    }

    /**
     * Route 2 from section 10. Returns null when the summary alone would still blow
     * the URL limit — a truncated deep link loses the end of the body silently, and
     * a manual export is better than a report that is quietly missing its tail.
     */
    public fun deepLink(report: BenchmarkReport): String? {
        val body = summaryMarkdown(report) +
            "\n\n_The full JSON did not fit in this link. Attach the exported file to this issue._\n"
        val url = buildString {
            append("https://github.com/$REPOSITORY/issues/new")
            append("?template=benchmark-report.yml")
            append("&title=").append(encode(titleOf(report)))
            append("&labels=").append(encode(labelsOf(report).joinToString(",")))
            append("&summary=").append(encode(body))
        }
        return url.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAXIMUM_DEEP_LINK_BYTES }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** Largest absolute difference in the report, used to headline a list entry. */
    public fun headlineDifference(report: BenchmarkReport): Double =
        report.comparisons.maxOfOrNull { abs(it.percentDifference) } ?: 0.0
}
