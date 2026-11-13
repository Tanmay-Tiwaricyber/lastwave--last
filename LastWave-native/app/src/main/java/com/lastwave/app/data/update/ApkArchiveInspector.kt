package com.lastwave.app.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the platform says about a downloaded APK, kept behind an interface.
 *
 * The alternative — reading `packageManager` inline in [AppUpdateManager] — makes
 * the security decision of the whole updater untestable, because no JVM test can
 * install a real APK. With this seam the manager's behaviour (offer → download →
 * verify → refuse or hand off to the installer) is covered by ordinary unit
 * tests with a fake, while the device keeps the real [PackageManager] answers.
 */
interface ApkArchiveInspector {

    /** Everything knowable about [file] before the installer is asked to touch it. */
    fun inspect(file: File): ApkFacts

    /** The certificate this installed app is signed with, or null if unreadable. */
    fun installedSignature(): ByteArray?

    /** This build's `versionCode`, or 0 when the package manager will not say. */
    fun installedVersionCode(): Long
}

/**
 * @param readable false when the file is not a parseable APK at all — the
 *   strongest signal that the download is not what it claims to be.
 * @param versionCode the archive's own `versionCode`, 0 when unreadable.
 * @param signerReadable whether the platform reported *anything* about the
 *   archive's signer. OEM builds differ here, and "could not tell" must not be
 *   reported as "differs" — nor as "matches".
 * @param signerMatches the archive is signed by the certificate this app is
 *   installed with. Only meaningful when [signerReadable].
 */
data class ApkFacts(
    val readable: Boolean,
    val versionCode: Long,
    val signerReadable: Boolean,
    val signerMatches: Boolean,
) {
    companion object {
        /**
         * Nothing could be read: the file is not an APK the platform can open, so
         * the download is refused rather than passed on with a shrug.
         */
        val Unreadable = ApkFacts(
            readable = false,
            versionCode = 0L,
            signerReadable = false,
            signerMatches = false,
        )
    }
}

/** The [PackageManager] answers, read the way the installer will read them. */
@Singleton
class PackageManagerApkArchiveInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkArchiveInspector {

    private val signingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    override fun inspect(file: File): ApkFacts {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags)
        }.getOrNull()
        if (archive == null) return ApkFacts.Unreadable

        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        val ours = installedSignature()
        val theirs = signatureOf(archive)
        val readable = ours != null && theirs != null
        return ApkFacts(
            readable = true,
            versionCode = code,
            signerReadable = readable,
            signerMatches = readable && ours!!.contentEquals(theirs),
        )
    }

    override fun installedSignature(): ByteArray? = runCatching {
        signatureOf(context.packageManager.getPackageInfo(context.packageName, signingFlags))
    }.getOrNull()

    override fun installedVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    /**
     * The certificate a package is signed with.
     *
     * `apkContentsSigners` is the current signer and `signingCertificateHistory`
     * the rotation chain; which one is populated is the platform's own decision,
     * so both are read. A rotated signer still ends up compared against this app's
     * certificate, which is the point.
     */
    private fun signatureOf(info: PackageInfo?): ByteArray? {
        info ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = runCatching { info.signingInfo }.getOrNull() ?: return null
            val current = runCatching { signing.apkContentsSigners }.getOrNull()
            if (!current.isNullOrEmpty()) return current.first().toByteArray()
            val history = runCatching { signing.signingCertificateHistory }.getOrNull()
            return history?.firstOrNull()?.toByteArray()
        }
        @Suppress("DEPRECATION")
        return info.signatures?.firstOrNull()?.toByteArray()
    }
}
