package com.orion.store.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Android's developer-verification component (`com.google.android.verifier`), pushed to every device, which can slow or block
 *  sideloaded installs. Detection is read-only; turning it off runs the commands Google documents (reversible) through whatever
 *  privileged route the user set up. Disabling for the current user is the recommended lever; uninstall-for-user is the fallback
 *  OEMs that block the disable still allow. The globals turn off Play Protect's verify hooks without removing anything. */
object Verifier {
    const val PKG = "com.google.android.verifier"

    enum class State { ACTIVE, OFF, ABSENT }

    /** Global verify hooks and the value that switches each off. Their old values are saved first so Revert puts them back. */
    val GLOBALS = listOf("package_verifier_user_consent" to "-1", "package_verifier_enable" to "0", "verifier_verify_adb_installs" to "0")
    const val DISABLE_CMD = "pm disable-user --user 0 $PKG"
    /** Fallback when an OEM blocks disable-user (component stays enabled afterwards). Keeps the APK, so Revert can bring it back. */
    const val UNINSTALL_CMD = "pm uninstall -k --user 0 $PKG"
    val RESTORE_CMDS = listOf("cmd package install-existing --user 0 $PKG", "pm enable --user 0 $PKG")

    /** ACTIVE while the component is installed and enabled for this user, OFF once disabled or removed for this user, ABSENT on phones that never got it. */
    fun state(ctx: Context): State = runCatching {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(PKG, PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        val on = info.flags and ApplicationInfo.FLAG_INSTALLED != 0 && pm.getApplicationEnabledSetting(PKG).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (on) State.ACTIVE else State.OFF
    }.getOrDefault(State.ABSENT)

    fun active(ctx: Context) = state(ctx) == State.ACTIVE

    /** pm and settings print their refusals instead of failing; this pulls out the line that says why, if there is one. */
    fun refusal(out: String): String? {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // A stack trace's "SomeException: reason" line beats its "Exception occurred while executing" header
        val l = lines.firstOrNull { "Exception: " in it } ?: lines.firstOrNull { l -> listOf("Error", "Failure", "not allowed", "Cannot", "denied").any { it in l } } ?: return null
        return l.substringAfterLast("Exception: ").removePrefix("Error: ").trimEnd('.').ifBlank { null }
    }
}
