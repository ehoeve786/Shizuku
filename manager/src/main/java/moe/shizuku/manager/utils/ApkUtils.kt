package moe.shizuku.manager.utils.ApkUtils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.archive.FileInputSource
import com.reandroid.common.Namespace
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.receiver.InstallerResultReceiver
import moe.shizuku.manager.utils.ApkSigner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ApkUtils"

const val ORIGINAL_PACKAGE_NAME = "moe.shizuku.privileged.api"

private val app = ShizukuApplication.application
private val appContext = ShizukuApplication.appContext

val workDir by lazy {
    File(appContext.cacheDir, "patcher").also {
        it.deleteRecursively()
        it.mkdirs()
    }
}

private const val SIGNING_KEY_ENTRY = "assets/${ApkSigner.KEYSTORE_NAME}"
private const val ORIGIN_CERT_ENTRY = "assets/${ApkSigner.ORIGIN_CERT_ASSET}"

/**
 * Rebuilds this APK under [newPkgName] and signs it with the local stealth signing key.
 *
 * @param embedSigningKey put the signing key into the new APK. This is only needed for the very
 * first stealth APK, which is installed as a new app and has no other way to receive the key.
 * Anyone who can read that APK can read the key, so later APKs must not carry it.
 * @param originCertDigest SHA-256 of the certificate official builds are signed with, recorded
 * in the new APK so that it can authenticate updates
 */
fun File.changePackageName(
    newPkgName: String,
    embedSigningKey: Boolean = false,
    originCertDigest: String? = null,
    maybeCreateSigningKey: Boolean = false
): File {
    Log.i(TAG, "Loading APK")
    val module = ApkModule.loadApkFile(this)
    val manifest = module.androidManifest

    Log.i(TAG, "Changing package name")
    val oldPkgName = manifest.packageName
    manifest.packageName = newPkgName

    Log.i(TAG, "Updating provider authorities")
    val providers =
        manifest
            .getApplicationElement()
            .getElements("provider")
    for (provider in providers) {
        val attr = provider.searchAttribute(Namespace.URI_ANDROID, "authorities")
        val auth = attr?.valueAsString ?: continue

        if (auth.startsWith(oldPkgName)) {
            val newAuth = auth.replace(oldPkgName, newPkgName)
            attr.setValueAsString(newAuth)
        }
    }

    // Make sure the key is available (and created if allowed) before building
    ApkSigner.getSigningKey(maybeCreateSigningKey)

    module.removeInputSource(SIGNING_KEY_ENTRY)
    if (embedSigningKey) {
        Log.i(TAG, "Inserting signing key")
        module.add(FileInputSource(ApkSigner.keystoreFile, SIGNING_KEY_ENTRY))
    }

    module.removeInputSource(ORIGIN_CERT_ENTRY)
    if (originCertDigest != null) {
        module.add(ByteInputSource(originCertDigest.toByteArray(Charsets.US_ASCII), ORIGIN_CERT_ENTRY))
    }

    val outFile = File(workDir, "signed.apk")
    return module.buildAndSign(outFile)
}

/**
 * Rebuilds the installed stealth APK without the embedded signing key, so that the key no longer
 * sits in a file other apps can read. The result has to be installed over the current app.
 */
fun createApkWithoutEmbeddedKey(): File {
    // Imports the embedded key into private storage if that has not happened yet
    ApkSigner.getSigningKey()

    return File(app.applicationInfo.sourceDir).changePackageName(
        app.packageName,
        embedSigningKey = false,
        originCertDigest = ApkSigner.getOriginCertDigest()
    )
}

fun createStubApk(pkgName: String): File {
    Log.i(TAG, "Initializing APK framework")
    val outFile = File(appContext.filesDir, "stub.apk")

    val tableBlock = TableBlock()
    val manifest = AndroidManifestBlock()
    val dummyDex = ByteInputSource(ByteArray(0), "classes.dex")

    val module =
        ApkModule().apply {
            setTableBlock(tableBlock)
            setManifest(manifest)
            add(dummyDex)
        }

    Log.i(TAG, "Adding APK resources")
    val packageBlock = tableBlock.newPackage(0x7f, pkgName)
    val appName =
        packageBlock.getOrCreate("", "string", "app_name").apply {
            setValueAsString("${getAppLabel()} Stub")
        }
    val appIcon =
        packageBlock.getOrCreate("", "drawable", "ic_launcher").apply {
            setValueAsReference(R.drawable.ic_launcher)
        }

    Log.i(TAG, "Creating manifest")
    manifest.apply {
        setPackageName(pkgName)
        setVersionCode(1)
        setVersionName("1.0.0")
        setApplicationLabel(appName.getResourceId())
        setIconResourceId(appIcon.getResourceId())
        setTargetSdkVersion(app.applicationInfo.targetSdkVersion)
        setMinSdkVersion(app.applicationInfo.minSdkVersion)
    }

    return module.buildAndSign(outFile, maybeCreateSigningKey = true)
}

private fun ApkModule.buildAndSign(
    outFile: File,
    maybeCreateSigningKey: Boolean = false
): File {
    Log.i(TAG, "Building new APK")
    workDir.mkdirs()
    val unsignedApk = File(workDir, "unsigned.apk")
    writeApk(unsignedApk)

    Log.i(TAG, "Signing APK")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    ApkSigner.sign(unsignedApk, outFile, key)

    return outFile
}

fun getAppLabel(): String =
    app
        .applicationInfo
        .loadLabel(appContext.packageManager)
        .toString()

fun getVersionName(): String =
    appContext
        .packageManager
        .getPackageInfo(app.packageName, 0)
        .versionName
        .orEmpty()

fun buildApkFilename(): String {
    val safeLabel =
        getAppLabel()
            .lowercase()
            .replace("[^a-z0-9._-]".toRegex(), "-")

    return "$safeLabel-${getVersionName()}"
}

fun Context.installPackage(
    apk: File,
    cb: ((Boolean, String?) -> Unit)? = null
) {
    val installer = appContext.packageManager.packageInstaller

    val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = installer.createSession(sessionParams)
    try {
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            session.commit(createInstallerPendingIntent(cb).intentSender)
        }
    } catch (e: Exception) {
        runCatching { installer.abandonSession(sessionId) }
        throw e
    }
}

fun Context.uninstallPackage(
    pkgName: String,
    cb: ((Boolean, String?) -> Unit)? = null,
) {
    val installer = appContext.packageManager.packageInstaller
    installer.uninstall(pkgName, createInstallerPendingIntent(cb).intentSender)
}

private val nextRequestId = AtomicInteger(1)
private val installerCallbacks = ConcurrentHashMap<Int, (Boolean, String?) -> Unit>()

/** Called by [InstallerResultReceiver] once the system reports a final result. */
internal fun dispatchInstallerResult(requestId: Int, isSuccess: Boolean, msg: String?) {
    installerCallbacks.remove(requestId)?.invoke(isSuccess, msg)
}

private fun Context.createInstallerPendingIntent(
    cb: ((Boolean, String?) -> Unit)? = null
): PendingIntent {
    val requestId = nextRequestId.getAndIncrement()
    if (cb != null) installerCallbacks[requestId] = cb

    // Explicit intent to a receiver that is not exported: the result (which may carry an intent
    // that this app then starts) can only come from the system, never from another app.
    val callbackIntent =
        Intent(appContext, InstallerResultReceiver::class.java)
            .putExtra(InstallerResultReceiver.EXTRA_REQUEST_ID, requestId)

    // The system has to add the result extras, so the PendingIntent must be mutable
    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    return PendingIntent.getBroadcast(
        appContext,
        requestId,
        callbackIntent,
        flags,
    )
}
