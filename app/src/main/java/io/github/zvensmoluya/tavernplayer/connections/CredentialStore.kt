package io.github.zvensmoluya.tavernplayer.connections

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.zvensmoluya.modelgateway.CredentialResolver
import io.github.zvensmoluya.modelgateway.SecretValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    suspend fun put(credentialId: String, secret: String)
    suspend fun getOrNull(credentialId: String): String?
    suspend fun delete(credentialId: String)
    suspend fun contains(credentialId: String): Boolean
}

class CredentialUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class AndroidKeystoreCredentialStore(context: Context) : CredentialStore, CredentialResolver {
    private val directory = File(context.noBackupFilesDir, "model_gateway/credentials").apply(File::mkdirs)
    private val mutex = Mutex()

    override suspend fun put(credentialId: String, secret: String) {
        require(secret.isNotBlank()) { "Credential must not be blank" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = requireSafeId(credentialId)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, masterKey())
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
                val bytes = ByteArrayOutputStream().use { buffer ->
                    DataOutputStream(buffer).use { output ->
                        output.write(MAGIC)
                        output.writeByte(cipher.iv.size)
                        output.write(cipher.iv)
                        output.writeInt(encrypted.size)
                        output.write(encrypted)
                    }
                    buffer.toByteArray()
                }
                val atomicFile = AtomicFile(file(id))
                val output = atomicFile.startWrite()
                try {
                    output.write(bytes)
                    atomicFile.finishWrite(output)
                } catch (error: Throwable) {
                    atomicFile.failWrite(output)
                    throw error
                }
            }
        }
    }

    override suspend fun getOrNull(credentialId: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val id = requireSafeId(credentialId)
                val stored = file(id)
                if (!stored.isFile) return@withLock null
                if (stored.length() > MAX_CREDENTIAL_FILE_BYTES) {
                    throw CredentialUnavailableException("Credential is unavailable")
                }
                try {
                    DataInputStream(stored.inputStream().buffered()).use { input ->
                        val magic = ByteArray(MAGIC.size).also(input::readFully)
                        if (!magic.contentEquals(MAGIC)) throw IOException("Unknown credential format")
                        val nonceLength = input.readUnsignedByte()
                        if (nonceLength !in 12..16) throw IOException("Invalid credential nonce")
                        val nonce = ByteArray(nonceLength).also(input::readFully)
                        val encryptedLength = input.readInt()
                        if (encryptedLength !in 17..MAX_CREDENTIAL_FILE_BYTES) {
                            throw IOException("Invalid credential payload")
                        }
                        val encrypted = ByteArray(encryptedLength).also(input::readFully)
                        if (input.read() != -1) throw IOException("Trailing credential data")
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, nonce))
                        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
                    }
                } catch (error: CredentialUnavailableException) {
                    throw error
                } catch (error: Throwable) {
                    throw CredentialUnavailableException("Credential cannot be decrypted", error)
                }
            }
        }

    override suspend fun resolve(credentialRef: String): SecretValue? =
        getOrNull(credentialRef)?.let(::SecretValue)

    override suspend fun delete(credentialId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock { file(requireSafeId(credentialId)).delete() }
        }
    }

    override suspend fun contains(credentialId: String): Boolean =
        withContext(Dispatchers.IO) { file(requireSafeId(credentialId)).isFile }

    private fun file(credentialId: String) = File(directory, "$credentialId.bin")

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requireSafeId(value: String): String {
        require(SAFE_ID.matches(value)) { "Unsafe credential identifier" }
        return value
    }

    companion object {
        private const val KEYSTORE_NAME = "AndroidKeyStore"
        private const val KEY_ALIAS = "tavern_player.model_gateway.master.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_CREDENTIAL_FILE_BYTES = 64 * 1024
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), 1)
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

fun maskCredential(secret: String): String =
    if (secret.length <= 4) "••••" else "•••• ${secret.takeLast(4)}"
