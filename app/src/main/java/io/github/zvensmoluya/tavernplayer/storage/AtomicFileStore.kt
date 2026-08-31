package io.github.zvensmoluya.tavernplayer.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object AtomicFileStore {
    fun writeUtf8(target: File, value: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: IOException) {
                // Some Windows/JVM filesystems cannot atomically replace an existing file.
                // Android app-private storage supports the first branch; this keeps host tests usable.
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun cleanupTemporaryFiles(directory: File) {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }
}
