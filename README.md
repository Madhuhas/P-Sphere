<p align="center">
  <img src="desktop-app/icons/psphere.png" width="88" alt="PSphere logo" />
</p>

<h1 align="center">PSphere</h1>

<p align="center">
Google Photos, but your own devices are the cloud.
</p>

<div align="center">
<table>
<tbody>
<td align="center">
<a href="https://psphere.pages.dev" target="_blank"><strong>Website</strong></a>
</td>
<td align="center">
<a href="https://psphere.pages.dev/docs" target="_blank"><strong>Docs</strong></a>
</td>
<td align="center">
<a href="https://psphere.pages.dev/download" target="_blank"><strong>Download</strong></a>
</td>
<td align="center">
<a href="https://github.com/Madhuhas/P-Sphere/discussions/categories/feature-requests" target="_blank"><strong>Feature Requests</strong></a>
</td>
<td align="center">
<a href="https://psphere.pages.dev/security" target="_blank"><strong>Security</strong></a>
</td>
</tbody>
</table>
</div>

<p align="center">
<a href="https://github.com/Madhuhas/P-Sphere/actions/workflows/test.yml"><img src="https://github.com/Madhuhas/P-Sphere/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Madhuhas/P-Sphere" alt="License: AGPL-3.0"></a>
<a href="https://kadhai.pages.dev"><picture><source media="(prefers-color-scheme: dark)" srcset="https://kadhai.pages.dev/badges/built-with-kadhai-light.svg"><img src="https://kadhai.pages.dev/badges/built-with-kadhai-dark.svg" alt="Built with Kadhai" width="119" height="20"></picture></a>
</p>

<br>

PSphere backs up folders between your Android phone, tablet and Windows laptop over your home Wi-Fi. There's no server, no account and no subscription, and your photos never leave home.

<div align="center">
<table>
<tr>
<td align="center" width="50%">

### Android app

Back up your camera and any other folder,
see every device's photos in one timeline,
and free up space once photos are safe.

<a href="https://psphere.pages.dev/download">Download the APK</a>
<br><br>

</td>
<td align="center" width="50%">

### Windows app

Keeps the backups from your phone and tablet
on any drive you choose, and backs up its own
folders too. Runs in the tray.

<a href="https://psphere.pages.dev/download">Download the installer</a>
<br><br>

</td>
</tr>
</table>
</div>

> **Early development.** Version 2.3 is a pre-release. Both apps are tested on real devices, but expect rough edges, and please [tell us what breaks](https://github.com/Madhuhas/P-Sphere/issues/new?template=bug_report.yml).

---

## Index

| Part | What it is | Location |
|---|---|---|
| **Shared core** | The sync engine every app uses: protocol, encryption, linking, rules, relay, resume, photo catalog. Pure Kotlin, no third-party dependencies. | [`shared/`](shared) |
| **Android app** | Kotlin and Jetpack Compose. | [`mobile/`](mobile) |
| **Windows app** | Kotlin and Compose for Desktop, packaged as an MSI. | [`desktop-app/`](desktop-app) |
| **Design** | The full spec: how devices link, what goes where, and why. | [`docs/p2p-v3.md`](docs/p2p-v3.md) |
| **Website** | [psphere.pages.dev](https://psphere.pages.dev), kept in its own repository. | n/a |

## Backs up between your own devices

Pick a folder, like Camera, and tick the devices that should keep a copy. Each device can back up several folders, each to different devices, and every device sends and receives. Whenever two linked devices are on the same Wi-Fi, they send each other only what's missing.

## One photo library

The Photos tab shows the photos and videos from all your linked devices in one timeline. Photos that live on another device show a preview; open one to see it full size, or download it back.

## Free up space

On your phone, PSphere lists the photos already backed up safely on another device. Free them up and they're removed from the phone only, after PSphere checks again that the copies are really there. They stay in Photos as previews.

## Backup, not mirror

Deleting a file never deletes its copies. When a file changes, the backup is replaced and the old version is kept for 30 days. A device that was off catches up later from any device that has the files, and big videos resume where they stopped.

## Private by design

Only devices you've linked can connect, by scanning a QR code or comparing a 6-digit code on both screens. Every connection is encrypted with TLS, each device checks the other's key, and every file is checked against its SHA-256 fingerprint when it arrives. We [tested this on real devices](https://psphere.pages.dev/security).

---

## Building

You need JDK 17 or newer (the one in Android Studio works) and, for the Android app, the Android SDK.

```sh
cd shared      && ./gradlew test            # the core's tests
cd mobile      && ./gradlew assembleDebug   # mobile/app/build/outputs/apk/debug/app-debug.apk
cd desktop-app && ./gradlew run             # add -PdataDir=<folder> to keep your real data untouched
```

To package the Windows app you need a full JDK with `jpackage` (Android Studio's doesn't have it):

```sh
cd desktop-app
./gradlew packageMsi    # build/compose/binaries/main/msi
```

On Windows, Gradle sometimes fails with "Unable to delete directory …/build/…". Run `attrib -r "build\*" /s /d` in that folder and try again.

`shared` also has a command-line test device that can link and back up with a phone or emulator. See [CONTRIBUTING.md](CONTRIBUTING.md) for that and for how to test a change.

## Feedback

- **Found a bug?** [Open a bug report](https://github.com/Madhuhas/P-Sphere/issues/new?template=bug_report.yml). Say which devices were involved and how they were connected.
- **Have an idea?** [Post it in Feature Requests](https://github.com/Madhuhas/P-Sphere/discussions/new?category=feature-requests), where others can upvote it.
- **Want to help?** [Open for Contribution](https://github.com/Madhuhas/P-Sphere/discussions/categories/open-for-contribution) lists work anyone can pick up, and [CONTRIBUTING.md](CONTRIBUTING.md) explains how to start.
- **Found a security problem?** Please don't open an issue. [Report it privately](https://github.com/Madhuhas/P-Sphere/security/advisories/new); see [SECURITY.md](SECURITY.md).

## License

Copyright (C) 2026 Kotini Sai Madhuhas

PSphere is free software: you can redistribute it and/or modify it under the terms of the [GNU Affero General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the license for details.

In plain words: use it, change it and share it freely. If you share a changed version of PSphere, or run one as a service other people use over a network, you must offer them the source code of your version under the same license. Contributors sign a short [agreement](CLA.md) once, keep their copyright, and are promised their work will always stay open source.

## Credits

- **[Kotlin](https://kotlinlang.org)**, **[Jetpack Compose](https://developer.android.com/jetpack/compose)**, **[Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)**, **WorkManager**, **Navigation** and **ExifInterface** (Apache 2.0).
- **[Coil](https://coil-kt.github.io/coil/)** loads photos on Android (Apache 2.0).
- **[ZXing](https://github.com/zxing/zxing)** draws the linking QR codes (Apache 2.0).
- **[JmDNS](https://github.com/jmdns/jmdns)** finds devices on the Wi-Fi in the Windows app (Apache 2.0).
- **[metadata-extractor](https://github.com/drewnoakes/metadata-extractor)** reads when a photo was taken in the Windows app (Apache 2.0).
- QR codes are scanned with Google's **ML Kit code scanner**, part of Google Play services, which is not open source and has its own [terms](https://developers.google.com/ml-kit/terms).
