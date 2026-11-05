/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.graphics.BitmapFactory
import org.kde.kdeconnect.NetworkPacket
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Private clipboard images, with bounded transfers and time for other apps to finish pasting. */
internal class ClipboardImageStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun receive(payload: NetworkPacket.Payload, keep: File?, isCurrent: () -> Boolean): File = synchronized(cacheLock) {
        receiveImage(payload, keep, isCurrent)
    }

    private fun receiveImage(payload: NetworkPacket.Payload, keep: File?, isCurrent: () -> Boolean): File {
        var temporary: File? = null
        try {
            if (payload.payloadSize !in 1..MAX_IMAGE_BYTES) {
                throw IOException("Invalid clipboard image size: ${payload.payloadSize}")
            }
            checkCurrent(isCurrent)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot create clipboard image cache")
            }
            prune(keep, payload.payloadSize)
            temporary = File.createTempFile("image-", ".part", directory)
            payload.setReadTimeout(READ_TIMEOUT_MS)
            val input = payload.inputStream ?: throw IOException("Clipboard image has no input stream")
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = payload.payloadSize
                while (remaining > 0) {
                    checkCurrent(isCurrent)
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("Incomplete clipboard image")
                    if (count == 0) throw IOException("Clipboard image stream made no progress")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            checkCurrent(isCurrent)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            val extension = when (bounds.outMimeType) {
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> throw IOException("Unsupported clipboard image format")
            }
            if (bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS
            ) {
                throw IOException("Clipboard image dimensions exceed the limit")
            }
            val image = File(directory, temporary.nameWithoutExtension + extension)
            if (image.exists() || !temporary.renameTo(image)) {
                throw IOException("Cannot finish clipboard image")
            }
            temporary = null
            return image
        } finally {
            temporary?.delete()
            // Payload owns the transport socket. Do not close its input stream separately.
            payload.close()
        }
    }

    private fun checkCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) throw InterruptedIOException("Clipboard image was superseded")
    }

    private fun prune(keep: File?, incomingBytes: Long) {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        val now = clock()
        for (file in files) {
            val age = now - file.lastModified()
            if (file == keep || age < PASTE_GRACE_MS) continue
            if (age > CACHE_AGE_MS || bytes + incomingBytes > MAX_CACHE_BYTES || count >= MAX_CACHE_FILES) {
                val size = file.length()
                if (file.delete()) {
                    bytes -= size
                    count--
                }
            }
        }
        if (bytes + incomingBytes > MAX_CACHE_BYTES || count >= MAX_CACHE_FILES) {
            throw IOException("Clipboard image cache is full")
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
        const val MAX_CACHE_BYTES = 200L * 1024 * 1024
        const val MAX_CACHE_FILES = 64
        const val MAX_PIXELS = 32_000_000L
        const val MAX_DIMENSION = 32768
        const val PASTE_GRACE_MS = 60_000L
        const val CACHE_AGE_MS = 24L * 60 * 60 * 1000
        private const val READ_TIMEOUT_MS = 30_000
        // All paired-device plugins share the same cache and its storage budget.
        private val cacheLock = Any()
    }
}
