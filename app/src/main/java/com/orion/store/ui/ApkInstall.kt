package com.orion.store.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.orion.store.OrionActivity
import com.orion.store.data.*
import com.orion.store.platform.AppTracker
import com.orion.store.platform.DhizukuBridge
import com.orion.store.platform.Downloads
import com.orion.store.platform.ResultHost
import com.orion.store.platform.WirelessAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the opened file is, read from the APK itself. */
private class Apk(val file: File, val label: String, val pkg: String, val version: String, val code: Long, val icon: Bitmap?, val installed: String?, val installedCode: Long?, val clash: Clash?)

private sealed interface ApkStep {
    data object Reading : ApkStep
    data class Ready(val apk: Apk) : ApkStep
    data class Installing(val apk: Apk) : ApkStep
    data class Scan(val apk: Apk, val vt: com.orion.store.platform.VtScan) : ApkStep
    data class Done(val apk: Apk) : ApkStep
    data class Failed(val apk: Apk?, val title: String, val body: String) : ApkStep
}

/** Opens APKs from file managers, browsers and share sheets as a sheet over the app that sent them, then installs them
 *  through the fastest route the user set up: root, Shizuku, Dhizuku or built-in wireless debugging, which skips Android's 24-hour wait. */
class ApkInstallActivity : ComponentActivity() {
    private val tracker by lazy { AppTracker(applicationContext) }
    private var step by mutableStateOf<ApkStep>(ApkStep.Reading)
    private var pending: ((Int, Intent?) -> Unit)? = null
    private val results = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> pending?.also { pending = null }?.invoke(r.resultCode, r.data) }
    private var previousHost: ((Intent, (Int, Intent?) -> Unit) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(applicationContext); Downloads.attach(applicationContext)
        previousHost = ResultHost.launch
        ResultHost.launch = { i, cb -> pending = cb; results.launch(i) }
        tracker.root = Store.settings.flow.value.rootMode
        read(intent)
        setContent {
            val s by Store.settings.flow.collectAsState()
            CompositionLocalProvider(LocalS provides s) { OrionTheme(s) { Sheet(s) } }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); read(intent) }

    override fun onDestroy() {
        ResultHost.launch = previousHost
        (step as? ApkStep.Ready)?.apk?.file?.delete(); (step as? ApkStep.Failed)?.apk?.file?.delete(); (step as? ApkStep.Scan)?.apk?.file?.delete()
        super.onDestroy()
    }

    private fun read(i: Intent?) {
        val uri = i?.data ?: (if (i?.action == Intent.ACTION_SEND) @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null)
        if (uri == null) { finish(); return }
        step = ApkStep.Reading
        lifecycleScope.launch {
            step = withContext(Dispatchers.IO) { runCatching { parse(uri) }.getOrNull() }?.let { ApkStep.Ready(it) }
                ?: ApkStep.Failed(null, "This file can't be installed", "It isn't a working APK. Split bundles (.apks, .xapk) aren't supported yet.")
            // Wireless debugging connects while the sheet is read, so Install doesn't sit on discovery
            if (step is ApkStep.Ready && route(Store.settings.flow.value)?.first == "adb") WirelessAdb.warm(applicationContext)
        }
    }

    /** Copies the file in first: content URIs only stay readable while this activity lives, and every install route needs a real file. */
    private fun parse(uri: Uri): Apk? {
        // Files from earlier sheets that were swiped away mid-copy
        Downloads.dir().listFiles { x -> x.name.startsWith("Ext_") && System.currentTimeMillis() - x.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val f = Downloads.file("Ext_${System.currentTimeMillis()}.apk") ?: return null
        contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return null
        if (!tracker.isValidApk(f)) { f.delete(); return null }
        val pm = packageManager
        val pi = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: run { f.delete(); return null }
        val ai = pi.applicationInfo?.apply { sourceDir = f.absolutePath; publicSourceDir = f.absolutePath }
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        val label = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }?.takeIf { it.isNotBlank() && it != pi.packageName } ?: name?.removeSuffix(".apk") ?: pi.packageName
        val inst = tracker.appInfo(pi.packageName).takeIf { it.installed }
        return Apk(f, label, pi.packageName, pi.versionName.orEmpty(), androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi),
            ai?.let { runCatching { it.loadIcon(pm).toBitmap(144, 144) }.getOrNull() }, inst?.version, inst?.versionCode, tracker.clash(f)?.second)
    }

    /** The same order the store uses for its own installs. */
    private fun route(s: Settings): Pair<String, String>? = when {
        s.rootMode -> "root" to "Installs with root, no prompts and no 24-hour wait"
        s.useShizuku -> "shizuku" to "Installs through Shizuku, no prompts and no 24-hour wait"
        s.useDhizuku -> "dhizuku" to "Installs through Dhizuku, no prompts"
        s.directInstall && WirelessAdb.supported -> "adb" to "Installs instantly through wireless debugging, skipping the 24-hour wait"
        else -> null
    }

    private fun install(apk: Apk, s: Settings, regular: Boolean = false) = lifecycleScope.launch {
        val r = if (regular) null else route(s)?.first
        if (r == null && !tracker.canRequestInstalls()) {
            step = ApkStep.Failed(apk, "Allow Orion to install apps", "Turn on \"Allow from this source\" for Orion Store, then come back and tap Install again.")
            runCatching { startActivity(tracker.installSettingsIntent()) }; return@launch
        }
        step = ApkStep.Installing(apk)
        val err = runCatching {
            when (r) {
                "root", "shizuku" -> tracker.installShizuku(apk.file.name, s.updateLock, if (s.installOptions) s.installFlags else emptyList())
                "dhizuku" -> DhizukuBridge.install(applicationContext, apk.file)
                "adb" -> { if (!WirelessAdb.connected) WirelessAdb.connect(applicationContext); WirelessAdb.install(applicationContext, apk.file) }
                else -> tracker.install(apk.file.name, "system", "")
            }
        }.exceptionOrNull()
        // The system installer reports cancel and failure alike; what is on the phone afterwards is the truth
        val now = withContext(Dispatchers.IO) { tracker.appInfo(apk.pkg) }
        val ok = err == null || (r == null && now.installed && now.versionCode == apk.code && now.versionCode != apk.installedCode)
        if (ok) { apk.file.delete(); step = ApkStep.Done(apk); return@launch }
        val msg = err?.message.orEmpty()
        if (r == null && "INSTALL_CANCELED" in msg) { step = ApkStep.Ready(apk); return@launch }
        // A connection problem is not an install failure: say what wireless debugging needs instead of a generic error
        if (r == "adb" && "INSTALL_" !in msg) { step = ApkStep.Failed(apk, "Wireless debugging didn't answer", "${msg.ifBlank { "Orion couldn't reach wireless debugging." }}\n\nTry again, or use Android's installer."); return@launch }
        val (t, b) = installFailureText(msg, apk.label)
        step = ApkStep.Failed(apk, t, b)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun Sheet(s: Settings) {
        val cs = MaterialTheme.colorScheme
        ModalBottomSheet({ finish() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerLow,
            sheetMaxWidth = 560.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
                when (val st = step) {
                    ApkStep.Reading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(56.dp)) }
                    is ApkStep.Ready -> {
                        Header(st.apk)
                        st.apk.clash?.let { c -> val (t, b) = clashText(c, st.apk.label); Note(Sym.GppMaybe, t, b, cs.errorContainer, cs.onErrorContainer) }
                        val r = route(s)
                        if (r != null) Note(Sym.Bolt, "Fast install", r.second, cs.secondaryContainer, cs.onSecondaryContainer)
                        else if (WirelessAdb.supported) Note(Sym.Schedule, "Skip the 24-hour wait", "Android may hold apps from unverified developers for a day. Turn on Skip the 24-hour wait in Orion's settings to install them right away.",
                            cs.surfaceContainerHigh, cs.onSurface) { TextButton({ startActivity(Intent(this@ApkInstallActivity, OrionActivity::class.java).setData("orionstore://settings/installer".toUri())); finish() }) { Text("Set up") } }
                        FilledTonalButton({ step = ApkStep.Scan(st.apk, com.orion.store.platform.VtScan(tracker, "apk:${st.apk.pkg}", st.apk.label, st.apk.pkg, s.virusTotalApiKey)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shapes = ButtonDefaults.shapes()) {
                            Icon(Sym.Security, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan with VirusTotal")
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ finish() }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                            Button({ install(st.apk, s) }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) {
                                Icon(Sym.InstallMobile, null); Spacer(Modifier.width(8.dp))
                                Text(when { st.apk.installed == null -> "Install"; st.apk.installedCode != null && st.apk.code > st.apk.installedCode -> "Update"; else -> "Reinstall" }, maxLines = 1)
                            }
                        }
                    }
                    // Your own VirusTotal key, saved with the store's: checks the exact file before it goes in
                    is ApkStep.Scan -> {
                        Header(st.apk)
                        Box(Modifier.padding(top = 16.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                            VtPanel(st.vt, com.orion.store.platform.ScanTarget(com.orion.store.platform.ScanTargetType.DOWNLOADED_APK, st.apk.label, st.apk.file.name), s.virusTotalApiKey,
                                save = { k -> Store.settings.update { it.copy(virusTotalApiKey = k) } }, revoke = { Store.settings.update { it.copy(virusTotalApiKey = "") }; st.vt.reset(); st.vt.view = com.orion.store.platform.VtView.KEY },
                                open = { tracker.open(it) }, onDone = { step = ApkStep.Ready(st.apk) })
                        }
                        if (st.vt.view != com.orion.store.platform.VtView.SCANNING) TextButton({ step = ApkStep.Ready(st.apk) }, Modifier.fillMaxWidth()) { Text("Back to install") }
                    }
                    is ApkStep.Installing -> {
                        Header(st.apk)
                        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(40.dp)); Spacer(Modifier.width(12.dp)); Text("Installing...", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    is ApkStep.Done -> {
                        Header(st.apk)
                        Note(Sym.CheckCircle, "Installed", "${st.apk.label} is ready. If it's in the Orion catalog, Orion keeps it updated from now on.", cs.primaryContainer, cs.onPrimaryContainer)
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ finish() }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) { Text("Done") }
                            packageManager.getLaunchIntentForPackage(st.apk.pkg)?.let { li ->
                                Button({ runCatching { startActivity(li) }; finish() }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) {
                                    Icon(Sym.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open")
                                }
                            }
                        }
                    }
                    is ApkStep.Failed -> {
                        st.apk?.let { Header(it) }
                        Note(Sym.ErrorOutline, st.title, st.body, cs.errorContainer, cs.onErrorContainer)
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ finish() }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) { Text("Close") }
                            st.apk?.let { a -> Button({ install(a, s, regular = route(s) != null) }, modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight), shapes = ButtonDefaults.shapes()) {
                                Text(if (route(s) != null) "Use Android's installer" else "Try again", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(a: Apk) {
        val cs = MaterialTheme.colorScheme
        Row(verticalAlignment = Alignment.CenterVertically) {
            a.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))) } ?: Blob(Sym.Android, cs.primary, 64)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(a.version.ifBlank { null }?.let { "Version $it" }, a.installed?.let { "installed $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${CatalogProcessor.formatSize(a.file.length())} · ${if (a.installed == null) "New app" else if (a.installedCode != null && a.code < a.installedCode) "Older than installed" else "Update"}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, action: (@Composable () -> Unit)? = null) =
        Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(20.dp), color = bg, contentColor = fg) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = fg.copy(.85f))
                    action?.invoke()
                }
            }
        }
}
