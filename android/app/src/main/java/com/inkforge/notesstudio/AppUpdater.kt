package com.inkforge.notesstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest

internal class AppUpdater(private val context: Context) {
    data class Release(
        val version: String,
        val build: Int?,
        val url: String,
        val size: Long,
        val notes: String,
        val sha256: String?
    )

    val isPublicInstall = context.packageName == "com.inkforge.note4" || context.packageName == "com.inkforge.note5"

    fun latest(): Release? {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val release = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val installed = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-profile")
            return releaseFromJson(release, installed, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR)
        } finally {
            connection.disconnect()
        }
    }

    fun download(release: Release, progress: (Int?) -> Unit): File {
        require(release.url.startsWith(DOWNLOAD_PREFIX)) { "Invalid APK download URL" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - UPDATE_CACHE_AGE_MS
        directory.listFiles { file -> file.name.startsWith("badnote-update-") && file.name.endsWith(".apk") }
            ?.filter { it.lastModified() in 1 until expiry }
            ?.forEach { it.delete() }
        val connection = URL(release.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        val file = File.createTempFile("badnote-update-", ".apk", directory)
        var validated = false
        try {
            val total = release.size.takeIf { it > 0 } ?: connection.getHeaderField("Content-Length")?.toLongOrNull()
            val digest = MessageDigest.getInstance("SHA-256")
            var lastReport = 0L
            var lastPercent = -1
            progress(if (total != null && total > 0) 0 else null)
            connection.inputStream.use { input ->
                DigestOutputStream(file.outputStream(), digest).use { output ->
                    ArchiveCodec.copy(input, output, cancelled = { Thread.currentThread().isInterrupted }) { bytes ->
                        if (total != null && total > 0) {
                            require(bytes <= total) { "APK exceeds the published size" }
                            val percent = (100.0 * bytes / total).toInt()
                            val now = SystemClock.elapsedRealtime()
                            if (percent != lastPercent && (now - lastReport >= 120 || percent == 100)) {
                                lastPercent = percent
                                lastReport = now
                                progress(percent)
                            }
                        }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Update cancelled")
            require(total == null || total <= 0 || file.length() == total) { "APK size does not match" }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(release.sha256 == null || sha256 == release.sha256) { "APK digest does not match" }
            val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0)) { "Invalid APK" }
            val versionCode = PackageInfoCompat.getLongVersionCode(info)
            require(info.packageName == context.packageName &&
                info.versionName == release.version &&
                versionCode > BuildConfig.VERSION_CODE &&
                (release.build == null || versionCode == release.build.toLong())) { "APK version or package does not match" }
            validated = true
            return file
        } finally {
            connection.disconnect()
            if (!validated) file.delete()
        }
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/jsk1004ha/BADNOTE/releases/latest"
        private const val DOWNLOAD_PREFIX = "https://github.com/jsk1004ha/BADNOTE/releases/download/"
        private const val UPDATE_CACHE_AGE_MS = 24 * 60 * 60 * 1000L
        private val stableVersion = Regex("""v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\+([1-9][0-9]*))?""")
        private val installedVersion = Regex("""v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?""")
        private val sha256Pattern = Regex("""sha256:([0-9a-fA-F]{64})""")

        internal fun shouldOfferRelease(
            remote: String, installed: String, prerelease: Boolean, draft: Boolean, installedBuild: Int? = null
        ): Boolean {
            if (prerelease || draft) return false
            val target = stableVersion.matchEntire(remote) ?: return false
            val current = installedVersion.matchEntire(installed) ?: return false
            val buildText = target.groupValues[4]
            val targetBuild = if (buildText.isEmpty()) null else buildText.toIntOrNull() ?: return false
            if (targetBuild != null && installedBuild != null && targetBuild <= installedBuild) return false
            for (index in 1..3) {
                val next = target.groupValues[index].toLongOrNull() ?: return false
                val existing = current.groupValues[index].toLongOrNull() ?: return false
                if (next != existing) return next > existing
            }
            return if (targetBuild == null) current.groupValues[4].isNotEmpty() else installedBuild != null
        }

        internal fun releaseFromJson(data: JSONObject, installed: String, installedBuild: Int, flavor: String): Release? {
            val tag = data.optString("tag_name")
            if (!shouldOfferRelease(tag, installed, data.optBoolean("prerelease"), data.optBoolean("draft"), installedBuild)) {
                return null
            }
            val suffix = if (flavor == "sideBySide") "-SideBySide.apk" else "-Update.apk"
            val asset = requireNotNull(data.optJSONArray("assets")?.objects()?.firstOrNull {
                it.optString("name").endsWith(suffix) && it.optString("state", "uploaded") == "uploaded"
            }) { "No APK for this installation type" }
            val url = asset.getString("browser_download_url")
            require(url.startsWith(DOWNLOAD_PREFIX)) { "Invalid APK download URL" }
            val digest = if (asset.isNull("digest")) null else {
                requireNotNull(sha256Pattern.matchEntire(asset.getString("digest"))) { "Invalid APK digest" }
                    .groupValues[1].lowercase()
            }
            return Release(
                version = tag.removePrefix("v").substringBefore('+'),
                build = tag.substringAfter('+', "").toIntOrNull(),
                url = url,
                size = asset.optLong("size").coerceAtLeast(0),
                notes = data.optString("body", "").take(12_000).trim(),
                sha256 = digest
            )
        }
    }
}
