package moe.shizuku.manager.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ApkUtils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

object UpdateHelper {
    private val app = ShizukuApplication.application
    private val appContext = ShizukuApplication.appContext

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A release version such as `13.7.0`, `13.7.0.r1520` or `13.7.0.r1520-beta`.
     *
     * For the same `major.minor.patch` a stable release is newer than any beta, and betas are
     * ordered by their commit count.
     */
    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val commit: Int = 0,
        val isBeta: Boolean = false,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(
                this,
                other,
                { it.major },
                { it.minor },
                { it.patch },
                { !it.isBeta },
                { it.commit },
            )

        override fun toString(): String =
            buildString {
                append("$major.$minor.$patch")
                if (commit != 0) append(".r$commit")
                if (isBeta) append("-beta")
            }

        companion object {
            private val regex = Regex("""(\d+)\.(\d+)\.(\d+)(?:\.r(\d+))?""")

            fun parse(tag: String, isPrerelease: Boolean = false): Version? {
                val match = regex.find(tag) ?: return null
                val (major, minor, patch, commit) = match.destructured
                return Version(
                    major.toIntOrNull() ?: return null,
                    minor.toIntOrNull() ?: return null,
                    patch.toIntOrNull() ?: return null,
                    commit.toIntOrNull() ?: 0,
                    isPrerelease || tag.contains("beta", ignoreCase = true),
                )
            }
        }
    }

    data class Release(
        val version: Version,
        val url: String,
        val digest: String?
    )

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        // Not reported for assets that were uploaded before GitHub started computing digests
        val digest: String? = null
    )

    private const val TAG = "UpdateHelper"
    private const val RELEASES_URL = "https://api.github.com/repos/thedjchi/Shizuku/releases"
    private const val DOWNLOAD_URL_PREFIX = "https://github.com/thedjchi/Shizuku/releases/download/"

    @Volatile
    private var latestRelease: Release? = null

    private fun toast(resId: Int) {
        Toast.makeText(appContext, appContext.getString(resId), Toast.LENGTH_SHORT).show()
    }

    suspend fun checkAndInstallUpdates() {
        if (isUpdateAvailable()) {
            update()
        } else {
            toast(R.string.update_latest_installed)
        }
    }

    fun isCheckForUpdatesEnabled(): Boolean = ShizukuSettings.getUpdateMode() != ShizukuSettings.UpdateMode.OFF

    suspend fun isNewUpdateAvailable(): Boolean {
        val lastPromptedVersion =
            Version.parse(ShizukuSettings.getLastPromptedVersion())
                ?: Version.parse(getVersionName())
                ?: return false
        if (!isUpdateAvailable()) return false
        val latest = latestRelease ?: return false
        return latest.version > lastPromptedVersion
    }

    suspend fun isUpdateAvailable(): Boolean {
        try {
            val latest = fetchLatestRelease().version
            val current = Version.parse(getVersionName()) ?: return false
            return latest > current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            toast(R.string.update_check_failed)
            return false
        }
    }

    fun updateLastPromptedVersion() {
        latestRelease?.let { ShizukuSettings.setLastPromptedVersion(it.version.toString()) }
    }

    suspend fun update() {
        if (latestRelease == null && !isUpdateAvailable()) return
        val release = latestRelease ?: return

        toast(R.string.update_downloading)

        val apk =
            try {
                withContext(Dispatchers.IO) { release.download().prepareForInstall(release) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Failed to download update", e)
                toast(R.string.update_download_failed)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare update", e)
                toast(R.string.update_failed)
                return
            }

        try {
            appContext.installPackage(apk) { isSuccess, _ ->
                updateFile.delete()
                toast(if (isSuccess) R.string.update_success else R.string.update_failed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update", e)
            toast(R.string.update_failed)
        }
    }

    /**
     * Reinstalls a stealth install without the signing key in its APK. The key stays available
     * in the app's private storage for future updates.
     */
    suspend fun removeEmbeddedSigningKey() {
        val apk =
            try {
                withContext(Dispatchers.IO) { createApkWithoutEmbeddedKey() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebuild APK without signing key", e)
                toast(R.string.update_failed)
                return
            }

        try {
            appContext.installPackage(apk) { isSuccess, _ ->
                toast(if (isSuccess) R.string.update_success else R.string.update_failed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reinstall without signing key", e)
            toast(R.string.update_failed)
        }
    }

    private suspend fun fetchLatestRelease(): Release =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(RELEASES_URL).build()
            val body =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Couldn't fetch releases: HTTP ${response.code}")
                    response.body?.string() ?: throw IOException("Couldn't fetch releases")
                }

            val releases = json.decodeFromString<List<GitHubRelease>>(body)
            val filtered =
                if (ShizukuSettings.getUpdateMode() == ShizukuSettings.UpdateMode.BETA) {
                    releases
                } else {
                    releases.filter { !it.prerelease }
                }

            filtered
                .mapNotNull { release ->
                    val version =
                        Version.parse(release.tag_name, release.prerelease)
                            ?: return@mapNotNull null
                    val asset =
                        release.assets.firstOrNull {
                            it.name.endsWith(".apk") && it.browser_download_url.startsWith(DOWNLOAD_URL_PREFIX)
                        } ?: return@mapNotNull null

                    Release(
                        version = version,
                        url = asset.browser_download_url,
                        digest = asset.digest
                    )
                }.maxByOrNull { it.version }
                ?.also { latestRelease = it }
                ?: throw IOException("No valid releases found")
        }

    private val updateFile by lazy { File(appContext.cacheDir, "update.apk") }

    /** Downloads the release and checks it against the digest GitHub reports, if there is one. */
    private fun Release.download(): File {
        val request = Request.Builder().url(url).build()
        val sha256 = MessageDigest.getInstance("SHA-256")

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download failed: empty response")

            updateFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sha256.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
        }

        if (digest != null) {
            val downloadedDigest = "sha256:" + sha256.digest().joinToString("") { "%02x".format(it) }
            if (!downloadedDigest.equals(digest.trim(), ignoreCase = true)) {
                updateFile.delete()
                throw SecurityException("Digest of downloaded file does not match the one reported by GitHub")
            }
        }

        return updateFile
    }

    /**
     * Returns the APK to hand to the package installer.
     *
     * A regular install takes the download as is: Android itself refuses it unless it is signed
     * with the same key as the installed app. A stealth install has to rename and re-sign the
     * download with its own key, which removes that protection, so the publisher's signature is
     * checked here first.
     */
    private fun File.prepareForInstall(release: Release): File {
        val apkPackageName = appContext.packageManager.getPackageArchiveInfo(path, 0)?.packageName
        if (apkPackageName == app.packageName) return this

        val signer = ApkSigner.verifyApk(this)
        val expected = ApkSigner.getOriginCertDigest()
        when {
            expected != null ->
                if (signer != expected.lowercase(Locale.ROOT)) {
                    delete()
                    throw SecurityException("Update is not signed by the publisher of this app")
                }

            // Stealth installs made by older versions did not record the publisher's
            // certificate. Trust the first update, but only if GitHub vouched for the file.
            release.digest == null -> {
                delete()
                throw SecurityException("Cannot authenticate update")
            }

            else -> ShizukuSettings.setOriginCertDigest(signer)
        }

        Log.d(TAG, "Changing package name from $apkPackageName to ${app.packageName}")
        return changePackageName(
            app.packageName,
            embedSigningKey = false,
            originCertDigest = expected ?: signer
        )
    }

}
