package io.github.zvensmoluya.tavernplayer.transfer

import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class ShelfTransferManifest(
    val protocol: String,
    val version: Int,
    val kind: String,
    val subtype: String? = null,
    val name: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    val mediaType: String,
    val sourceUrl: String,
    val expiresAt: String,
)

data class ShelfTransfer(
    val manifest: ShelfTransferManifest,
    val sourceBytes: ByteArray,
)

fun interface ShelfTransferReceiver {
    suspend fun receive(transferUrl: String): ShelfTransfer
}

class ShelfTransferClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : ShelfTransferReceiver {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun receive(transferUrl: String): ShelfTransfer = withContext(Dispatchers.IO) {
        val manifestBytes = get(transferUrl.trim(), MAX_MANIFEST_BYTES, "无法读取 Shelf 传输信息")
        val manifest = runCatching {
            json.decodeFromString<ShelfTransferManifest>(manifestBytes.decodeToString())
        }.getOrElse { throw ShelfTransferException("Shelf 传输信息格式无效", it) }
        validate(manifest, transferUrl.trim())

        val source = get(manifest.sourceUrl, MAX_SOURCE_BYTES, "无法下载 Shelf 资源")
        if (source.size.toLong() != manifest.size) {
            throw ShelfTransferException("Shelf 资源大小与传输信息不一致")
        }
        val actualHash = source.sha256()
        if (!actualHash.equals(manifest.sha256, ignoreCase = true)) {
            throw ShelfTransferException("Shelf 资源完整性校验失败")
        }
        ShelfTransfer(manifest, source)
    }

    private fun validate(manifest: ShelfTransferManifest, transferUrl: String) {
        if (manifest.protocol != PROTOCOL || manifest.version != VERSION) {
            throw ShelfTransferException("不支持的 Shelf 传输协议 ${manifest.protocol} v${manifest.version}")
        }
        if (manifest.kind.isBlank() || manifest.filename.isBlank() || manifest.sourceUrl.isBlank()) {
            throw ShelfTransferException("Shelf 传输信息不完整")
        }
        if (manifest.size !in 0..MAX_SOURCE_BYTES.toLong()) {
            throw ShelfTransferException("Shelf 资源超过 32 MiB 导入上限")
        }
        if (!SHA256.matches(manifest.sha256)) {
            throw ShelfTransferException("Shelf 传输信息中的 SHA-256 无效")
        }
        validateResourceUrl(manifest.sourceUrl, transferUrl)
    }

    private fun validateResourceUrl(resourceUrl: String, transferUrl: String) {
        val transfer = runCatching { URI(transferUrl) }.getOrNull()
        val resource = runCatching { URI(resourceUrl) }.getOrNull()
        val valid = transfer != null && resource != null &&
            transfer.isAbsolute && resource.isAbsolute &&
            (transfer.scheme.equals("http", ignoreCase = true) || transfer.scheme.equals("https", ignoreCase = true)) &&
            transfer.scheme.equals(resource.scheme, ignoreCase = true) &&
            transfer.host.equals(resource.host, ignoreCase = true) &&
            effectivePort(transfer) == effectivePort(resource) &&
            resource.userInfo == null && resource.fragment == null
        if (!valid) throw ShelfTransferException("Shelf 下载地址与传输来源不一致")
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun get(url: String, limit: Int, failureMessage: String): ByteArray {
        val request = runCatching { Request.Builder().url(url).get().build() }
            .getOrElse { throw ShelfTransferException("Shelf 传输地址无效", it) }
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ShelfTransferException("$failureMessage（HTTP ${response.code}）")
                }
                val body = response.body
                if (body.contentLength() > limit) throw ShelfTransferException("Shelf 响应超过允许大小")
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limit) throw ShelfTransferException("Shelf 响应超过允许大小")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (error: ShelfTransferException) {
            throw error
        } catch (error: Exception) {
            throw ShelfTransferException(failureMessage, error)
        }
    }

    private companion object {
        const val PROTOCOL = "tavern-shelf-transfer"
        const val VERSION = 1
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

class ShelfTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)
