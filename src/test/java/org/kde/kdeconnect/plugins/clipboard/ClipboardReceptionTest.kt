/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardReceptionTest {
    private lateinit var plugin: ClipboardPlugin
    private lateinit var listener: ClipboardListener
    private lateinit var context: Application
    private val installed = CopyOnWriteArrayList<File>()
    private val blocked = mutableListOf<BlockingInput>()
    private val png get() = javaClass.getResourceAsStream("/clipboard/image.png")!!.readBytes()

    private class BlockingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return super.read(bytes, offset, count)
        }
        override fun close() { closed.countDown(); super.close() }
    }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "clipboard").deleteRecursively()
        listener = spyk(ClipboardListener(context))
        every { listener.setFile(any()) } just Runs
        mockkObject(ClipboardListener.Companion)
        every { ClipboardListener.instance(any()) } returns listener
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } answers {
            assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
            val image = thirdArg<File>()
            installed.add(image)
            Uri.parse("content://test.fileprovider/clipboard/${image.name}")
        }
        val device = mockk<Device> {
            every { deviceId } returns "image-test"
            every { sendPacket(any()) } just Runs
        }
        plugin = ClipboardPlugin().apply { setContext(context, device) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun cleanup() {
        plugin.onDestroy()
        blocked.forEach { it.release.countDown() }
        blocked.forEach { assertTrue(it.closed.await(5, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        unmockkStatic(FileProvider::class)
        unmockkObject(ClipboardListener.Companion)
    }

    private fun packet(payload: NetworkPacket.Payload) = NetworkPacket("kdeconnect.clipboard.file").apply {
        set("filename", "../../untrusted-name.txt")
        this.payload = payload
    }

    private fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Clipboard worker did not complete")
    }

    private fun startBlocked(): BlockingInput {
        val input = BlockingInput(png).also { blocked.add(it) }
        plugin.onPacketReceived(packet(NetworkPacket.Payload(input, png.size.toLong())))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(input.started.await(5, TimeUnit.SECONDS))
        return input
    }

    @Test fun receivesRealImageOnMainThreadAndIgnoresPeerFilename() {
        plugin.onPacketReceived(packet(NetworkPacket.Payload(png)))
        drainUntil { installed.size == 1 }
        val file = installed.single()
        assertEquals(File(context.cacheDir, "clipboard"), file.parentFile)
        assertEquals("png", file.extension)
        assertArrayEquals(png, file.readBytes())
        verify(exactly = 1) { listener.setFile(any()) }
    }

    @Test fun slowImageCannotReplaceNewerText() {
        val input = startBlocked()
        plugin.onPacketReceived(NetworkPacket("kdeconnect.clipboard").apply { set("content", "newer text") })
        shadowOf(Looper.getMainLooper()).idle()
        input.release.countDown()
        assertTrue(input.closed.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("newer text", listener.currentContent)
        assertTrue(installed.isEmpty())
        assertTrue(File(context.cacheDir, "clipboard").listFiles()!!.isEmpty())
    }

    @Test fun burstKeepsOnlyNewestPendingImageAndClosesDiscardedPayload() {
        val input = startBlocked()
        val discardedClosed = CountDownLatch(1)
        val discarded = object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                fail("Superseded queued payload must not be read")
                return -1
            }
            override fun close() { discardedClosed.countDown(); super.close() }
        }
        plugin.onPacketReceived(packet(NetworkPacket.Payload(discarded, png.size.toLong())))
        plugin.onPacketReceived(packet(NetworkPacket.Payload(png)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(discardedClosed.await(5, TimeUnit.SECONDS))
        input.release.countDown()
        drainUntil { installed.size == 1 }
        assertArrayEquals(png, installed.single().readBytes())
        assertEquals(1, File(context.cacheDir, "clipboard").listFiles()!!.size)
    }

    @Test fun destroyedPluginCannotInstallAnImage() {
        val input = startBlocked()
        plugin.onDestroy()
        input.release.countDown()
        assertTrue(input.closed.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(installed.isEmpty())
    }

    @Test fun missingFilenameClosesPayloadWithoutReading() {
        val input = spyk(ByteArrayInputStream(png))
        val payload = NetworkPacket.Payload(input, png.size.toLong())
        plugin.onPacketReceived(NetworkPacket("kdeconnect.clipboard.file").apply { this.payload = payload })
        verify(timeout = 5000) { input.close() }
        verify(exactly = 0) { input.read(any<ByteArray>(), any(), any()) }
        assertTrue(installed.isEmpty())
    }
}
