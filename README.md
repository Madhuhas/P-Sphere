# PSphere

[![Tests](https://github.com/Madhuhas/P-Sphere/actions/workflows/test.yml/badge.svg)](https://github.com/Madhuhas/P-Sphere/actions/workflows/test.yml)
[![License](https://img.shields.io/github/license/Madhuhas/P-Sphere)](LICENSE)
<a href="https://kadhai.pages.dev"><picture><source media="(prefers-color-scheme: dark)" srcset="https://kadhai.pages.dev/badges/built-with-kadhai-light.svg"><img src="https://kadhai.pages.dev/badges/built-with-kadhai-dark.svg" alt="Built with Kadhai" width="119" height="20"></picture></a>

**Website:** [psphere.pages.dev](https://psphere.pages.dev) · **Community:** [discussions](https://github.com/Madhuhas/P-Sphere/discussions)

> **Early development.** The Android app works and has been tested on an emulator; the Windows app is not built yet. Expect rough edges, and please report what breaks.

Google Photos, but your own devices are the cloud. PSphere backs up folders between your phone, tablet and Windows laptop over your home Wi-Fi. There's no server, no account and no internet involved.

- **Several folders per device**, each going to the devices you choose.
- **Every device sends and receives.** Each receiving device picks where backups are saved.
- **Backup, not mirror.** Deleting a file on one device never deletes its copies.
- **Only your linked devices** can connect, and everything is encrypted.

The full design is in [docs/p2p-v3.md](docs/p2p-v3.md).

## Status

| Step | What | State |
|---|---|---|
| 1 | Shared core: protocol, encryption, rules, relay, resume, catalog | Done, 38 tests |
| 2 | Android app: linking, Sending, Receiving, Devices | Built and tested on an emulator; real-device testing next |
| 3 | Windows app | Not started |
| 4 | Photos library across devices, Free up space, polish | Not started |

## Project layout

```
shared/          Pure-Kotlin core used by every app (package com.psphere.core)
mobile/          Android app (Kotlin, Jetpack Compose, package com.psphere)
  preview/v3.html  Clickable mock of the Android screens
docs/p2p-v3.md   The spec
.github/         Issue and discussion forms, tests (Actions), Dependabot
website/         Static site (to be updated after the Windows app, step 3)
```

The Android app still contains some hidden screens from the earlier server-based version (Albums, Search, sign-in). They'll be removed or rebuilt in step 4. Older versions of the project (the server, web app, Electron app and the first P2P prototype) are in `../old.zip`.

## Building

Needs the JDK that ships with Android Studio (`JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr`) and the Android SDK.

```sh
# Shared core: run the tests
cd shared
./gradlew test

# Android app: debug APK at mobile/app/build/outputs/apk/debug/app-debug.apk
cd mobile
./gradlew assembleDebug
```

On Windows, Gradle sometimes fails with "Unable to delete directory …/build/…". Running `attrib -r "build\*" /s /d` (or `app\build\*` in `mobile/`) fixes it.

## Testing against the Android app

`shared` includes a command-line test device that acts as a laptop. It can link and back up with the app on an emulator or phone:

```sh
adb forward tcp:47820 tcp:47820
cd shared
./gradlew harness -Pargs="<dir>,init,Laptop"            # create the device and sample files
./gradlew harness -Pargs="<dir>,link,127.0.0.1,47820"   # open "Link a device" on the phone first
./gradlew harness -Pargs="<dir>,sync,127.0.0.1,47820"   # back up both ways
./gradlew harness -Pargs="<dir>,status"
```

Devices find each other with mDNS on real Wi-Fi, but not through an emulator, so the test device always starts the sync.

## Feedback

- **Found a bug?** [Open a bug report](https://github.com/Madhuhas/P-Sphere/issues/new?template=bug_report.yml). Say which devices were involved and how they were connected.
- **Have an idea?** [Post it in Feature Requests](https://github.com/Madhuhas/P-Sphere/discussions/new?category=feature-requests), where others can upvote it.
- **Want to help?** [Open for Contribution](https://github.com/Madhuhas/P-Sphere/discussions/categories/open-for-contribution) lists work anyone may pick up, and [CONTRIBUTING.md](CONTRIBUTING.md) explains how to start.
- **Found a security problem?** Please do not open an issue: [report it privately](https://github.com/Madhuhas/P-Sphere/security/advisories/new). See [SECURITY.md](SECURITY.md).

## License

Copyright (C) 2026 Kotini Sai Madhuhas

PSphere is free software: you can redistribute it and/or modify it under the terms of the [GNU Affero General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the license for details.

In plain words: use it, change it and share it freely. If you share a changed version of PSphere, or run one as a service other people use over a network, you must offer them the source code of your version under the same license. Contributions are welcome: contributors sign a short [agreement](CLA.md) once, keep their copyright, and are promised their work will always stay open source (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Credits

- **[Kotlin](https://kotlinlang.org)**, **[Android Jetpack](https://developer.android.com/jetpack)** (Compose, WorkManager, DataStore, Navigation), **[Coil](https://coil-kt.github.io/coil/)**, **[Retrofit](https://square.github.io/retrofit/)** and **[OkHttp](https://square.github.io/okhttp/)** are Apache 2.0 licensed.
- **[ZXing](https://github.com/zxing/zxing)** (Apache 2.0) draws the linking QR codes.
- QR codes are scanned with Google's **ML Kit code scanner**, part of Google Play services, which is not open source and has its own [terms](https://developers.google.com/ml-kit/terms).
