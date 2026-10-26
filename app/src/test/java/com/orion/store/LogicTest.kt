package com.orion.store

import com.orion.store.data.*
import com.orion.store.platform.Verifier
import com.orion.store.ui.bundleIssueBody
import com.orion.store.ui.cleanPermName
import com.orion.store.ui.isProfane
import com.orion.store.ui.webSettingsImport

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun verifierCommands() {
        // Every destructive command must stay scoped to the current user: a bare pm disable/uninstall would hit all users and can brick.
        Verifier.DISABLE_CMDS.filter { it.startsWith("pm ") }.forEach { assertTrue(it, "--user 0" in it) }
        assertTrue(Verifier.UNINSTALL_CMD.contains("--user 0") && Verifier.UNINSTALL_CMD.endsWith(Verifier.PKG))
        // Documented global keys, set to their off values
        assertTrue(Verifier.DISABLE_CMDS.contains("settings put global package_verifier_enable 0"))
        assertTrue(Verifier.DISABLE_CMDS.contains("settings put global verifier_verify_adb_installs 0"))
        assertTrue(Verifier.DISABLE_CMDS.last().endsWith(Verifier.PKG))
    }

    @Test fun versions() {
        assertEquals("1.2.3", normalizeVersion("v1.2.3-beta+5"))
        assertTrue(compareVersions("1.10", "1.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(0, compareVersions(null, "1.0"))
        assertTrue(isSameVersion("v2.0", "2.0-rc"))
        assertTrue(isStoreUpdateAvailable("1.5.5", "1.6.0"))
        assertFalse(shouldForceStoreUpdate("1.5.5", "1.5.5"))
    }

    @Test fun imageLinks() {
        assertNull(explainRejectedImageLink("https://example.com/a.png"))
        assertNull(explainRejectedImageLink("https://raw.githubusercontent.com/o/r/main/x"))
        assertEquals("Link must start with http:// or https://", explainRejectedImageLink("ftp://x/a.png"))
        assertNotNull(explainRejectedImageLink("https://example.com/page"))
        assertEquals("https://raw.githubusercontent.com/o/r/main/s.png", sanitizeGitHubImageUrl("https://github.com/o/r/blob/main/s.png"))
        assertEquals("https://x.com/a.png", sanitizeGitHubImageUrl("https://x.com/a.png"))
        assertEquals("https://play-lh.googleusercontent.com/abc=h720-rw", optimizedImageUrl("https://play-lh.googleusercontent.com/abc=w526-h296", null, 720))
        assertEquals("https://play-lh.googleusercontent.com/abc=w256-h256-rw", optimizedImageUrl("https://play-lh.googleusercontent.com/abc", 256, 256))
        assertTrue(optimizedImageUrl("https://raw.githubusercontent.com/o/r/main/s.png", null, 720).startsWith("https://wsrv.nl/?url="))
    }

    @Test fun repoParsing() {
        assertEquals(RepoSource("github", "o/app", "o", "app"), parseRepoSource("https://github.com/o/app.git"))
        assertEquals(RepoSource("github", "o/app", "o", "app"), parseRepoSource("https://github.com/o/app/tree/main/src"))
        assertEquals(RepoSource("gitlab", "g/sub/app", "g", "app", "gitlab.com"), parseRepoSource("https://gitlab.com/g/sub/app/-/releases"))
        assertEquals("codeberg", parseRepoSource("https://codeberg.org/o/r")?.provider)
        assertNull(parseRepoSource("https://example.com/o/r"))
        assertNull(parseRepoSource("https://github.com/o"))
        assertEquals("com.foo.bar", extractPackageName("android {\n  namespace = \"x.y\"\n  defaultConfig { applicationId \"com.foo.bar\" }\n}"))
        assertEquals("org.x", extractPackageName("<manifest package=\"org.x\">"))
        assertEquals("My Cool App", humanizeRepoName("my-cool_app.git"))
        assertEquals("my-app-2", slugId("  My App 2!! "))
    }

    @Test fun submissionUrls() {
        val u = submissionIssueUrl("[]", 2, "Foo", "1.5.5")
        assertTrue(u.startsWith("https://github.com/RookieEnough/Orion-Data/issues/new?title="))
        assertTrue(enc("App Submission [2 Apps] - Foo") in u)
        val body = bundleIssueBody("T", "D", "fa-bolt", "c", listOf("a", "zz"), listOf(AppItem(id = "a", name = "A", packageName = "p.a")), null)
        assertTrue("- **A** (`a`) · `p.a`" in body)
        assertTrue("- zz" in body)
        assertFalse("Submitted by" in body)
        assertFalse("\n\n" in body)
    }

    @Test fun categories() {
        val music = AppItem(id = "m", name = "Tunes", description = "Listen to your album collection", category = "Media")
        val tool = AppItem(id = "t", name = "Box", category = "Utility", tags = listOf("Privacy"))
        assertTrue(appMatchesCategoryFilter(music, "Music")) // preset keyword in description
        assertTrue(appMatchesCategoryFilter(tool, "privacy")) // tag, case-insensitive
        assertTrue(appMatchesCategoryFilter(tool, "Utilities")) // preset raw category
        assertFalse(appMatchesCategoryFilter(tool, "Music"))
        val cards = homepageCategoryCards(listOf(music, tool, tool.copy(id = "t2")))
        assertEquals(1, cards.first { it.label == "Music" }.count)
        assertEquals(2, cards.first { it.label == "Utilities" }.count)
        assertEquals(listOf("All", "Music", "Privacy", "Utilities", "Media", "Utility"), availableFilterCategories(listOf(music, tool)))
    }

    @Test fun catalogCacheRoundTrip() {
        val c = CatalogCache(listOf(AppItem(id = "a", name = "A", variants = listOf(AppVariant("ARM64", "https://x/a.apk", 42)))), null, "GitHub")
        assertEquals(c, json.decodeFromString(CatalogCache.serializer(), json.encodeToString(CatalogCache.serializer(), c)))
    }

    @Test fun webCarryOver() {
        val s = webSettingsImport("""{"state":{"adWatchCount":42,"unlockedBadges":["Dino Rookie"],"theme":"dark","isLegend":true},"version":0}""", Settings())
        assertEquals(42, s.adWatchCount); assertEquals(listOf("Dino Rookie"), s.unlockedBadges); assertEquals("dark", s.theme); assertTrue(s.isLegend)
        // A clashing field type drops to the identity fields, which still carry the profile counters
        val f = webSettingsImport("""{"state":{"adWatchCount":7,"unlockedBadges":{"bad":1}}}""", Settings())
        assertEquals(7, f.adWatchCount)
        assertEquals(Settings(), webSettingsImport("not json", Settings()))
    }

    @Test fun releaseNotes() {
        val body = "# Orion Store 2.1.5\r\n\r\nIntro text.\r\n\r\n> **Updating?** Install over it.\r\n\r\n## 🔄 Hands-Free Updates\r\n\r\n" +
            "* **Updates That Install Themselves:** Turn on **Auto-update apps** in Settings.\r\n* **Safe by Design:** Checks every download.\r\n\r\n---\r\n\r\n" +
            "### Fixes\r\n- Fixed apps showing as unavailable. Your list is kept.\r\n- See [the docs](https://x.y) for `more`.\r\n## Empty section\r\n"
        val s = releaseHighlights(body)
        assertEquals(listOf("🔄 Hands-Free Updates", "Fixes"), s.map { it.title })
        assertEquals(listOf("Updates That Install Themselves", "Safe by Design"), s[0].items)
        assertEquals(listOf("Fixed apps showing as unavailable", "See the docs for more"), s[1].items)
        assertTrue(releaseHighlights("- " + "word ".repeat(40)).single().items.single().let { it.length <= 90 && it.endsWith("…") })
        val r = parseReleases("""[{"tag_name":"v2.0.5","name":"Orion-RE : The Labyrinth Update","published_at":"2026-10-14T06:47:22Z","html_url":"u","body":"- Fix"},{"draft":true,"tag_name":"x"}]""")
        assertEquals(1, r.size); assertEquals("2.0.5", r[0].version); assertEquals("The Labyrinth Update", r[0].name); assertEquals(1_791_960_442_000L, r[0].publishedAt)
    }

    @Test fun cleanNotes() {
        val md = "## What's Changed\r\n* **Fix** crash on `start` by @dev in https://github.com/a/b/pull/3 :tada:\r\n- [x] See [docs](https://x.y)\r\n![shot](https://i.png)\r\n" +
            "| a | b |\r\n```\r\ncode\r\n```\r\n## Fixes\r\n- Faster boot\r\n\r\n**Full Changelog**: https://github.com/a/b/compare/v1...v2\r\n## New Contributors\r\n* @x made it\r\nsha256 0123456789abcdef0123456789abcdef"
        assertEquals("• Fix crash on start\n• See docs\n\n## Fixes\n• Faster boot", cleanNotes(md))
    }

    @Test fun antiFarming() {
        val t = 1_790_000_000_000L
        // Ads: 30 s apart at least, AD_DAILY per rolling day, then nothing until the oldest falls out of the window
        var s = Settings()
        s = s.incrementAdWatch(t); assertEquals(1, s.adWatchCount)
        s = s.incrementAdWatch(t + 1_000); assertEquals(1, s.adWatchCount)
        repeat(30) { s = s.incrementAdWatch(t + (it + 1) * Xp.AD_GAP_MS) }
        assertEquals(Xp.AD_DAILY, s.adWatchCount); assertNotNull(s.adBlockReason(t + 40 * Xp.AD_GAP_MS))
        assertNull(s.adBlockReason(t + Xp.DAY_MS + 1)); assertEquals(Xp.AD_DAILY + 1, s.incrementAdWatch(t + Xp.DAY_MS + 1).adWatchCount)
        // Game XP: 50 per run, GAME_DAILY per day, resets the next day
        var g = Settings(); repeat(20) { g = g.addGameXP(1e9, t) }
        assertEquals(Xp.GAME_DAILY, g.gameXP); assertEquals(Xp.GAME_DAILY + 50, g.addGameXP(80.0, t + Xp.DAY_MS).gameXP)
        // Imports and leaderboard: a hand-edited save fails, real progress passes
        assertTrue(Xp.plausible(Settings(adWatchCount = 300, gameXP = 2_000, submissionCount = 12), t))
        assertFalse(Xp.plausible(Settings(adWatchCount = 999_999), t)); assertFalse(Xp.plausible(Settings(adWatchCount = -1), t))
        assertFalse(Xp.plausible(Settings(submissionCount = 1, changeRequestCount = 5), t))
    }

    @Test fun channelPackages() {
        val cs = AppItem(id = "cs", name = "CloudStream", packageName = "com.lagradost.cloudstream3")
        val installed = listOf("com.lagradost.cloudstream3.prerelease", "com.lagradost.cloudstream3.plugins.manager", "com.lagradost.cloudstream3x", "com.lagradost.cloudstream3.beta2")
        val c = packageCandidates(cs, null, installed)
        assertTrue("com.lagradost.cloudstream3.prerelease" in c); assertTrue("com.lagradost.cloudstream3.beta2" in c)
        assertFalse("com.lagradost.cloudstream3.plugins.manager" in c); assertFalse("com.lagradost.cloudstream3x" in c)
        assertEquals("com.lagradost.cloudstream3", c[0]); assertEquals("x.y", packageCandidates(cs, "x.y").first())
    }

    @Test fun aiAnswers() {
        val a = com.orion.store.platform.OrionAi.parse("<think>hmm</think>Try Metrolist [3] or ViMusic [7, 9]. **Both** are free.")
        assertEquals("Try Metrolist or ViMusic. Both are free.", a.reply); assertEquals(listOf(3, 7, 9), a.picks)
        val apps = listOf(AppItem(id = "m", name = "Metrolist", category = "Music", description = "YouTube Music client"), AppItem(id = "k", name = "Keyboard", category = "Tools"))
        assertEquals(listOf(0), com.orion.store.platform.OrionAi.matches(apps, "I want to listen to songs for free", 5))
        assertTrue(com.orion.store.platform.OrionAi.matches(apps, "hey whats orion", 5).isEmpty())
    }

    @Test fun managedAndIgnored() {
        val a = AppItem(id = "a", name = "A", latestVersion = "2.0")
        val s = Settings(installedVersions = mapOf("a" to "1.0"), installSources = mapOf("a" to "com.android.vending"))
        assertEquals(listOf(a), availableUpdates(listOf(a), s, 0))
        val skip = s.copy(skipOtherStores = true)
        assertTrue(availableUpdates(listOf(a), skip, 0).isEmpty())
        assertTrue(isManaged("a", skip.withManaged("a", true)))
        assertFalse(isManaged("a", s.withManaged("a", false)))
        assertTrue(s.withManaged("a", false).withManaged("a", true).appManage.isEmpty())
        assertTrue(isManaged("a", skip.copy(installSources = mapOf("a" to "com.google.android.packageinstaller"))))
        val now = 10L * ONE_WEEK_MS
        assertTrue(isUpdateIgnored(a, s.ignoreUpdate("a", "week", null, now), now + 1000))
        assertFalse(isUpdateIgnored(a, s.ignoreUpdate("a", "week", null, now), now + ONE_WEEK_MS))
        assertTrue(isUpdateIgnored(a, s.ignoreUpdate("a", "version", "v2.0", now), now))
        assertFalse(isUpdateIgnored(a.copy(latestVersion = "2.1"), s.ignoreUpdate("a", "version", "2.0", now), now))
    }

    @Test fun categoryWholeWords() {
        val budget = AppItem(id = "b", name = "Pocket Budget", description = "Track expenses with a simple tracker", category = "Utility")
        val player = AppItem(id = "p", name = "Tunes", description = "Offline music player", category = "Media")
        assertFalse(appMatchesCategoryFilter(budget, "Music"))
        assertTrue(appMatchesCategoryFilter(player, "Music"))
    }

    @Test fun installFailures() {
        assertTrue("uninstall" in installFailureText("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package x signatures do not match]", "X").second)
        assertTrue("Secure Folder" in installFailureText("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "X").second)
        assertEquals("Phone storage is full", installFailureText("INSTALL_FAILED_INSUFFICIENT_STORAGE", "X").first)
        assertTrue("INSTALL_FAILED_WEIRD" in installFailureText("Failure [INSTALL_FAILED_WEIRD]", "X").second)
    }

    @Test fun misc() {
        assertEquals(1, Xp.level(60)); assertEquals(2, Xp.level(61)); assertEquals(10, Xp.level(1185)); assertEquals(9, Xp.level(1184))
        assertEquals(30, Xp.level(14355)); assertEquals(50, Xp.level(55125)); assertEquals(Xp.start(50), 55125L)
        assertTrue(isProfane("b4d guy", listOf("bad")))
        assertFalse(isProfane("good", listOf("bad")))
        assertEquals("FINE LOCATION", cleanPermName("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test fun betaInstallIsNotAnUpdateFromStable() {
        val beta = VersionOption("Beta", "20261020", variants = listOf(AppVariant("Universal", "u")))
        val stable = VersionOption("Stable", "1.5.0", variants = listOf(AppVariant("Universal", "s")))
        val app = AppItem(id = "x", availableVersions = listOf(stable, beta))
        // Installed the Beta (names itself 1.4.2) through Orion: no update on its own channel, a new Beta is one
        assertFalse(hasAvailableUpdate(app, "1.4.2", "Beta", InstalledBuild("1.4.2", "20261020")))
        assertTrue(hasAvailableUpdate(app.copy(availableVersions = listOf(stable, beta.copy(version = "20261101"))), "1.4.2", "Beta", InstalledBuild("1.4.2", "20261020")))
        // No record: an exact catalog name still counts as that build
        assertFalse(hasAvailableUpdate(app, "20261020", "Beta"))
        assertTrue(hasAvailableUpdate(app, "1.4.0", "Stable"))
    }
}
