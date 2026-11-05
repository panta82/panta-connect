/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kde.kdeconnect.NetworkPacket
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardImageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 2 * ClipboardImageStore.CACHE_AGE_MS
    private val png get() = javaClass.getResourceAsStream("/clipboard/image.png")!!.readBytes()

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var readCalls = 0
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            readCalls++
            return super.read(bytes, offset, count)
        }
        override fun close() { closed = true; super.close() }
    }

    private fun store() = ClipboardImageStore(temporary.root) { now }

    @Test fun receivesPngWithoutChangingBytesAndClosesPayload() {
        val input = TrackedInput(png)
        val image = store().receive(NetworkPacket.Payload(input, png.size.toLong()), null) { true }
        assertEquals("png", image.extension)
        assertArrayEquals(png, image.readBytes())
        assertTrue(input.closed)
    }

    @Test fun determinesJpegMimeFromContent() {
        val jpeg = javaClass.getResourceAsStream("/clipboard/image.jpg")!!.readBytes()
        val image = store().receive(NetworkPacket.Payload(jpeg), null) { true }
        assertEquals("jpg", image.extension)
        assertArrayEquals(jpeg, image.readBytes())
    }

    @Test fun usesUniqueFilesForRepeatedImages() {
        val first = store().receive(NetworkPacket.Payload(png), null) { true }
        val second = store().receive(NetworkPacket.Payload(png), first) { true }
        assertNotEquals(first, second)
        assertArrayEquals(png, first.readBytes())
        assertArrayEquals(png, second.readBytes())
    }

    @Test fun rejectsOversizedAndUnknownPayloadsWithoutReading() {
        for (size in listOf(-1L, 0L, ClipboardImageStore.MAX_IMAGE_BYTES + 1)) {
            val input = TrackedInput(png)
            assertThrows(IOException::class.java) {
                store().receive(NetworkPacket.Payload(input, size), null) { true }
            }
            assertEquals(0, input.readCalls)
            assertTrue(input.closed)
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectsTruncatedTransfersAndRemovesPartialFile() {
        val input = TrackedInput(png)
        assertThrows(IOException::class.java) {
            store().receive(NetworkPacket.Payload(input, png.size + 100L), null) { true }
        }
        assertTrue(input.closed)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectsNonImageBytesAndCleansUp() {
        assertThrows(IOException::class.java) {
            store().receive(NetworkPacket.Payload("not an image".toByteArray()), null) { true }
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun stopsWhenClipboardChangesDuringCopy() {
        var current = true
        val input = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                val result = super.read(bytes, offset, count)
                current = false
                return result
            }
        }
        assertThrows(IOException::class.java) {
            store().receive(NetworkPacket.Payload(input, png.size.toLong()), null) { current }
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun rejectsImagesWhoseDimensionsWouldRequireTooMuchMemory() {
        for (name in listOf("too-wide.png", "too-many-pixels.png")) {
            val bytes = javaClass.getResourceAsStream("/clipboard/$name")!!.readBytes()
            val error = assertThrows(IOException::class.java) {
                store().receive(NetworkPacket.Payload(bytes), null) { true }
            }
            assertTrue(error.message!!.contains("dimensions"))
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun evictsOlderImagesToMakeRoomForAnotherTransfer() {
        val old = File(temporary.root, "old.png")
        RandomAccessFile(old, "rw").use { it.setLength(ClipboardImageStore.MAX_CACHE_BYTES) }
        old.setLastModified(now - ClipboardImageStore.PASTE_GRACE_MS - 1)
        val received = store().receive(NetworkPacket.Payload(png), null) { true }
        assertFalse(old.exists())
        assertArrayEquals(png, received.readBytes())
    }

    @Test fun prunesExpiredFilesButKeepsCurrentAndRecentImages() {
        val expired = File(temporary.root, "expired.png").apply { writeBytes(png); setLastModified(0) }
        val current = File(temporary.root, "current.png").apply { writeBytes(png); setLastModified(0) }
        val recent = File(temporary.root, "recent.png").apply { writeBytes(png); setLastModified(now) }
        store().receive(NetworkPacket.Payload(png), current) { true }
        assertFalse(expired.exists())
        assertTrue(current.exists())
        assertTrue(recent.exists())
    }

    @Test fun respectsCacheByteBudgetAndDoesNotDeleteCurrentImage() {
        val current = File(temporary.root, "current.png")
        RandomAccessFile(current, "rw").use { it.setLength(ClipboardImageStore.MAX_CACHE_BYTES) }
        current.setLastModified(0)
        val input = TrackedInput(png)
        assertThrows(IOException::class.java) {
            store().receive(NetworkPacket.Payload(input, png.size.toLong()), current) { true }
        }
        assertTrue(current.exists())
        assertEquals(0, input.readCalls)
        assertTrue(input.closed)
    }

    @Test fun limitsCacheFileCountWithoutDeletingRecentReaders() {
        repeat(ClipboardImageStore.MAX_CACHE_FILES) { number ->
            File(temporary.root, "$number.png").apply { writeBytes(png); setLastModified(now) }
        }
        assertThrows(IOException::class.java) {
            store().receive(NetworkPacket.Payload(png), null) { true }
        }
        assertEquals(ClipboardImageStore.MAX_CACHE_FILES, temporary.root.listFiles()!!.size)
    }

    @Test fun appliesSocketReadTimeout() {
        val socket = mockk<Socket>(relaxed = true)
        every { socket.getInputStream() } returns ByteArrayInputStream(png)
        store().receive(NetworkPacket.Payload(socket, png.size.toLong()), null) { true }
        io.mockk.verify { socket.soTimeout = 30_000 }
        io.mockk.verify { socket.close() }
    }

    @Test fun pairedDevicesCannotOverfillSharedCacheWithConcurrentTransfers() {
        val current = File(temporary.root, "current.png")
        RandomAccessFile(current, "rw").use { it.setLength(ClipboardImageStore.MAX_CACHE_BYTES - png.size) }
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val firstInput = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                reading.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(bytes, offset, count)
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<File> {
                store().receive(NetworkPacket.Payload(firstInput, png.size.toLong()), current) { true }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> {
                try {
                    store().receive(NetworkPacket.Payload(png), current) { true }
                    false
                } catch (_: IOException) {
                    true
                } finally {
                    secondDone.countDown()
                }
            }
            secondDone.await(100, TimeUnit.MILLISECONDS)
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).isFile)
            assertTrue(second.get(5, TimeUnit.SECONDS))
            assertTrue(temporary.root.listFiles()!!.sumOf { it.length() } <= ClipboardImageStore.MAX_CACHE_BYTES)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
