package com.acme.b2b.infrastructure.sellfox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed HTTP access to the Sellfox Open API. The only class here that knows about
 * tokens, nonces or pagination.
 *
 * Auth is OAuth2 client_credentials with a ~24h token, and every business call is a POST
 * whose *query string* is signed — the JSON body takes no part in the signature. The
 * signed value is the sorted "k=v" pairs of a fixed set of parameters, joined by "&",
 * HMAC-SHA256'd with the client secret.
 */
@Component
class SellfoxApiClient(
    @Value("\${sellfox.base-url}") private val baseUrl: String,
    @Value("\${sellfox.client-id}") private val clientId: String,
    @Value("\${sellfox.client-secret}") private val clientSecret: String,
    @Value("\${sellfox.request-timeout-seconds:60}") requestTimeoutSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val random = SecureRandom()
    private val timeout = Duration.ofSeconds(requestTimeoutSeconds)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val token = AtomicReference<CachedToken?>()

    /**
     * POSTs a business endpoint and returns its `data` node.
     *
     * Sellfox answers HTTP 200 with a non-zero `code` for business failures, so the
     * status line alone never tells you whether a call worked.
     */
    fun post(path: String, body: Map<String, Any>): JsonNode {
        val response = send(path, body)
        val code = response.path("code").asInt(-1)
        if (code != SUCCESS_CODE) {
            throw SellfoxApiException(
                code = code,
                message = response.path("msg").asText("no message"),
                path = path,
            )
        }
        return response.path("data")
    }

    /**
     * Pages an endpoint to exhaustion, calling [extract] on each page's `data`.
     *
     * Paced deliberately: Sellfox rate-limits with code 40019, and a burst of pages is
     * the reliable way to trip it. A nightly catalog scan is ~65 pages, so the delay
     * costs about a minute and buys a job that finishes.
     */
    fun <T> pageThrough(
        path: String,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        body: (page: Int) -> Map<String, Any>,
        extract: (JsonNode) -> List<T>,
    ): List<T> {
        val collected = mutableListOf<T>()
        var page = 1
        while (true) {
            val data = post(path, body(page) + mapOf("pageNo" to "$page", "pageSize" to "$pageSize"))
            val rows = extract(data)
            collected += rows

            val totalPages = data.path("totalPage").asInt(0)
            if (page >= totalPages || rows.isEmpty()) break
            page++
            pace()
        }
        log.debug("{} returned {} rows over {} page(s)", path, collected.size, page)
        return collected
    }

    private fun send(path: String, body: Map<String, Any>): JsonNode {
        val accessToken = validToken()
        val timestamp = System.currentTimeMillis()
        val nonce = random.nextInt(1, 100_000)
        val query = mapOf(
            "access_token" to accessToken,
            "client_id" to clientId,
            "timestamp" to "$timestamp",
            "nonce" to "$nonce",
            "sign" to sign(path, accessToken, timestamp, nonce),
        )

        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path?${encode(query)}"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw SellfoxApiException(response.statusCode(), response.body().take(500), path)
        }
        return mapper.readTree(response.body())
    }

    /**
     * The signature covers the endpoint path exactly as it appears in the URL. Vendor
     * examples sometimes show an "/openapi" prefix that the real service rejects.
     */
    private fun sign(path: String, accessToken: String, timestamp: Long, nonce: Int): String {
        val payload = mapOf(
            "access_token" to accessToken,
            "client_id" to clientId,
            "method" to "post",
            "nonce" to "$nonce",
            "timestamp" to "$timestamp",
            "url" to path,
        ).toSortedMap()
            .map { (k, v) -> "$k=$v" }
            .joinToString("&")

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(clientSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Tokens are rate-limited, so one is held until shortly before it expires rather than
     * fetched per call.
     */
    private fun validToken(): String {
        token.get()?.takeIf { it.stillValid() }?.let { return it.value }

        val query = encode(
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "grant_type" to "client_credentials",
            )
        )
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$TOKEN_PATH?$query"))
            .timeout(timeout)
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = mapper.readTree(response.body())
        if (response.statusCode() != 200 || json.path("code").asInt(-1) != SUCCESS_CODE) {
            throw SellfoxApiException(
                json.path("code").asInt(response.statusCode()),
                json.path("msg").asText("token request failed"),
                TOKEN_PATH,
            )
        }

        val data = json.path("data")
        val fresh = CachedToken(
            value = data.path("access_token").asText(),
            // expires_in is milliseconds, not the seconds the OAuth spec would suggest.
            expiresAt = Instant.now().plusMillis(data.path("expires_in").asLong()),
        )
        token.set(fresh)
        return fresh.value
    }

    private fun pace() = Thread.sleep(PAGE_DELAY_MILLIS)

    private fun encode(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) ->
        "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
    }

    private data class CachedToken(val value: String, val expiresAt: Instant) {
        fun stillValid() = Instant.now().isBefore(expiresAt.minusSeconds(REFRESH_MARGIN_SECONDS))
    }

    private companion object {
        const val TOKEN_PATH = "/api/oauth/v2/token.json"
        const val SUCCESS_CODE = 0
        const val DEFAULT_PAGE_SIZE = 100
        const val PAGE_DELAY_MILLIS = 1_300L
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val REFRESH_MARGIN_SECONDS = 300L
    }
}

/**
 * Carries Sellfox's own code, which is what actually says what went wrong: 40005 is the
 * IP allowlist, 40019 is the rate limit, and both arrive as HTTP 200.
 */
class SellfoxApiException(val code: Int, message: String, val path: String) :
    RuntimeException("Sellfox $path failed with code $code: $message")
