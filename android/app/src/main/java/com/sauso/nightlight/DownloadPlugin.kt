package com.sauso.nightlight

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.io.FileOutputStream

/**
 * Save a file straight into the phone's public Downloads folder, so an export (e.g. the
 * diagnostics bundle) lands somewhere the user can then attach to a GitHub issue — rather than
 * only offering the share sheet. The WebView can't do a browser-style download at all.
 *
 * From the web side:
 *   Capacitor.Plugins.Download.saveToDownloads({ filename, data /* base64 */, mimeType })
 *     -> { uri }  on success, rejects on failure (caller can fall back to the share sheet).
 *
 * On Android 10+ (API 29) this uses MediaStore's Downloads collection, which needs NO runtime
 * permission (scoped storage). On older devices it writes to the public Downloads dir directly,
 * which may fail without WRITE_EXTERNAL_STORAGE — in that case it rejects and the web app falls
 * back to sharing the file.
 */
@CapacitorPlugin(name = "Download")
class DownloadPlugin : Plugin() {

    @PluginMethod
    fun saveToDownloads(call: PluginCall) {
        val filename = call.getString("filename")
        val base64 = call.getString("data")
        val mimeType = call.getString("mimeType") ?: "application/octet-stream"
        if (filename.isNullOrBlank() || base64.isNullOrBlank()) {
            call.reject("filename and data are required")
            return
        }
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: Exception) {
            call.reject("Invalid base64 data")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: run { call.reject("Couldn't create the download entry"); return }
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run { call.reject("Couldn't open the download for writing"); return }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                call.resolve(JSObject().put("uri", uri.toString()))
            } else {
                // Pre-Android 10: write into the public Downloads dir. Needs WRITE_EXTERNAL_STORAGE;
                // if it's not granted this throws and we reject, so the web app shares instead.
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { it.write(bytes) }
                call.resolve(JSObject().put("uri", file.absolutePath))
            }
        } catch (e: Exception) {
            call.reject("Save failed: ${e.message}")
        }
    }
}
