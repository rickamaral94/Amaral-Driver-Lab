package com.amaral.driverlab.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes a report to GitHub.
 *
 * Authentication is the OAuth Device Flow: the app shows a code, the user enters it
 * in a browser, and the app polls for a token. That avoids shipping a client secret
 * in an APK, where it would not be secret.
 *
 * Nothing here runs without the user asking. There is no automatic telemetry, and
 * the preview screen shows the exact payload before this is called.
 */
public class GitHubPublisher(
    private val clientId: String,
    private val tokenStore: SecureTokenStore,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    public sealed interface PublishResult {
        public data class Published(val issueUrl: String, val gistUrl: String?) : PublishResult
        public data class Failed(val message: String) : PublishResult
        public data object NotAuthenticated : PublishResult
    }

    public suspend fun publish(report: BenchmarkReport): PublishResult = withContext(Dispatchers.IO) {
        val token = tokenStore.token() ?: return@withContext PublishResult.NotAuthenticated
        try {
            when (val route = IssuePayload.route(report)) {
                is IssuePayload.Route.InlineIssue ->
                    PublishResult.Published(createIssue(token, route.title, route.body, route.labels), null)

                is IssuePayload.Route.GistAndIssue -> {
                    val gistUrl = createGist(token, route.gistFilename, route.gistContent, route.title)
                    val issueUrl =
                        createIssue(token, route.title, route.bodyWith(gistUrl), route.labels)
                    PublishResult.Published(issueUrl, gistUrl)
                }
            }
        } catch (e: IOException) {
            PublishResult.Failed(e.message ?: "the request failed")
        }
    }

    private fun createIssue(token: String, title: String, body: String, labels: List<String>): String {
        val payload = json.encodeToString(
            IssueRequest.serializer(),
            IssueRequest(title = title, body = body, labels = labels),
        )
        val response = http.post(
            url = "https://api.github.com/repos/${IssuePayload.REPOSITORY}/issues",
            body = payload,
            headers = authHeaders(token),
        )
        if (response.code !in 200..299) {
            throw IOException("GitHub refused the issue (HTTP ${response.code}): ${response.body.take(300)}")
        }
        return json.decodeFromString(IssueResponse.serializer(), response.body).htmlUrl
    }

    private fun createGist(token: String, filename: String, content: String, description: String): String {
        val payload = json.encodeToString(
            GistRequest.serializer(),
            GistRequest(
                description = description,
                public = true,
                files = mapOf(filename to GistFile(content)),
            ),
        )
        val response = http.post("https://api.github.com/gists", payload, authHeaders(token))
        if (response.code !in 200..299) {
            throw IOException("GitHub refused the gist (HTTP ${response.code}): ${response.body.take(300)}")
        }
        return json.decodeFromString(GistResponse.serializer(), response.body).htmlUrl
    }

    private fun authHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "Content-Type" to "application/json",
    )

    // ---- Device Flow -------------------------------------------------------

    public data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    public suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        val response = http.post(
            url = "https://github.com/login/device/code",
            body = "client_id=$clientId&scope=public_repo%20gist",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )
        if (response.code !in 200..299) {
            throw IOException("GitHub refused the device code request (HTTP ${response.code})")
        }
        val parsed = json.decodeFromString(DeviceCodeResponse.serializer(), response.body)
        DeviceCode(
            deviceCode = parsed.deviceCode,
            userCode = parsed.userCode,
            verificationUri = parsed.verificationUri,
            intervalSeconds = parsed.interval,
            expiresInSeconds = parsed.expiresIn,
        )
    }

    /**
     * One poll of the token endpoint.
     *
     * Returns null while the user has not finished in the browser. The caller waits
     * [DeviceCode.intervalSeconds] between calls; polling faster earns a
     * `slow_down` from GitHub and makes the wait longer, not shorter.
     */
    public suspend fun pollForToken(deviceCode: String): String? = withContext(Dispatchers.IO) {
        val response = http.post(
            url = "https://github.com/login/oauth/access_token",
            body = "client_id=$clientId&device_code=$deviceCode" +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )
        val parsed = json.decodeFromString(TokenResponse.serializer(), response.body)
        when {
            parsed.accessToken != null -> parsed.accessToken.also { tokenStore.store(it) }
            parsed.error == "authorization_pending" || parsed.error == "slow_down" -> null
            parsed.error != null -> throw IOException("GitHub returned ${parsed.error}")
            else -> null
        }
    }
}

// ---- HTTP boundary, so publishing can be tested without a network ----------

public data class HttpResponse(val code: Int, val body: String)

public interface HttpClient {
    public fun post(url: String, body: String, headers: Map<String, String>): HttpResponse
}

internal class UrlConnectionHttpClient : HttpClient {
    override fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class IssueRequest(val title: String, val body: String, val labels: List<String>)

@Serializable
private data class IssueResponse(@SerialName("html_url") val htmlUrl: String)

@Serializable
private data class GistRequest(
    val description: String,
    val public: Boolean,
    val files: Map<String, GistFile>,
)

@Serializable
private data class GistFile(val content: String)

@Serializable
private data class GistResponse(@SerialName("html_url") val htmlUrl: String)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int = 900,
    val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
)
