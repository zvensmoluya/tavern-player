package io.github.zvensmoluya.modelgateway

import java.time.Duration

sealed class GatewayException(
    message: String,
    cause: Throwable? = null,
    open val diagnostic: String = message,
) : RuntimeException(message, cause) {
    class Configuration(message: String) : GatewayException(message)

    class Security(message: String) : GatewayException(message)

    class Authentication(message: String) : GatewayException(message)

    class Network(cause: Throwable) : GatewayException(
        message = "Network request failed",
        cause = cause,
        diagnostic = cause::class.simpleName ?: "network error",
    )

    open class HttpFailure(
        open val status: Int,
        open val requestId: String?,
        open val retryAfter: Duration?,
        override val diagnostic: String,
    ) : GatewayException(
        message = "HTTP $status",
        diagnostic = diagnostic,
    )

    class RateLimited(
        status: Int,
        requestId: String?,
        retryAfter: Duration?,
        diagnostic: String,
    ) : HttpFailure(status, requestId, retryAfter, diagnostic)

    class AuthenticationFailure(
        status: Int,
        requestId: String?,
        retryAfter: Duration?,
        diagnostic: String,
    ) : HttpFailure(status, requestId, retryAfter, diagnostic)

    class Protocol(message: String, cause: Throwable? = null) : GatewayException(message, cause)

    class ResponseTooLarge(val limitBytes: Long) : GatewayException(
        message = "Response exceeded the $limitBytes byte safety limit",
    )
}
