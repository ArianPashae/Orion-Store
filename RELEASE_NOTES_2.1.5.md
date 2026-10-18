# Orion Store 2.1.5

Make Orion yours: updates that install themselves, your own quick toggles, Settings arranged your way, automatic cloud backup and a full root toolkit. Plus a tougher climb to the top, a fair leaderboard, stable-only updates, a working "App details in store" shortcut and bundles that remember where you were.

> **Updating from 2.0.x?** Install 2.1.5 over your current Orion Store. Your profile, XP, badges and settings come along. Levels are recalculated on the new XP curve, so your level number may read lower than before.

---

## 🔄 Hands-Free Updates

* **Updates That Install Themselves:** Turn on **Auto-update apps**, then **Install automatically** in **Settings → Network**. Every few hours, even with Orion closed and after a reboot, Orion checks for updates, downloads them and installs them over your current apps.
* **Safe by Design:** Orion reads installed versions straight from Android, checks every download is a valid APK of the very same app, and respects Wi-Fi only, Stable releases only and ignored updates. It waits for good battery and storage, and steps aside while you're using the store.
* **Silent Where Android Allows It:** With Root mode or Shizuku, every update installs silently. Without them, Android 12+ installs updates silently for apps Orion installed.
* **Nothing Fails Quietly:** If an update can't install (a different signature, low storage, Android asking for a confirmation), you get a notification that opens the app ready to install with one tap, and the next time you open Orion it shows exactly what happened.

---

## 🏆 Fair Play

* **A Tougher Climb:** Each level now takes more XP than the last, and the gap keeps growing. Bronze Wings sit at 1,185 XP, Silver at 14,355 and Gold at 55,125.
* **Ads Must Be Earned:** Only a fully watched ad fuels the code. Skipped ads, failed loads and missing ads no longer pay out, missions are spaced 30 seconds apart, and Fuel the Code pays out up to 20 times a day.
* **Mini Game Limits:** Orion Run gives up to 300 XP a day.
* **Easter Egg Exploit Fixed:** The "Made with 💜 for Geeks" easter egg counted as a watched ad, letting XP be farmed without limit. It still unlocks its badge, but no longer grants XP or Fuel the Code progress.
* **Honest Backups:** Restoring a save with more progress than anyone could have earned is refused, and tier badges are re-earned from your real XP.
* **Leaderboard Integrity Check:** Progress that fails the same check can't be submitted to the Hall of Fame.

---

## 🎛️ Settings, Your Way

* **Custom Quick Toggles:** Tap the pencil next to **Quick toggles** and pick the settings you flip most: auto-update, Wi-Fi only, stable only, auto-cleanup, animations, glass dock and more. Hold and drag a tile to reorder it. **Reset** brings back the defaults.
* **Arrange the Settings Hub:** Long-press any settings card and drag it around. Cards trade places live as you pass over them and glide into their new spots. The layout keeps its shape, so it always looks right. **Reset** restores the original order.
* Both carry over in your identity backup.

---

## ☁️ Automatic Cloud Backup

* **Back Up to Your Own Cloud:** In **Settings → Backup**, connect Google Drive, OneDrive, Proton Drive or any storage app on your phone, then choose **Daily**, **Weekly** or **Monthly**. Orion keeps your identity file there up to date on its own.
* **Still Serverless:** No Orion account and no Orion server. Your backup goes straight from your phone to the cloud you picked, through Android's own file picker.
* **Back Up Now** and **Disconnect** whenever you like. Restoring works from the same file.

---

## 🔓 Root Toolkit

Root is optional. Turn on **Root mode** in **Settings → Orion Xtra** and Orion unlocks:

* **Root Installs:** Silent installs and updates with no prompts, no Shizuku and no wireless debugging. No 24-hour wait either.
* **Orion Owns Its Updates:** Apps installed with root list Orion as their installer, so Android's "App details in store" and update ownership point to Orion.
* **Adopt Installed Apps:** Got catalog apps from somewhere else? Orion can take over their updates in one tap. Your app data stays exactly as it was.
* **Update Lock (Android 14+):** Other stores have to ask you before they update apps Orion installed.
* **Clear Every App's Cache:** Free space across all your apps at once.
* **Guardian with Root:** Permission revoke, APK extraction and debloat all run on root too, with no Shizuku needed.

---

## ✨ New

* **Stable Releases Only:** A new switch in **Settings → Network** hides Beta, Alpha and Nightly builds whenever an app has a stable release. Downloads, the version picker, update checks and auto-update all stick to stable. Apps that only ship prereleases keep working as before. Turn it off and your per-app channel choices come back. (#140)
* **Tablets and Landscape:** Orion now rotates freely on tablets and unfolded foldables, with no more locked portrait window. Pages sit in a comfortable, centered column on wide screens. Phones stay in portrait.
* **What's New, Always Current:** Release notes now come straight from Orion's GitHub releases: short headlines grouped by section, with the full notes one tap away.
* **Undo and Redo Your Signature:** The pilot card signature pad now has undo and redo for every stroke, clear and ink change.
* **Preview Submission Screenshots:** Tap any screenshot while submitting an app to view it full screen.
* **App Details in Store:** In Android's **Settings → Apps → App info**, the **App details in store** row now opens the app's page in Orion Store for anything Orion installed. When two catalog entries share a package name, Orion opens the one you installed. Thanks @Zokii0. (#123)

---

## 🐛 Fixes

* **One Popup at a Time:** Welcome screens, notices, tours, tips and messages now wait their turn and appear one by one, only after you close the previous one. They no longer show over the update or maintenance screens.
* **Bundles Remember You:** Opening an app from a bundle and going back now returns you to that bundle instead of dropping you on the home screen.

---

## ⚡ Performance

* **Smoother Pull to Refresh Picker:** The pixel characters are now drawn once and reused, so the pull-to-refresh picker opens without a stutter. Pulling to refresh on the store gets the same speedup.
