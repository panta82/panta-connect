/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R
import java.io.File

@LoadablePlugin
class ClipboardPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard_desc)

    override fun hasSettings(): Boolean = true

    override val settingsScope = SettingsScope.PER_DEVICE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.clipboardplugin_preferences)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val content = np.getString("content")
        when (np.type) {
            (PACKET_TYPE_CLIPBOARD) -> {
                ClipboardListener.instance(context).setText(content)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_FILE) -> {
                receiveClipboardFile(np)
                return true
            }
            (PACKET_TYPE_CLIPBOARD_CONNECT) -> {
                val packetTime = np.getLong("timestamp")
                // If the packetTime is 0, it means the timestamp is unknown (so do nothing).
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                if ("content" in np) { // change clipboard if content is in NetworkPacket
                    ClipboardListener.instance(context).setText(content)
                }
                return true
            }
            else -> throw UnsupportedOperationException("Unknown packet type: ${np.type}")
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            if (contentType == ClipboardListener.ClipboardContentType.Password &&
                preferences.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            if (contentType == ClipboardListener.ClipboardContentType.File) {
                val uri = ClipboardListener.instance(context).currentContentUri
                if (uri != null) {
                    propagateClipboard(uri)
                }
                return
            }
            propagateClipboard(content)
        }
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        device.sendPacket(np)
    }

    @VisibleForTesting
    fun propagateClipboard(uri: Uri) {
        if (!device.supportsPacketType(PACKET_TYPE_CLIPBOARD_FILE)) {
            Log.d(TAG, "Remote device does not support clipboard files")
            return
        }
        ThreadHelper.runOnNewThread {
            val np = FilesHelper.uriToNetworkPacket(context, uri, PACKET_TYPE_CLIPBOARD_FILE)
            if (np == null) {
                Log.e(TAG, "Could not read clipboard file $uri")
            } else {
                device.sendPacket(np)
            }
        }
    }

    private fun receiveClipboardFile(np: NetworkPacket) {
        if (!np.hasPayload() || !np.has("filename")) {
            Log.w(TAG, "Ignoring clipboard file without payload or filename")
            np.payload?.close()
            return
        }

        val filename = np.getString("filename")
        ThreadHelper.runOnNewThread {
            val directory = File(context.cacheDir, "clipboard")
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.e(TAG, "Could not create clipboard directory")
                np.payload?.close()
                return@runOnNewThread
            }

            val name = FilesHelper.findValidNonExistingFileName(DocumentFile.fromFile(directory), filename)
            val file = File(directory, name)
            try {
                val input = np.payload?.inputStream ?: throw RuntimeException("Payload has no input stream")
                input.use { source ->
                    file.outputStream().use { destination ->
                        source.copyTo(destination)
                    }
                }
                if (np.payloadSize >= 0 && file.length() < np.payloadSize) {
                    throw RuntimeException("Incomplete clipboard file: received ${file.length()} of ${np.payloadSize} bytes")
                }
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                ClipboardListener.instance(context).setFile(uri)
            } catch (e: Exception) {
                file.delete()
                Log.e(TAG, "Error receiving clipboard file", e)
            } finally {
                np.payload?.close()
            }
        }
    }

    private fun sendConnectPacket() {
        val content = ClipboardListener.instance(context).currentContent ?: return // Send clipboard only if it had been initialized
        if (ClipboardListener.instance(context).currentContentType == ClipboardListener.ClipboardContentType.Password &&
            preferences.getBoolean(
                context.getString(R.string.clipboard_preference_key_skip_sensitive),
                false,
            )
        ) {
            return
        }
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT)
        val timestamp = ClipboardListener.instance(context).updateTimestamp
        np["timestamp"] = timestamp
        np["content"] = content
        device.sendPacket(np)
    }


    override fun onCreate() {
        ClipboardListener.instance(context).registerObserver(observer)
        sendConnectPacket()
    }

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
    }

    override val supportedPacketTypes: Array<String> =
        arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_FILE, PACKET_TYPE_CLIPBOARD_CONNECT)

    override val outgoingPacketTypes: Array<String> =
        arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_FILE, PACKET_TYPE_CLIPBOARD_CONNECT)

    override fun getUiButtons(): List<PluginUiButton> {
        return if (!canSyncAutomatically(context)) {
            listOf(PluginUiButton(context.getString(R.string.send_clipboard), R.drawable.ic_baseline_content_paste_24) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return if (canSyncAutomatically(context)) {
            // If automatic sync is supported, add the manual action less prominently, as a menu entry instead of a button.
            listOf(PluginUiMenuEntry(context.getString(R.string.send_clipboard)) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    private fun userInitiatedSendClipboard() {
        if (isDeviceInitialized) {
            val clipboardManager = context.getSystemService<ClipboardManager>()!!
            val item: ClipData.Item
            if (clipboardManager.hasPrimaryClip()) {
                item = clipboardManager.primaryClip!!.getItemAt(0)
                // Don't check if the content is sensitive, just send it
                if (item.text != null) {
                    this.propagateClipboard(item.coerceToText(this.context).toString())
                } else {
                    item.uri?.let { this.propagateClipboard(it) }
                }
                Toast.makeText(this.context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "ClipboardPlugin"

        /**
         * Packet containing just clipboard contents, sent when a device updates its clipboard.
         *
         *
         * The body should look like so:
         * {
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"

        /**
         * Packet containing file data, sent or received when a device updates its clipboard and the
         * top element is a file.
         *
         * Binary file data is transferred via payload. The body should look like so:
         * {
         * "filename": "image.png"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD_FILE = "kdeconnect.clipboard.file"

        /**
         * Packet containing clipboard contents and a timestamp that the contents were last updated, sent
         * on first connection
         *
         *
         * The timestamp is milliseconds since epoch. It can be 0, which indicates that the clipboard
         * update time is currently unknown.
         *
         *
         * The body should look like so:
         * {
         * "timestamp": 542904563213,
         * "content": "password"
         * }
         */
        private const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        fun canSyncAutomatically(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            // Check if hack to read clipboard changes from logs is enabled
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }

    }
}
