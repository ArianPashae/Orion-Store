package com.orion.store.platform

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Android's developer-verification component (`com.google.android.verifier`), pushed to every device, which can slow or block
 *  sideloaded installs. Detection is read-only; turning it off runs the commands Google documents (reversible) through whatever
 *  privileged route the user set up. Disabling for the current user is the recommended lever; uninstall-for-user is the fallback
 *  OEMs that block the disable still allow. The globals turn off Play Protect's verify hooks without removing anything. */
object Verifier {
    const val PKG = "com.google.android.verifier"

    /** Google's documented sequence: disable the verify hooks, then disable the component for this user. */
    val DISABLE_CMDS = listOf(
        "settings put global package_verifier_user_consent -1",
        "settings put global package_verifier_enable 0",
        "settings put global verifier_verify_adb_installs 0",
        "pm disable-user --user 0 $PKG",
    )
    /** Fallback when an OEM blocks disable-user (component stays enabled afterwards). */
    const val UNINSTALL_CMD = "pm uninstall --user 0 $PKG"

    /** True while the verifier component is present and enabled on this device. The popup keys on this, so a secured device never sees it again. */
    fun active(ctx: Context): Boolean = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationInfo(PKG, 0) // throws when uninstalled for this user
        pm.getApplicationEnabledSetting(PKG).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }.getOrDefault(false)
}
