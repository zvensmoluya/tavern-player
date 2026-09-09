package io.github.zvensmoluya.tavernplayer.characters

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun interface CharacterImageFetcher {
    suspend fun fetch(uri: String, maxBytes: Int): ByteArray
}

/** Separate client: never inherits model credentials, cookies, or custom headers. */
internal class HttpCharacterImageFetcher(client: OkHttpClient = imageClient()) : CharacterImageFetcher {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun fetch(uri: String, maxBytes: Int): ByteArray {
        var url = checkedUrl(uri)
        repeat(4) { hop ->
            val result = request(url, maxBytes)
            result.bytes?.let { return it }
            require(hop < 3) { "图片重定向次数过多" }
            val next = url.resolve(requireNotNull(result.location) { "图片重定向缺少地址" }) ?: error("图片重定向地址无效")
            require(!url.isHttps || next.isHttps) { "图片重定向降低了连接安全级别" }
            url = checkedUrl(next.toString())
        }
        error("图片下载失败")
    }

    private data class Download(val bytes: ByteArray? = null, val location: String? = null)

    private suspend fun request(url: HttpUrl, maxBytes: Int): Download = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "image/png,image/jpeg,image/webp").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("图片下载失败，请检查网络后重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) Download(location = it.header("Location"))
                        else {
                            require(it.isSuccessful) { "图片服务器返回 HTTP ${it.code}" }
                            val body = it.body
                            require(body.contentLength() <= maxBytes) { "图片超过 8 MiB" }
                            val type = body.contentType()?.let { mime -> "${mime.type}/${mime.subtype}" }
                            require(type == null || type in setOf("image/png", "image/jpeg", "image/webp", "application/octet-stream")) { "服务器返回的内容不是受支持的图片" }
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (call.isCanceled()) throw IOException("图片下载已取消")
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size().toLong() + count <= maxBytes) { "图片超过 8 MiB" }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            Download(bytes = bytes.toByteArray())
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is IllegalArgumentException) error else IOException("图片下载中断，可重试"))
                }
            }
        })
    }

    companion object {
        private fun checkedUrl(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: error("图片地址无效")
            require(url.username.isEmpty() && url.password.isEmpty()) { "图片地址不能包含登录凭据" }
            return url
        }
        internal fun publicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            return if (bytes.size == 16) bytes[0] and 0xfe != 0xfc
            else bytes[0] != 0 && bytes[0] < 224 && !(bytes[0] == 100 && bytes[1] in 64..127) && !(bytes[0] == 198 && bytes[1] in 18..19)
        }
        private fun imageClient() = OkHttpClient.Builder()
            .dns { hostname ->
                Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || !addresses.all(::publicAddress)) throw java.net.UnknownHostException("图片地址必须指向公共网络")
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
