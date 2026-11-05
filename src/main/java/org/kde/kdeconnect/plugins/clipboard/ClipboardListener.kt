/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
        File,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentUri: Uri? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set
    var currentContentIsSensitive: Boolean = false
        private set
    private val updateVersion = AtomicLong()
    private var applyingRemoteClipboard = false

    private val cm: ClipboardManager

    internal constructor(ctx: Context) {
        context = ctx.applicationContext
        cm = context.getSystemService<ClipboardManager>()!!
        Handler(Looper.getMainLooper()).post {
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ClipboardPlugin.canSyncAutomatically(context)) {
            ThreadHelper.runOnNewThread {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    // Listen only ClipboardService errors after now
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        if (applyingRemoteClipboard) return
        try {
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                reserveUpdate()
                currentContent = null
                currentContentUri = null
                currentContentIsSensitive = false
                currentContentType = ClipboardContentType.Text
                updateTimestamp = System.currentTimeMillis()
                return
            }
            val item = clip.getItemAt(0)
            val contentType = detectContentType(clip)
            val sensitive = contentType == ClipboardContentType.Password
            val uri = imageUri(context, clip)

            if (uri != null) {
                if (uri == currentContentUri && currentContentType == ClipboardContentType.File && currentContentIsSensitive == sensitive) {
                    return
                }
                reserveUpdate()
                updateTimestamp = System.currentTimeMillis()
                currentContent = null
                currentContentUri = uri
                currentContentType = ClipboardContentType.File
                currentContentIsSensitive = sensitive

                for (observer in observers) {
                    observer.clipboardChanged(uri.toString(), ClipboardContentType.File)
                }
                return
            }

            val content = item.coerceToText(context).toString()
            if (content == currentContent && contentType == currentContentType && currentContentUri == null) {
                return
            }
            reserveUpdate()
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            currentContentUri = null
            currentContentType = contentType
            currentContentIsSensitive = sensitive

            for (observer in observers) {
                observer.clipboardChanged(content, contentType)
            }
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    fun setText(text: String?) {
        reserveUpdate()
        if (text == currentContent && currentContentUri == null && !currentContentIsSensitive) return
        applyClipboard(ClipData.newPlainText(null, text))
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentContentUri = null
        currentContentType = ClipboardContentType.Text
        currentContentIsSensitive = false
    }

    fun setFile(uri: Uri) {
        reserveUpdate()
        if (uri == currentContentUri && !currentContentIsSensitive) return
        applyClipboard(ClipData.newUri(context.contentResolver, null, uri))
        updateTimestamp = System.currentTimeMillis()
        currentContent = null
        currentContentUri = uri
        currentContentType = ClipboardContentType.File
        currentContentIsSensitive = false
    }

    private fun applyClipboard(clip: ClipData) {
        applyingRemoteClipboard = true
        try {
            cm.setPrimaryClip(clip)
        } finally {
            applyingRemoteClipboard = false
        }
    }

    fun reserveUpdate(): Long = updateVersion.incrementAndGet()

    fun isCurrentUpdate(version: Long): Boolean = updateVersion.get() == version

    companion object {
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun imageUri(context: Context, clip: ClipData): Uri? {
            if (clip.itemCount == 0) return null
            val uri = clip.getItemAt(0).uri ?: return null
            if (clip.description.hasMimeType("image/*")) return uri
            return try {
                uri.takeIf { context.contentResolver.getType(it)?.startsWith("image/") == true }
            } catch (_: Exception) {
                null
            }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
