package moe.shizuku.manager.utils

import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.*
import java.security.KeyStore.PrivateKeyEntry
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ApkUtils.ORIGINAL_PACKAGE_NAME
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

private val appContext = ShizukuApplication.appContext

object ApkSigner {

    private const val KEY_ALIAS = "key"
    private const val KEY_PASS = "keypass"
    const val KEYSTORE_NAME = "signing-key.bks"
    private const val KEYSTORE_PASS = "storepass"

    /**
     * Asset that holds the SHA-256 of the certificate the original (non-stealth) app was signed
     * with. Stealth clones use it to check that a downloaded update really comes from the same
     * publisher before re-signing it with the local key.
     */
    const val ORIGIN_CERT_ASSET = "origin-cert.sha256"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
            Security.addProvider(BouncyCastleProvider())
    }

    fun sign(input: File, output: File, key: PrivateKeyEntry) {
        val cert = key.certificate as X509Certificate

        ApkSigner.Builder(
            listOf(
                ApkSigner.SignerConfig.Builder(
                    "ShizukuSigner",
                    key.privateKey,
                    listOf(cert)
                ).build()
            )
        )
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign()
    }

    fun getSigningKey(createIfNeeded: Boolean = false): PrivateKeyEntry {
        if (keystoreFile.exists()) return loadKey(keystoreFile)

        importEmbeddedKey()?.let { return it }

        return if (createIfNeeded) createSigningKey()
        else throw IllegalStateException("Signing key does not exist")
    }

    /** Whether the installed APK still carries the signing key in its assets. */
    fun hasEmbeddedKey(): Boolean =
        try {
            appContext.assets.open(KEYSTORE_NAME).close()
            true
        } catch (e: IOException) {
            false
        }

    /**
     * Copies the key a stealth APK was created with into private storage. The key is only kept
     * if it matches the certificate this app is signed with.
     */
    private fun importEmbeddedKey(): PrivateKeyEntry? {
        val tmp = File(appContext.filesDir, "$KEYSTORE_NAME.tmp")
        try {
            appContext.assets.open(KEYSTORE_NAME).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            return null
        }

        try {
            val key = loadKey(tmp)
            if (!key.matchesAppCert()) {
                throw IllegalStateException("Signing key does not match app certificate")
            }
            if (!tmp.renameTo(keystoreFile)) {
                throw IOException("Could not store signing key")
            }
            return key
        } finally {
            tmp.delete()
        }
    }

    private fun createSigningKey(): PrivateKeyEntry {
        val keystoreParameters = generatePrivateKeyAndCert()
        writeKeyStore(keystoreParameters)
        return loadKey(keystoreFile)
    }

    val keystoreFile by lazy {
        File(appContext.filesDir, KEYSTORE_NAME)
    }

    private fun generatePrivateKeyAndCert(): PrivateKeyCertificatePair {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()

        val now = Date()
        val validUntil = Date(now.time + 3650L * 24 * 60 * 60 * 1000)

        val name = X500Name("CN=Shizuku")
        val certHolder = X509v3CertificateBuilder(
            name,
            BigInteger.valueOf(SecureRandom().nextLong()),
            now,
            validUntil,
            Locale.ENGLISH,
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        ).build(
            JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.private)
        )

        val cert = JcaX509CertificateConverter().getCertificate(certHolder)

        return PrivateKeyCertificatePair(keyPair.private, cert)
    }

    private fun writeKeyStore(pair: PrivateKeyCertificatePair) {
        val ks = KeyStore.getInstance("BKS", BouncyCastleProvider.PROVIDER_NAME).apply {
            load(null)
            setKeyEntry(
                KEY_ALIAS,
                pair.privateKey,
                KEY_PASS.toCharArray(),
                arrayOf(pair.certificate)
            )
        }

        keystoreFile.outputStream().use {
            ks.store(it, KEYSTORE_PASS.toCharArray())
        }
    }

    private fun loadKey(file: File): PrivateKeyEntry {
        val ks = KeyStore.getInstance("BKS", BouncyCastleProvider.PROVIDER_NAME)
        file.inputStream().use { ks.load(it, KEYSTORE_PASS.toCharArray()) }

        return ks.getEntry(KEY_ALIAS, KeyStore.PasswordProtection(KEY_PASS.toCharArray()))
            as PrivateKeyEntry
    }

    private data class PrivateKeyCertificatePair(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    @Suppress("DEPRECATION")
    private fun getAppCertificate(): X509Certificate {
        val pm = appContext.packageManager
        val found =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES).signatures
            }
        val signers = found ?: emptyArray()
        require(signers.isNotEmpty()) { "No signing certificates found" }

        val certBytes = signers[0].toByteArray()

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(certBytes.inputStream()) as X509Certificate
    }

    private fun X509Certificate.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { "%02x".format(it) }

    /** SHA-256 of the certificate this app is signed with. */
    fun getAppCertDigest(): String = getAppCertificate().sha256()

    /**
     * SHA-256 of the certificate that official builds are signed with, as far as this install
     * knows it: its own certificate for a regular install, the pinned one for a stealth clone.
     * Null if a clone was created by a version that did not record it yet.
     */
    fun getOriginCertDigest(): String? {
        if (appContext.packageName == ORIGINAL_PACKAGE_NAME) return getAppCertDigest()

        val embedded =
            try {
                appContext.assets.open(ORIGIN_CERT_ASSET).use { it.readBytes() }
                    .toString(Charsets.US_ASCII)
                    .trim()
                    .lowercase(Locale.ROOT)
            } catch (e: IOException) {
                null
            }
        if (embedded != null && embedded.matches(Regex("[0-9a-f]{64}"))) return embedded

        return ShizukuSettings.getOriginCertDigest()
    }

    /**
     * Verifies the signatures of [apk] and returns the SHA-256 of its signing certificate.
     *
     * @throws SecurityException if the APK is not validly signed by exactly one signer
     */
    fun verifyApk(apk: File): String {
        val result =
            try {
                ApkVerifier.Builder(apk).build().verify()
            } catch (e: Exception) {
                throw SecurityException("Could not verify APK signature", e)
            }
        if (!result.isVerified) throw SecurityException("APK signature is not valid")

        val certs = result.signerCertificates
        if (certs.size != 1) throw SecurityException("APK must be signed by exactly one signer")
        return certs[0].sha256()
    }

    private fun PrivateKeyEntry.matchesAppCert(): Boolean {
        val appCert = getAppCertificate()
        val keyCert = this.certificate as X509Certificate
        return appCert.publicKey.encoded.contentEquals(keyCert.publicKey.encoded)
    }

}