package ru.papajon.meshcentralmobile.settings

import android.content.Context
import java.security.MessageDigest
import ru.papajon.meshcentralmobile.mesh.DesktopQuality

/** Non-secret display preferences isolated by profile and device. No endpoint/credentials in keys. */
class DesktopPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop_display", Context.MODE_PRIVATE)
    private fun key(profile: String, node: String) = MessageDigest.getInstance("SHA-256")
        .digest("${profile.length}:$profile$node".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun loadPipAutomatic(): Boolean = prefs.getBoolean("pip.automatic", false)
    fun savePipAutomatic(enabled: Boolean) { prefs.edit().putBoolean("pip.automatic", enabled).apply() }
    fun load(profile: String, node: String): DesktopQuality {
        val key = key(profile, node)
        return DesktopQuality(prefs.getInt("$key.q", 60), prefs.getInt("$key.f", 30),
            prefs.getInt("$key.s", 1024)).normalized()
    }
    fun save(profile: String, node: String, quality: DesktopQuality) {
        val key = key(profile, node)
        val value = quality.normalized()
        prefs.edit().putInt("$key.q", value.jpegQuality).putInt("$key.f", value.frameRate)
            .putInt("$key.s", value.scaling).apply()
    }
}
