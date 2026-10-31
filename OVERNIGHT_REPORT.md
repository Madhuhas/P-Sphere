# Overnight work report — 25 Oct 2026

Everything below is in the working tree and **not committed**. Nothing was pushed or published.
A copy of the code as it was before this pass is in `P Sphere/_backup_before_overnight_2026-10-25/`
(including `uncommitted-mobile.patch`, the mobile changes you hadn't committed yet).

## Where things are

| What | Path |
|---|---|
| Debug APK (install this to test) | `mobile/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK (minified, **signed with the local debug key** — fine for testing, don't publish) | `mobile/app/build/outputs/apk/release/app-release.apk` |
| P2P unit tests (20, all passing) | `mobile/app/src/test/java/com/psphere/p2p/` |
| Rebuilt website | `website/` (open `website/index.html`) |
| Clickable UI mock-up of v1.1 (serve locally, e.g. http://localhost:5180; deep links like `#settings,server,dark,tablet`) | `mobile/preview/index.html` |

App version is now **1.1 (versionCode 2)**. The phone-to-phone protocol changed, so **update every phone** — 1.1 can't sync with 1.0 (it shows a clear "older version" message).

---

## Phase 2 — Phone-to-phone (P2P) sync: bugs found and fixed

These were real defects, several of which meant nearby sync could not have worked reliably:

1. **Phones never advertised themselves.** In `PeerDiscovery`, `NsdServiceInfo().apply { setServiceName(serviceName) }` used the new object's own empty `serviceName`, so registration threw "service name is missing". Other phones couldn't find this one. (Found by launching the app on the emulator; present in the original code too.)
2. **Two phones of the same model couldn't see each other.** The device ID was just the model name, so the second Pixel 8 looked like "itself". Each install now gets a 6-character random ID: `PSphere-Pixel-8-3fa91c`.
3. **Sessions stalled if a file disappeared or was duplicated.** The receiver counted files instead of waiting for an end marker. Protocol v2 ends each phase with `END`.
4. **"Destination folder name" setting was ignored.** Files always went to `<source>/p2p_received`. They now go to `Pictures/<sender's folder name>` and are added to Android's media index so they show up in gallery apps.
5. **Received photos were re-sent forever** (they weren't counted as "owned"). Now tracked in an index.
6. **No integrity check.** Every file is now SHA-256-verified while it downloads; written to a hidden `.part` file and renamed only when complete.
7. **Path traversal.** A peer could send a file name like `../../x`. Names and folder names are sanitised; unrequested files are refused.
8. **Non-English file names were corrupted** (bytes decoded one at a time). Now proper UTF-8.
9. **Every failure was reported as success / "Already up to date".** Errors now reach the UI per phone ("Couldn't reach Galaxy S23 …").
10. **No connect timeout**; now 5 s with one retry.
11. **Server accept loop spun forever after stop.**
12. **No reaction to Wi-Fi changes.** Discovery now restarts on reconnect and clears stale phones on disconnect; a multicast lock is held for devices that drop mDNS.
13. **Two simultaneous sessions downloaded the same file twice.** Downloads are now claimed per hash.
14. **Scanning failed on hidden/locked files** (e.g. `.thumbnails`). Hidden and unreadable files are skipped; P2P shares only photo/video types.
15. **"Sync now" ignored your chosen folder** for server uploads (always scanned `Pictures/`).
16. **The whole DCIM folder was shared by default** while the UI said "No folder selected". Now nothing is shared until a folder is chosen.

Tests (`gradlew testDebugUnitTest`) run two real sessions over localhost sockets plus hand-written misbehaving peers:
exchange, idempotent second sync, three-phone relay, folder naming, duplicates, Unicode names, name clashes, hidden files,
file deleted mid-sync, concurrent sessions, old-protocol peer, dropped connection mid-file, corrupted payload, malicious names/unrequested files, and naming/sanitising rules.

**Not done:** P2P has no pairing and no encryption. Any PSphere phone on the same Wi-Fi can request the shared folder. The website and docs say this plainly. This is the most important next step for P2P.

## Phase 1 — Android UI

- **Theme:** the old theme set 4 colours, so cards, chips and the nav highlight fell back to Material's default purple (and dark-mode buttons had purple text). Full light/dark schemes plus success/warning colours.
- **Navigation:** Albums and Search (server-only) are hidden in P2P mode instead of showing an error. Side navigation rail on tablets / landscape (≥600 dp). Content is width-capped on large screens; grids adapt their column count.
- **Sync screen:** one status card with the action button inside it; folder card opens Settings; "Search again" for nearby phones; partial-failure warnings; pull-to-refresh now re-searches instead of silently starting a sync; haptics only fire on a real transition (they used to fire on every app open).
- **Settings:** clearer sections; account section adapts to mode; **Sign out now switches to nearby sync** instead of dumping you on setup and cancelling background backup; "Free up space" reports how many files were actually deleted (Android 11+ won't let the app delete photos it didn't create — it used to claim success).
- **Albums/Search:** emoji tags replaced with icons; tapping a photo now opens the viewer. Album grid minimum is 140dp so 360dp phones still get two columns (changed after the release APK was built; the debug APK has it).
- **Setup screen:** clearer two-path layout; server URL no longer pre-filled with the emulator-only `10.0.2.2` in release builds.
- **Launcher icon** added (the app had none) and a dark window background (no white flash in dark mode).

Server-mode bugs fixed along the way:
- **No server thumbnails ever loaded** — every API route (including `/thumb`) requires a JWT and the image loader never sent it. A shared Coil loader now adds the token for requests to your server only.
- **Album covers and search results never loaded** — relative URLs were handed straight to the image loader.
- **Full-size photos used the build-default address** (`10.0.2.2`) instead of your server.
- **Plain `http://` servers were blocked by Android 9+.** Cleartext is now allowed (LAN servers are http).
- **Background sync in a fresh process used the wrong server** — server URL is now restored at app start.
- **Release builds would have broken every API call** — R8 was on but `proguard-rules.pro` didn't exist. Added keep rules.

## Phase 3 — Build and launch test

`gradlew testDebugUnitTest assembleDebug assembleRelease` succeeds with no Kotlin warnings. Release signing reads `mobile/keystore.properties` if present (now git-ignored), otherwise uses the debug key.

Launch test on the emulator (headless, shut down afterwards), **release APK**: installs, starts, no crashes in the crash log. Setup → "Start with nearby sync" → Sync screen → Settings all render (screenshots in `mobile/screenshots/`). Android's discovery service shows the phone advertised correctly as `PSphere-<model>-<id>._psphere._tcp` with TXT `{v=2, id, name}`. Before the fix, the same call threw on startup.
**Not tested on the emulator:** an actual transfer between two devices (one emulator, no Wi-Fi), server mode (no server running), dark mode, and tablet layout. The transfer logic itself is covered by the unit tests.

## Phase 4 — Website

Rebuilt `website/` as plain static HTML/CSS (still GitHub-Pages ready): home, download, a full docs page (quick start, using the app, P2P internals and security model, self-hosting with env reference/systemd/nginx, REST API reference from the actual routes, troubleshooting, FAQ, changelog), and restyled legal pages. Light and dark themes, works at phone width, no emoji, no broken links (checked).

Content corrections: the old site claimed connections "require explicit authorization on both devices" (false — there's no pairing), said the server/web/AI were "coming soon" (they exist), and listed Prisma (not used). **Legal text was kept word for word**; only the third-party library table was corrected.

## Check when you're back

1. **Install `app-debug.apk` on two real phones**, choose a folder on each, and sync. This is the first time discovery actually advertises, so this is the key test. Look for photos in `Pictures/PSphere Backup` and in Google Photos.
2. Try the server path: log in to Core from the app and confirm thumbnails, album covers, search results and full-size photos load.
3. Open `website/index.html` and `website/docs.html`; check wording you care about (brand is "P-Sphere" on the site but "PSphere" in the app — pick one).
4. Commit. Suggested first: stop tracking build output (`git rm -r --cached mobile/app/build`, `.gitignore` already updated), then commit the mobile and website changes.
5. Before publishing a release: create a real keystore and `mobile/keystore.properties`.

## Suggested next steps

- P2P pairing (QR code or confirm-on-both-phones — KotiniSync v1 had QR pairing) and TLS for transfers.
- Replace `resolveService` (deprecated on Android 14) with `registerServiceInfoCallback` on API 34+.
- "Free up space" for camera photos needs `MediaStore.createDeleteRequest` to delete files the app didn't create.
- The viewer's gallery list and the gallery grid come from two separate loads in server mode; unify them so indices always match.
- Roadmap 10.7 performance items (DB indexes, Redis caching) are still open on the server.
