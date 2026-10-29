/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Developers
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ClipboardListenerTest {
    private lateinit var context: Application
    private lateinit var listener: ClipboardListener
    private lateinit var clipboardManager: ClipboardManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listener = ClipboardListener.instance(context)
        shadowOf(Looper.getMainLooper()).idle()
        clipboardManager = context.getSystemService<ClipboardManager>()!!
    }

    @Test
    fun testImageUriIsDetectedAsFile() {
        val uri = Uri.parse("content://org.kde.kdeconnect.test/image.png")
        clipboardManager.setPrimaryClip(ClipData.newRawUri(null, uri))

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
}
