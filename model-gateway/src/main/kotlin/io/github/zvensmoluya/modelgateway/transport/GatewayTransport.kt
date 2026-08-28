package io.github.zvensmoluya.modelgateway.transport

import io.github.zvensmoluya.modelgateway.AuthScheme
import io.github.zvensmoluya.modelgateway.ConnectionTarget
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.EndpointRules
import io.github.zvensmoluya.modelgateway.GatewayException
import io.github.zvensmoluya.modelgateway.SecretValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

data class SseFrame(
    val event: String?,
    val data: String,
    val id: String?,
    val retryMillis: Long?,
)

class GatewayTransport(
    private val credentialResolver: CredentialResolver,
    client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun postSse(
        target: ConnectionTarget,
        url: HttpUrl,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): Flow<SseFrame> = channelFlow {
        target.validate()
        val credential = resolveAndAuthorize(target, url)
        val callRef = AtomicReference<Call?>()
        val requestJob = currentCoroutineContext().job
        requestJob.invokeOnCompletion { callRef.get()?.cancel() }
        withContext(Dispatchers.IO) {
            val request = buildRequest(
                target = target,
                url = url,
                credential = credential,
                headers = headers,
                method = "POST",
                jsonBody = jsonBody,
            )
            val response = executeFollowingRedirects(request, target, credential, callRef, requestJob)
            response.use {
                requireSuccess(it, credential)
                val contentType = it.header("Content-Type").orEmpty()
                if (!contentType.startsWith("text/event-stream", ignoreCase = true)) {
                    throw GatewayException.Protocol("Expected text/event-stream but received ${contentType.ifBlank { "no content type" }}")
                }
                val source = LimitedSource(it.body.source(), maxResponseBytes).buffer()
                val parser = SseParser { frame -> send(frame) }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        parser.accept(line)
                    }
                    parser.finish()
                } catch (error: LimitExceededException) {
                    throw GatewayException.ResponseTooLarge(maxResponseBytes)
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw GatewayException.Network(error)
                }
            }
        }
    }

    suspend fun getJson(
        target: ConnectionTarget,
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
    ): String {
        target.validate()
        val credential = resolveAndAuthorize(target, url)
        return withContext(Dispatchers.IO) {
            val callRef = AtomicReference<Call?>()
            val requestJob = currentCoroutineContext().job
            requestJob.invokeOnCompletion { callRef.get()?.cancel() }
            val request = buildRequest(target, url, credential, headers, method = "GET", jsonBody = null)
            val response = executeFollowingRedirects(request, target, credential, callRef, requestJob)
            response.use {
                requireSuccess(it, credential)
                try {
                    LimitedSource(it.body.source(), maxResponseBytes).buffer().readUtf8()
                } catch (error: LimitExceededException) {
                    throw GatewayException.ResponseTooLarge(maxResponseBytes)
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw GatewayException.Network(error)
                }
            }
        }
    }

    private suspend fun resolveAndAuthorize(target: ConnectionTarget, url: HttpUrl): SecretValue? {
        if (target.authScheme == AuthScheme.NONE) return null
        val origin = EndpointRules.origin(url)
        if (origin !in target.authorizedOrigins) {
            throw GatewayException.Security("Credential use is not approved for origin $origin")
        }
        val ref = target.credentialRef
            ?: throw GatewayException.Authentication("Credential reference is missing")
        return try {
            credentialResolver.resolve(ref)
                ?: throw GatewayException.Authentication("Credential $ref is unavailable")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GatewayException.Authentication) {
            throw error
        } catch (_: Exception) {
            throw GatewayException.Authentication("Credential $ref cannot be read")
        }
    }

    private fun buildRequest(
        target: ConnectionTarget,
        url: HttpUrl,
        credential: SecretValue?,
        headers: Map<String, String>,
        method: String,
        jsonBody: String?,
    ): Request {
        val authenticatedUrl = if (target.authScheme == AuthScheme.QUERY_KEY) {
            url.newBuilder().removeAllQueryParameters("key")
                .addQueryParameter("key", requireNotNull(credential).reveal)
                .build()
        } else {
            url
        }
        val builder = Request.Builder()
            .url(authenticatedUrl)
            .header("Accept", if (method == "POST") "text/event-stream" else "application/json")
        headers.forEach(builder::header)
        when (target.authScheme) {
            AuthScheme.BEARER -> builder.header("Authorization", "Bearer ${requireNotNull(credential).reveal}")
            AuthScheme.X_API_KEY -> builder.header("x-api-key", requireNotNull(credential).reveal)
            AuthScheme.X_GOOG_API_KEY -> builder.header("x-goog-api-key", requireNotNull(credential).reveal)
            AuthScheme.QUERY_KEY, AuthScheme.NONE -> Unit
        }
        if (method == "POST") {
            builder.post(requireNotNull(jsonBody).toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        return builder.build()
    }

    private fun executeFollowingRedirects(
        initial: Request,
        target: ConnectionTarget,
        credential: SecretValue?,
        callRef: AtomicReference<Call?>,
        requestJob: kotlinx.coroutines.Job,
    ): Response {
        var request = initial
        var redirects = 0
        while (true) {
            val call = client.newCall(request)
            callRef.set(call)
            val response = try {
                call.execute()
            } catch (error: IOException) {
                requestJob.ensureActive()
                throw GatewayException.Network(error)
            }
            if (response.code !in REDIRECT_CODES) return response
            val location = response.header("Location")
            if (location == null) return response
            if (redirects >= MAX_REDIRECTS) {
                response.close()
                throw GatewayException.Security("Redirect limit exceeded")
            }
            val nextUrl = request.url.resolve(location)
                ?: run {
                    response.close()
                    throw GatewayException.Protocol("Invalid redirect location")
                }
            if (EndpointRules.origin(nextUrl) != EndpointRules.origin(request.url)) {
                response.close()
                throw GatewayException.Security("Cross-origin redirect rejected")
            }
            response.close()
            redirects += 1
            val preserveBody = response.code == 307 || response.code == 308
            request = buildRequest(
                target = target,
                url = nextUrl,
                credential = credential,
                headers = request.headers.toMap().filterKeys {
                    !it.equals("authorization", true) &&
                        !it.equals("x-api-key", true) &&
                        !it.equals("x-goog-api-key", true) &&
                        !it.equals("content-length", true) &&
                        !it.equals("content-type", true)
                },
                method = if (preserveBody) request.method else "GET",
                jsonBody = if (preserveBody) request.body?.let { body ->
                    Buffer().also(body::writeTo).readUtf8()
                } else null,
            )
        }
    }

    private fun requireSuccess(response: Response, credential: SecretValue?) {
        if (response.isSuccessful) return
        val diagnostic = runCatching {
            LimitedSource(response.body.source(), min(maxResponseBytes, ERROR_DIAGNOSTIC_BYTES)).buffer()
                .readUtf8()
                .take(ERROR_DIAGNOSTIC_CHARS)
                .replace(SECRET_PATTERN, "$1[redacted]")
                .let { text -> credential?.reveal?.let { text.replace(it, "[redacted]") } ?: text }
        }.getOrElse { "HTTP error body unavailable" }
        val requestId = response.header("x-request-id")
            ?: response.header("request-id")
            ?: response.header("anthropic-request-id")
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.let(Duration::ofSeconds)
        if (response.code == 429) {
            throw GatewayException.RateLimited(response.code, requestId, retryAfter, diagnostic)
        }
        if (response.code == 401 || response.code == 403) {
            throw GatewayException.AuthenticationFailure(response.code, requestId, retryAfter, diagnostic)
        }
        throw GatewayException.HttpFailure(response.code, requestId, retryAfter, diagnostic)
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024L * 1024L
        private const val MAX_REDIRECTS = 3
        private const val ERROR_DIAGNOSTIC_BYTES = 16L * 1024L
        private const val ERROR_DIAGNOSTIC_CHARS = 4096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SECRET_PATTERN = Regex("(?i)(api[_-]?key|token|authorization|key)[\\\"'\\s:=]+[^,}\\s\\\"]+")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.MINUTES)
            .writeTimeout(6, TimeUnit.MINUTES)
            .callTimeout(8, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private class SseParser(
    private val emit: suspend (SseFrame) -> Unit,
) {
    private var event: String? = null
    private var id: String? = null
    private var retryMillis: Long? = null
    private val data = mutableListOf<String>()

    suspend fun accept(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(':')) return
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        val rawValue = if (separator < 0) "" else line.substring(separator + 1)
        val value = rawValue.removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> data += value
            "id" -> if ('\u0000' !in value) id = value
            "retry" -> retryMillis = value.toLongOrNull()?.takeIf { it >= 0 }
        }
    }

    suspend fun finish() = dispatch()

    private suspend fun dispatch() {
        if (data.isNotEmpty()) {
            emit(SseFrame(event = event, data = data.joinToString("\n"), id = id, retryMillis = retryMillis))
        }
        event = null
        data.clear()
    }
}

private class LimitExceededException : IOException()

private class LimitedSource(
    delegate: Source,
    private val limit: Long,
) : ForwardingSource(delegate) {
    private var received = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val remaining = limit - received
        if (remaining < 0) throw LimitExceededException()
        val read = super.read(sink, min(byteCount, remaining + 1))
        if (read > 0) {
            received += read
            if (received > limit) throw LimitExceededException()
        }
        return read
    }
}
