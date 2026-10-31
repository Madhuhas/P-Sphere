# PSphere

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

## License

Private / all rights reserved.
