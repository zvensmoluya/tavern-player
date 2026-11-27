package io.github.zvensmoluya.tavernplayer.connections

/**
 * Renders an external provider error without interpreting it.
 *
 * [io.github.zvensmoluya.modelgateway.transport.GatewayTransport] already truncates the error body
 * and redacts credentials in it, so the remaining text is the only actionable part of the failure.
 * Classifying it into a friendlier phrase of our own would hide exactly what the provider said.
 */
internal fun providerHttpFailureText(status: Int, diagnostic: String): String {
    val detail = diagnostic.trim()
    if (detail.isEmpty()) return "模型服务返回 HTTP $status"
    val bounded = if (detail.length > PROVIDER_DETAIL_LIMIT) detail.take(PROVIDER_DETAIL_LIMIT) + "…" else detail
    return "模型服务返回 HTTP $status：$bounded"
}

internal fun credentialFailureText(httpStatus: Int?): String = when (httpStatus) {
    401 -> "服务未接受本次请求的身份验证（HTTP 401），请检查已保存的 API Key 和地址"
    403 -> "服务拒绝访问（HTTP 403），请检查访问权限或网络限制"
    else -> "无法读取本机保存的 API Key，请重试或重新保存"
}

private const val PROVIDER_DETAIL_LIMIT = 400
