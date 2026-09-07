package com.amaral.driverlab.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public object ReportJson {

    /**
     * Encoding defaults matter here because this output is a wire format the
     * ingestion Action validates. Defaults are written out explicitly so a reader
     * never has to know what this app's defaults happened to be on the day.
     */
    public val strict: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /** For reading files this app wrote earlier, where an unknown field means a newer app. */
    public val lenient: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    public val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    public fun encode(report: BenchmarkReport): String =
        strict.encodeToString(BenchmarkReport.serializer(), report)

    public fun encodePretty(report: BenchmarkReport): String =
        pretty.encodeToString(BenchmarkReport.serializer(), report)

    /**
     * @throws SchemaVersionException when the payload was written by a version this
     *   build does not understand. Guessing at an unknown schema is how a leaderboard
     *   fills up with entries whose numbers mean something else.
     */
    public fun decode(text: String): BenchmarkReport {
        val version = runCatching {
            lenient.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: throw SchemaVersionException("the payload has no readable schemaVersion")

        if (version > ReportSchema.VERSION) {
            throw SchemaVersionException(
                "this report is schema version $version and this app understands up to " +
                    "${ReportSchema.VERSION}. Update the app rather than reading it partially.",
            )
        }
        return lenient.decodeFromString(BenchmarkReport.serializer(), text)
    }

    public fun encode(record: NullTestRecord): String =
        pretty.encodeToString(NullTestRecord.serializer(), record)

    /** Reads a stored null test record. Lenient, because this file is only ever ours. */
    public fun decodeNullTestRecord(text: String): NullTestRecord =
        lenient.decodeFromString(NullTestRecord.serializer(), text)
}

public class SchemaVersionException(message: String) : Exception(message)
