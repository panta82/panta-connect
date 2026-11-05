/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.net.Uri
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import io.mockk.verify
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class ClipboardListenerTest {
    private lateinit var context: Application
    private lateinit var listener: ClipboardListener
    private lateinit var clipboardManager: ClipboardManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listener = ClipboardListener(context)
        shadowOf(Looper.getMainLooper()).idle()
        clipboardManager = context.getSystemService<ClipboardManager>()!!
    }

    @Test
    fun testImageUriIsDetectedAsFile() {
        val uri = Uri.parse("content://org.kde.kdeconnect.test/image.png")
        clipboardManager.setPrimaryClip(ClipData("image", arrayOf("image/png"), ClipData.Item(uri)))

        listener.onClipboardChanged()

        Assert.assertEquals(ClipboardListener.ClipboardContentType.File, listener.currentContentType)
        Assert.assertEquals(uri, listener.currentContentUri)
        Assert.assertNull(listener.currentContent)
    }

    @Test
    fun testTextIsStillDetectedAsText() {
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, "clipboard text"))

        listener.onClipboardChanged()

        Assert.assertEquals(ClipboardListener.ClipboardContentType.Text, listener.currentContentType)
        Assert.assertEquals("clipboard text", listener.currentContent)
        Assert.assertNull(listener.currentContentUri)
    }

    @Test
    fun imageWithTextAlternativeUsesImageForBothSendPaths() {
        val uri = Uri.parse("content://images/test.png")
        val clip = ClipData("image", arrayOf("image/png", "text/plain"), ClipData.Item("caption", null, uri))
        clipboardManager.setPrimaryClip(clip)
        listener.onClipboardChanged()
        Assert.assertEquals(uri, ClipboardListener.imageUri(context, clip))
        Assert.assertEquals(uri, listener.currentContentUri)
        Assert.assertNull(listener.currentContent)
    }

    @Test
    fun uriBackedTextKeepsTextCoercion() {
        val uri = Uri.parse("https://example.invalid/text")
        val clip = ClipData("text", arrayOf("text/plain"), ClipData.Item(uri))
        clipboardManager.setPrimaryClip(clip)
        listener.onClipboardChanged()
        Assert.assertNull(ClipboardListener.imageUri(context, clip))
        Assert.assertEquals(uri.toString(), listener.currentContent)
        Assert.assertEquals(ClipboardListener.ClipboardContentType.Text, listener.currentContentType)
    }

    @Test
    fun imageRetainsSensitiveFlag() {
        val clip = ClipData("sensitive", arrayOf("image/png"), ClipData.Item(Uri.parse("content://images/secret.png")))
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboardManager.setPrimaryClip(clip)
        listener.onClipboardChanged()
        Assert.assertEquals(ClipboardListener.ClipboardContentType.File, listener.currentContentType)
        Assert.assertTrue(listener.currentContentIsSensitive)
    }

    @Test
    fun newerTextInvalidatesPendingImageEvenIfTextIsUnchanged() {
        listener.setText("keep this")
        val version = listener.reserveUpdate()
        listener.setText("keep this")
        Assert.assertFalse(listener.isCurrentUpdate(version))
    }

    @Test
    fun clearingClipboardInvalidatesPendingImage() {
        listener.setText("old text")
        val version = listener.reserveUpdate()
        clipboardManager.clearPrimaryClip()
        listener.onClipboardChanged()
        Assert.assertFalse(listener.isCurrentUpdate(version))
        Assert.assertNull(listener.currentContent)
        Assert.assertNull(listener.currentContentUri)
    }

    @Test
    fun receivedTextIsNotEchoedToObservers() {
        val observer = mockk<ClipboardListener.ClipboardObserver>(relaxed = true)
        listener.registerObserver(observer)
        listener.setText("received text")
        shadowOf(Looper.getMainLooper()).idle()
        listener.onClipboardChanged()
        verify(exactly = 0) { observer.clipboardChanged(any(), any()) }
    }
}
