package ru.papajon.meshcentralmobile.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Read the installed signer only. This client never downloads or opens APK archives. */
class PackageSignatureInspector(private val context: Context) {
    @Suppress("DEPRECATION")
    fun installed(): InstalledRelease {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        val signature = signatures?.singleOrNull() ?: throw IllegalArgumentException("Expected one installed signer")
        val signer = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return InstalledRelease(info.packageName, version, signer)
    }
}
