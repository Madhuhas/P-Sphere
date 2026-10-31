# Contributing to PSphere

Thank you for thinking about helping. PSphere is a small project with a few firm ideas: your files stay on your own devices, nothing goes through a server or the internet, and a backup is never deleted behind your back. This guide explains how to take part, from a one-line bug report to a new feature, and what a change needs before it can be merged.

**Contents**

1. [Ways to help](#ways-to-help)
2. [How we treat each other](#how-we-treat-each-other)
3. [Reporting bugs](#reporting-bugs)
4. [Reporting a security problem](#reporting-a-security-problem)
5. [Before you start: talk first](#before-you-start-talk-first)
6. [Finding something to work on](#finding-something-to-work-on)
7. [What a change must keep](#what-a-change-must-keep)
8. [Setting up](#setting-up)
9. [Writing code](#writing-code)
10. [Testing your change](#testing-your-change)
11. [Commits](#commits)
12. [Opening a pull request](#opening-a-pull-request)
13. [Review, merging and releases](#review-merging-and-releases)
14. [The contributor agreement](#the-contributor-agreement)
15. [Do not copy](#do-not-copy)
16. [Getting help](#getting-help)

---

## Ways to help

You do not need to write code to make PSphere better.

- **Use it and say what breaks.** A clear bug report with steps is one of the most useful things you can send.
- **Suggest features** in [Feature Requests](https://github.com/Madhuhas/P-Sphere/discussions/categories/feature-requests), and upvote the ideas you want most. The most wanted ones rise to the top.
- **Answer questions** from other people in Discussions.
- **Improve the docs.** The [spec](docs/p2p-v3.md) and this guide are plain Markdown. A sentence that confused you probably confuses others.
- **Try it on real devices.** The tests run on one computer with fake devices. Real phones, routers and big photo libraries behave differently. Reports from real setups are gold.
- **Write code**: fix a bug, pick up a task from [Open for Contribution](https://github.com/Madhuhas/P-Sphere/discussions/categories/open-for-contribution), or build a feature that was agreed on first.

## How we treat each other

Be kind, be patient, and assume good intent. People here have different skills, languages and amounts of time. Criticise ideas and code, never people. Harassment, insults and personal attacks are not tolerated, in issues, discussions, pull requests or anywhere else connected with the project. The maintainer may edit, hide or remove anything that breaks this, and block people who keep doing it. See the [code of conduct](.github/CODE_OF_CONDUCT.md).

## Reporting bugs

1. **Search first.** Look through [open and closed issues](https://github.com/Madhuhas/P-Sphere/issues?q=is%3Aissue). If someone has reported it, add what you know there (a thumbs-up helps too) instead of opening a new one.
2. **Open a [bug report](https://github.com/Madhuhas/P-Sphere/issues/new?template=bug_report.yml).** The form asks for what happened, the steps to reproduce it, and the devices involved.
3. **Say which devices were involved:** the make and model, the Android or Windows version, the PSphere version, and how they were connected (same Wi-Fi, hotspot, and so on).
4. **Add errors and logs** if you have them, for example from `adb logcat`. Remove anything private first, such as file names you would not want public.

A good report says what you expected, what happened instead, and how to make it happen again. "The tablet stopped receiving after the phone backed up a 4 GB video over a hotspot" is far more useful than "backup is broken".

## Reporting a security problem

**Do not open an issue for a security problem.** Report it privately through [Report a vulnerability](https://github.com/Madhuhas/P-Sphere/security/advisories/new) on the Security tab, so it can be fixed before it is known. [SECURITY.md](SECURITY.md) explains what counts as a vulnerability and what to expect.

## Before you start: talk first

So that nobody spends a weekend on a change that cannot be merged, most changes start with a conversation.

**Go straight to a pull request for:**

- a fix for a bug that is clearly a bug (with steps to show it),
- typos, broken links, clearer wording in the docs or the interface,
- a failing test made to pass, or a missing test added.

**Start a conversation first for:**

- **a new feature or behaviour**: post it in [Feature Requests](https://github.com/Madhuhas/P-Sphere/discussions/categories/feature-requests) (or comment on the existing one) and wait for the maintainer to say yes,
- **anything that changes the protocol**: what devices send each other, linking, encryption, relay, what gets kept or replaced,
- **a new dependency, permission or platform**,
- **a large refactor**, or anything touching more than a handful of files.

Pull requests for features that were not agreed on may be closed, however good the code is. That is not a judgement of your work; it keeps the project small enough to keep its promises.

## Finding something to work on

- [**Open for Contribution**](https://github.com/Madhuhas/P-Sphere/discussions/categories/open-for-contribution) lists tasks the maintainer would welcome. Each says what needs doing, where to start, how big it is and when it counts as done. Tasks marked **Small** are good first contributions.
- **Claim a task before you start.** Reply on it saying you are taking it. If you stop, say so, so someone else can pick it up.
- **Bugs** in [Issues](https://github.com/Madhuhas/P-Sphere/issues) are fair game too; say on the issue that you are working on it.

## What a change must keep

These are product requirements, not preferences. A change that breaks one will not be merged, so check yours against them early. The [spec](docs/p2p-v3.md) explains how each one works.

- **No server, no account, no internet.** Devices talk only to each other, on the local network. Nothing calls out, and nothing calls in from outside.
- **Only linked devices.** Every connection is encrypted, and each side checks that the other is a device it linked with. A device that was never linked, or was unlinked, gets nothing.
- **Backup, not mirror.** Deleting a file on one device never deletes its copies. A changed file replaces its backup only after the old version is kept for 30 days.
- **The receiver decides.** Each device chooses where its backups are saved and how much space stays free. A sender never chooses where a file lands.
- **Only the folders the user chose.** PSphere reads and writes inside the folders picked in the system folder picker, and nowhere else. No broad storage permissions.
- **Behaviour belongs in `shared/`.** The protocol, the rules and the transfer logic live in the shared core, so every app behaves the same. The apps are thin shells around it.
- **Few dependencies.** A new library needs a strong reason and the maintainer's agreement first.
- **Plain, honest wording.** Interface text says exactly what happens. Never describe something as safer, or more backed up, than it is.

## Setting up

### What you need

- **JDK 17 or newer.** The one that ships with [Android Studio](https://developer.android.com/studio) works; point `JAVA_HOME` at it.
- **The Android SDK** (Android Studio installs it) for the Android app.
- **Git** ([git-scm.com](https://git-scm.com)).
- An Android phone or emulator for trying the app. Windows is the main development platform; macOS and Linux work for the shared core.

### Get the code running

```bash
# 1. Fork the repository on GitHub, then clone your fork
git clone https://github.com/<your-name>/P-Sphere.git
cd P-Sphere

# 2. Run the shared core's tests (offline, no devices needed)
cd shared
./gradlew test

# 3. Build the Android app
cd ../mobile
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

### Project layout

| Folder | What it is |
| --- | --- |
| `shared/` | The pure-Kotlin core used by every app (package `com.psphere.core`): protocol, encryption, linking, rules, relay, resume and the catalog. A standalone Gradle build with its own tests. |
| `mobile/` | The Android app (Kotlin, Jetpack Compose, package `com.psphere`). Includes `shared/` as a composite build. |
| `docs/p2p-v3.md` | The spec: what PSphere does and how it works underneath. |
| `website/` | The website's source. The published site lives in [PSphere-Website](https://github.com/Madhuhas/PSphere-Website). |

### Windows: "Unable to delete directory"

Gradle on Windows sometimes fails with "Unable to delete directory …/build/…" because the files are read-only. Running `attrib -r "build\*" /s /d` (or `app\build\*` in `mobile/`) fixes it.

## Writing code

- **Behaviour belongs in `shared/`.** If both apps need it, it goes in the core. The core is plain Kotlin: no Android imports.
- **Android parts stay in `mobile/`**: folder access (`SafFolder`), discovery, background work and the screens.
- **Kotlin only**, targeting Java 17.
- **Never log file contents, keys or the user's file names** at a level that reaches release logs.
- **Every network message is untrusted.** Check sizes, paths and hashes before acting on anything another device sends.
- **Match the code around you**: its naming, its comment style (comments say why, not what) and its plain wording.

## Testing your change

- **Run `./gradlew test` in `shared/`.** It is offline: the tests use fake devices in temporary folders, never the network or your files.
- **Add a test for new behaviour,** and a test that fails without your fix for a bug. Tests live in `shared/src/test/kotlin/com/psphere/core/`.
- **Android changes:** build with `./gradlew assembleDebug` in `mobile/`, install it, and look at what you changed, in a light and a dark theme.
- **Changes to linking or backing up:** try them against the app with the test device in `shared/`, which acts as a laptop:

  ```bash
  adb forward tcp:47820 tcp:47820
  cd shared
  ./gradlew harness -Pargs="<dir>,init,Laptop"            # create the device and sample files
  ./gradlew harness -Pargs="<dir>,link,127.0.0.1,47820"   # open "Link a device" on the phone first
  ./gradlew harness -Pargs="<dir>,sync,127.0.0.1,47820"   # back up both ways
  ./gradlew harness -Pargs="<dir>,status"
  ```

  Devices find each other with mDNS on real Wi-Fi, but not through an emulator, so the test device always starts the sync. If you can, also try two real devices on the same Wi-Fi.

## Commits

- **One change per pull request,** split into commits that each make sense on their own.
- **Write messages that say what changed and why.** A short first line in plain words ("Receiver keeps the old version when a file changes"), then, if needed, a paragraph explaining the reason. Mention the issue or discussion it belongs to (`Fixes #12`).
- **Never commit keys or signing files**: `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`.

## Opening a pull request

Before you open it:

- [ ] It does one thing, and that thing was agreed on (or is a bug fix, typo or test).
- [ ] It keeps every rule in [What a change must keep](#what-a-change-must-keep).
- [ ] `./gradlew test` passes in `shared/`, and new behaviour has a test.
- [ ] `./gradlew assembleDebug` passes in `mobile/`.
- [ ] Interface changes are checked on a device or emulator, in a light and a dark theme.
- [ ] Your branch is up to date with the default branch (rebase rather than merge).
- [ ] No leftover debugging code, commented-out blocks or unrelated changes.
- [ ] The docs say how it works now.

In the description, explain:

- **what changed and why**, with a link to the issue or discussion,
- **how you tested it**, including the devices if you tried real ones,
- **anything that changes for existing users** (a setting, saved data, the protocol),
- **screenshots** for any change to the interface.

When you open it, two checks run: the build and tests, and the contributor agreement (see below). Both must pass before it can be merged.

## Review, merging and releases

- The maintainer reviews every pull request, usually within a week. Expect questions and requests for changes; they are part of the process, not a rejection.
- Reply to review comments and push follow-up commits to the same branch. Do not open a new pull request for the same change.
- Pull requests are squashed or merged by the maintainer once they pass review and the checks.
- PSphere is in early development. The maintainer decides version numbers, writes the release notes and publishes releases; you do not need to change the version or a changelog.
- Dependency updates arrive from Dependabot as pull requests and are reviewed like any other.

## The contributor agreement

Before your first pull request can be merged, please sign the [Contributor License Agreement](CLA.md). It is short and in plain English:

- **you keep the copyright** in your work,
- you give the project a licence to use it,
- and the project **promises your contribution will always stay available under an open-source licence** (today the GNU AGPL-3.0).

To sign, post this comment on your first pull request:

> I have read the CLA Document and I hereby sign the CLA

A bot records it, and it covers everything you contribute afterwards.

## Do not copy

Write your change yourself. Do not paste code, icons, images or text from other projects, products or websites, even with changes, unless its licence clearly allows it and you say in the pull request where it came from. Code written with an AI assistant is welcome if you have read it, understand it, have tested it and can stand behind it as your own.

## Getting help

- **A question about using PSphere or about the code?** Ask in [Discussions](https://github.com/Madhuhas/P-Sphere/discussions).
- **Stuck on a pull request?** Ask in the pull request itself; that keeps the context in one place.
- **News and releases** are posted in [Announcements](https://github.com/Madhuhas/P-Sphere/discussions/categories/announcements).

Thank you for helping PSphere.
