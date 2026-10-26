package com.orion.store.data

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class HttpError(val code: Int, val body: String) : IOException("Request failed with status $code")

/** System DNS first. When it fails or answers with a sinkhole (0.0.0.0, loopback: what DNS ad blockers and some ISPs hand out for GitHub hosts),
 *  Cloudflare and then Google answer over HTTPS instead, so a poisoned resolver no longer empties the store. */
private object FallbackDns : Dns {
    private val doh by lazy {
        val base = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        fun ips(vararg a: String) = a.map(InetAddress::getByName)
        listOf(
            DnsOverHttps.Builder().client(base).url("https://cloudflare-dns.com/dns-query".toHttpUrl()).bootstrapDnsHosts(ips("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")).build(),
            DnsOverHttps.Builder().client(base).url("https://dns.google/dns-query".toHttpUrl()).bootstrapDnsHosts(ips("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")).build(),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
        if (!sys.isNullOrEmpty()) return sys
        for (d in doh) runCatching { d.lookup(hostname) }.getOrNull()?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }?.takeIf { it.isNotEmpty() }?.let { return it }
        throw UnknownHostException(hostname)
    }
}

object Net {
    @Volatile private var cacheDir: File? = null
    /** Called from OrionApp before anything fetches, so the catalog client gets its disk cache. */
    fun init(ctx: Context) { cacheDir = File(ctx.cacheDir, "http") }

    val client: OkHttpClient = OkHttpClient.Builder().dns(FallbackDns).connectTimeout(Defaults.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Defaults.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS).callTimeout(Defaults.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    /** Catalog and mirror bodies are big on slow mobile data: no 8 second cap on the whole call, only a stall timeout.
     *  The disk cache lets the mirror revalidate with its ETag (a 304 when nothing changed) and serves the last good copy offline. */
    val catalog: OkHttpClient by lazy {
        client.newBuilder().callTimeout(120, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .apply { cacheDir?.let { cache(Cache(it, 64L * 1024 * 1024)) } }.build()
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap(), noStore: Boolean = false, c: OkHttpClient = client): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url); headers.forEach(rb::header); if (noStore) rb.header("Cache-Control", "no-store")
        c.newCall(rb.build()).execute().use { r -> val b = r.body.string(); if (!r.isSuccessful) throw HttpError(r.code, b); b }
    }

    suspend fun retry(url: String, retries: Int = 3, backoff: Long = 1000, noStore: Boolean = false, c: OkHttpClient = client): String {
        repeat(retries - 1) { i -> runCatching { return text(url, noStore = noStore, c = c) }; delay(backoff * (i + 1)) }
        return text(url, noStore = noStore, c = c)
    }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())); headers.forEach(rb::header)
        client.newCall(rb.build()).execute().use { it.code to it.body.string() }
    }

    suspend fun alive(url: String, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(Request.Builder().url(url).head().build()).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
    }
}

/** Every host serving a raw GitHub file, in order: GitHub itself, then raw.githack and jsDelivr for networks that block raw.githubusercontent.com. Other URLs stay as they are. */
fun rawMirrors(url: String): List<String> {
    val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$").find(url.trim()) ?: return listOf(url)
    val (o, r, b, p) = m.destructured
    return listOf(url, "https://raw.githack.com/$o/$r/$b/$p", "https://cdn.jsdelivr.net/gh/$o/$r@$b/$p")
}

data class CatalogLoad(val rawApps: JsonElement?, val mirror: Map<String, List<CatalogProcessor.Release>>?, val config: StoreConfig?, val source: String, val devError: String? = null, val failed: Boolean = false,
    val stars: Map<String, Int>? = null)

/** Last good catalog, already processed, so boot paints from disk while the network refresh runs. */
@Serializable data class CatalogCache(val apps: List<AppItem>, val config: StoreConfig? = null, val source: String = "")

class Repository(private val ctx: Context) {
    fun embeddedApps(): JsonElement = json.parseToJsonElement(ctx.assets.open("local_apps.json").bufferedReader().use { it.readText() })

    private val cache = AtomicFile(File(ctx.filesDir, "catalog.json"))
    fun cached(): CatalogCache? = runCatching { json.decodeFromString(CatalogCache.serializer(), cache.readFully().decodeToString()) }.getOrNull()
    fun saveCache(c: CatalogCache) {
        val out = cache.startWrite()
        runCatching { out.write(json.encodeToString(CatalogCache.serializer(), c).toByteArray()); cache.finishWrite(out) }.onFailure { cache.failWrite(out) }
    }

    private fun bust(url: String, ts: String) = "$url${if ('?' in url) '&' else '?'}t=$ts"
    private fun host(url: String) = runCatching { URI(url).host }.getOrNull().orEmpty()

    /** One catalog load. Hosts that could not be reached at all are skipped for the rest of it, so a blocked host costs one timeout, not one per file. */
    private class Attempt { val dead: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet() }

    private suspend fun first(urls: List<String>, a: Attempt, fetch: suspend (String) -> String): String {
        var last: Exception = IOException("No reachable host")
        for (u in urls.distinct()) {
            val h = host(u); if (h in a.dead) continue
            try { return fetch(u) } catch (e: HttpError) { last = e } catch (e: Exception) { last = e; a.dead += h }
        }
        throw last
    }

    /** The mirror is ~30 MB of JSON (under 2 MB gzipped). It streams straight into release lists, so a low-RAM phone never holds the text or a JSON tree;
     *  revalidates against the HTTP cache; tries every host that serves it; and falls back to the last good copy on disk when none answers. */
    private suspend fun fetchMirror(url: String, a: Attempt): Map<String, List<CatalogProcessor.Release>>? = withContext(Dispatchers.IO) {
        fun call(u: String, cc: CacheControl) = Net.catalog.newCall(Request.Builder().url(u).cacheControl(cc).build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, ""); CatalogProcessor.decodeMirror(r.body.byteStream())
        }
        val hosts = rawMirrors(url)
        for (u in hosts) {
            val h = host(u); if (h in a.dead) continue
            try { return@withContext call(u, CacheControl.Builder().noCache().build()) } catch (_: HttpError) {} catch (_: Exception) { a.dead += h }
        }
        hosts.firstNotNullOfOrNull { u -> runCatching { call(u, CacheControl.FORCE_CACHE) }.getOrNull() }
    }

    /** A few KB of raw file from the data branch, revalidated through the HTTP cache: no GitHub API call, so guests keep their 60 an hour. */
    private suspend fun fetchStars(a: Attempt): Map<String, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            first(rawMirrors(Defaults.STARS_JSON), a) { u ->
                Net.catalog.newCall(Request.Builder().url(u).cacheControl(CacheControl.Builder().noCache().build()).build()).execute().use { r -> if (!r.isSuccessful) throw HttpError(r.code, ""); r.body.string() }
            }.let { json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), it) }
                .mapKeys { it.key.lowercase() }
        }.getOrNull()
    }

    private suspend fun fetchSet(config: StoreConfig, appsFallback: String?, ts: String, a: Attempt): Triple<JsonElement, Map<String, List<CatalogProcessor.Release>>?, Map<String, Int>?> = coroutineScope {
        val appsUrl = config.appsJsonUrl.ifEmpty { appsFallback ?: throw IOException("Dev config is missing appsJsonUrl.") }
        val mirrorUrl = config.mirrorJsonUrl?.ifEmpty { null } ?: Defaults.MIRROR_JSON
        val mirror = async { fetchMirror(mirrorUrl, a) }
        val stars = async { fetchStars(a) }
        val apps = async { first(rawMirrors(appsUrl) + listOfNotNull(appsFallback), a) { Net.text(bust(it, ts), noStore = true, c = Net.catalog) } }
        Triple(json.parseToJsonElement(apps.await()), mirror.await(), stars.await())
    }

    /** Port of App.tsx loadApps network section: dev config → GitHub → jsDelivr → raw.githack → embedded. */
    suspend fun loadCatalog(s: Settings, manual: Boolean): CatalogLoad {
        val useDev = s.devConfigEnabled; val remote = useDev || s.useRemoteJson
        val ts = System.currentTimeMillis().toString(); val a = Attempt()
        if (!remote) return if (s.loadLocalData) CatalogLoad(embeddedApps(), null, null, "Disabled") else CatalogLoad(null, null, null, "Disabled & Blocked Local", failed = true)
        var devError: String? = null
        if (useDev) {
            try {
                val url = s.devConfigUrl.trim().ifEmpty { throw IOException("Enter a developer config URL before reloading.") }
                val cfgText = try { Net.retry(bust(url, ts), 1, noStore = true) } catch (e: HttpError) { throw IOException("Dev config request failed with ${e.code}") }
                val cfg = CatalogProcessor.parseConfig(cfgText)
                if (cfg.appsJsonUrl.isEmpty()) throw IOException("Dev config is missing appsJsonUrl.")
                val (apps, mirror, stars) = try { fetchSet(cfg, null, ts, a) } catch (e: HttpError) { throw IOException("Dev apps request failed with ${e.code}") }
                return CatalogLoad(apps, mirror, cfg, "Developer Config", stars = stars)
            } catch (e: Exception) { devError = e.message ?: "Failed to load developer config." }
        } else {
            for (i in Defaults.CONFIG_URLS.indices) {
                val h = host(Defaults.CONFIG_URLS[i]); if (h in a.dead) continue
                runCatching {
                    val cfg = try { CatalogProcessor.parseConfig(Net.retry("${Defaults.CONFIG_URLS[i]}?t=$ts", 1, noStore = true)) } catch (e: IOException) { if (e !is HttpError) a.dead += h; throw e }
                    val (apps, mirror, stars) = fetchSet(cfg, Defaults.APPS_URLS[i], ts, a)
                    return CatalogLoad(apps, mirror, cfg, Defaults.MIRROR_LABELS[i], stars = stars)
                }
            }
        }
        return if (s.loadLocalData) CatalogLoad(embeddedApps(), null, null, if (useDev) "Developer Config Failed (Local Fallback)" else "Offline (Local)", devError)
        else CatalogLoad(null, null, null, if (useDev) "Developer Config Failed" else "Failed (Offline)", devError, failed = true)
    }

}

private val notesCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** [tag]'s release notes for [app], cleaned for display and kept for the session (GitHub allows 60 anonymous calls an hour).
 *  Empty when the release has none, null when they could not be fetched. */
suspend fun fetchReleaseNotes(app: AppItem, tag: String, githubToken: String): String? {
    val (url, field) = releaseNotesApi(app, tag) ?: return null
    notesCache[url]?.let { return it }
    val h = if ("api.github.com" in url && githubToken.isNotEmpty()) mapOf("Authorization" to "Bearer $githubToken") else emptyMap()
    return runCatching { cleanNotes(CatalogProcessor.str((json.parseToJsonElement(Net.text(url, h)) as kotlinx.serialization.json.JsonObject)[field]).orEmpty()) }
        .getOrNull()?.also { notesCache[url] = it }
}
