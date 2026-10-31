# PSphere local backup (v3): spec

> **In one line:** Google Photos, but your own devices are the cloud. Nothing leaves your home network.

Status: **approved** (2026-10-25). Steps 1, 2 and 3 are built; see section 8.
Scope: local backup between your own devices on the same Wi-Fi. No server and no internet.

---

## 1. What it's for

You have a phone, a tablet and a Windows laptop. You want:

- to back up **several folders** from any device to **one or more** of your other devices, with a choice per folder;
- every device to be able to **send and receive**;
- backups to keep working when one device is off (for example, phone → tablet while the laptop is off);
- to see all their photos on any device, like Google Photos, and download any of them when needed. Albums, faces and AI search come later.

Example setup:

| Folder | On | Backed up to |
|---|---|---|
| Camera | Phone | Tablet, Laptop |
| WhatsApp Images | Phone | Laptop |
| Downloads | Tablet | Laptop |
| Documents | Tablet | Phone |

## 2. Rules the app follows

1. **Backup, not mirror.** Deleting a file on the source never deletes a copy anywhere else.
2. **Only your devices.** A device talks only to devices you have linked. Other PSphere devices on the same Wi-Fi are ignored.
3. **Each folder has its own destinations.** Nothing is sent to a device that the folder's rule doesn't name.
4. **The receiver decides where files go.** Each receiving device picks its own backup location: any drive or folder on Windows, and any folder on Android.
5. **Any copy can be the source.** If the laptop missed something, it can get it from the phone *or* from the tablet, if the tablet already holds a copy. Files are identified by their contents (SHA-256), so a file is never stored twice.
6. **Nothing is lost silently.** Every file is checked after it arrives. A failure is shown with the device name and the reason.

## 3. Words used in the app

- **My devices**: the devices you've linked. Each has a name, e.g. "Phone", "Tablet" or "Laptop".
- **Backup folder**: a folder on this device plus the devices it goes to. Shown in the app under **Sending**.
- **Kept backups**: files this device holds for other devices. Shown under **Receiving**.
- **Backup location**: the folder on a receiving device where kept backups live.

Layout inside the backup location:

```
<backup location>/<sending device>/<folder name>/<original subfolders and files>

D:\Backups\Phone\Camera\2026\IMG_0001.jpg
D:\Backups\Phone\WhatsApp Images\IMG-20261020-WA0003.jpg
Phone:  Pictures/PSphere/Tablet/Documents/report.pdf
```

Relayed files go in the folder of the device they **came from originally**, not the device that passed them on. A phone photo that reached the laptop through the tablet still lands in `Phone\Camera`.

## 4. Linking devices

- It's done once for each pair: three devices means up to three links (phone–tablet, phone–laptop, tablet–laptop).
- One device shows a **QR code**. The other scans it with its camera.
  - The laptop has no camera, so it always shows the code and the phone or tablet scans it.
- **Fallback when scanning doesn't work:** both screens show the same 6-digit code, and you tap **Codes match** on both.
- After linking, both devices remember each other permanently.
- **Unlink** is available on each device. It stops all transfers with that device. Files already copied are kept.
- A linked device that is renamed shows its new name. Its folder in the backup location keeps the old name, so nothing moves.

## 5. When backups run

- **Automatically**, whenever two of your devices are on the same Wi-Fi and one has something the other should have.
  - On Android this also runs in the background every few hours.
  - On Windows it runs while the app is open or minimised to the system tray. It can start with Windows.
- **Manually**: **Back up now**, for all devices or for one device.
- Options (Android): **Wi-Fi only** is always on, since this is local; **Only while charging** is optional.
- If a device is off, its share waits. The screen says so, e.g. "Waiting for Laptop · 312 files".

## 6. Screens

The same sections on Android and Windows: Devices, Photos, Sending, Receiving, Settings.

### 6.1 Devices (home)
- Overall status at the top: "Everything is backed up", "Backing up to Tablet · 45 of 120", or "Waiting for Laptop (offline)".
- A **Back up now** button.
- A card for each linked device:
  - online or offline, and when it was last seen
  - **Sending**: how many files are waiting to go to it
  - **Receiving**: how many files this device keeps for it, and their total size
- **Link a device** button.

### 6.2 Device detail (tap a device)
- **Sending to Laptop**: each folder going to that device, with progress and last backup time.
- **Keeping from Laptop**: each of its folders stored here, with file count, size and an **Open folder** link.
- **Back up now** with just this device, and **Unlink**.

### 6.3 Sending
- The list of backup folders, each showing its destinations and status.
- **Add folder**:
  1. pick the folder;
  2. tick the devices it goes to;
  3. choose what to include: **Photos and videos** or **All files**;
  4. subfolders are always included.
- Edit or remove a folder. Removing it stops future backups; copies already made stay where they are.
- **Free up space** button (see 6.3a).

### 6.3a Free up space
Deletes files **from this device only**, once they are safely backed up elsewhere. It's always a manual action and never runs automatically.

- **Safe** means a receiving device has confirmed it holds the file and checked it against the hash. The receiver sends that confirmation after each file is stored.
- **How many copies are needed**, set per folder:
  - **at least one** destination (the default);
  - or **every destination** of that folder.
- **Everything that is backed up is freed** by default.
  - Optional setting: keep files from the last N days on this device.
- Before deleting, a small preview of each photo and video is saved on this device, so it stays visible in **Photos** (6.3b).
- **Preview before deleting.** For example: "Delete 1,240 files (8.3 GB) from Phone. All are safe on Tablet and Laptop."
  - It lists any device that couldn't be re-checked because it's offline.
- **Re-check just before deleting:**
  - Online destinations are asked again whether they still hold each file. Any file one of them no longer has is skipped.
  - For offline devices, their last confirmation is used.
- Files are deleted through the folder access granted by the system folder picker (7.6), so Android doesn't block it.
- Result line afterwards, e.g. "Freed 8.3 GB · 1,240 files". If any were skipped, it says why.

### 6.3b Photos (one library across all your devices, like Google Photos)
One timeline on every device, showing the photos and videos from the backup folders of **all** your linked devices. The phone sees the tablet's photos, the tablet sees the phone's, and the laptop sees both.

**What's shown**
- Each folder has a setting, **Show on my other devices**, which is on by default. Turn it off for folders you'd rather keep out of the shared library, such as WhatsApp. That folder is still backed up as usual.
- Filters: **From** (Phone, Tablet, Laptop) and **Folder**.
- Small badges show where an item lives: on this device, or only on other devices.

**Previews**
- Every device keeps a small preview (about 50 KB) of every item in the library, so everything is always visible, even away from home with no other device on.
- Roughly 50 MB per 1,000 photos. For example, 20,000 photos ≈ 1 GB of previews on each device.
- Receiving's storage line shows how much space previews use.

**Full size**
- Opening an item that **is on this device**: it opens normally.
- Opening an item that **isn't on this device**:
  - if any device holding it (its original device or one of its backups) is reachable on the same Wi-Fi, the full-size photo loads from there, and videos play from there;
  - otherwise you see the preview with a note such as "Full size on Laptop · not reachable".

**Download to this device**
- Copies the full file onto this device. It works for one item or a selection. A device holding the file must be reachable.
- **Your own freed items** go back into their original folder.
- **Items from another device** go into this device's backup location as `<device>/<folder>/`, the same place a backup would put them.

**Deleted on the original device**
- If you delete a photo on the phone with any app (not with Free up space), the copies on the tablet and laptop stay, because this is a backup.
- In Photos, those items are hidden by default. The filter **Show deleted on original device** brings them back, so you can download one you deleted by mistake.
- **Share:** shares the full file if it's available, otherwise it offers to download it first.
- Freed items don't appear in other gallery apps on the device, because the files are no longer there. Only PSphere shows them.

### 6.4 Receiving
- **Backup location**, with a button to change it.
  - Changing it asks whether to move the existing backups or leave them.
- Free space on that drive, plus a warning line when it's nearly full.
- Every folder this device keeps, grouped by sending device, with size and **Open folder**.
- **Pause receiving**, for when you need the space or bandwidth.

### 6.5 Settings
- This device's name.
- When to back up.
- Linked devices.
- About.

### 6.6 What happens to today's screens
- The current Photos tab is rebuilt as the Photos view in 6.3b.
- Albums, Search and the server sign-in are **parked**. They're hidden for now, not deleted.
- Albums, Search and sign-in come back with the laptop's "Google Photos" work.

## 7. How it works underneath

### 7.1 Finding each other
- Discovery on the local network (mDNS) as today, service `_psphere._tcp`.
- The advertisement includes the device ID and protocol version **3**.
- Unlinked IDs are ignored.
- Each device also remembers the last IP address of every linked device and tries it directly, because some routers block discovery.

### 7.2 Identity and encryption
- Each device creates a key pair once. Its device ID is derived from the public key.
- Linking swaps public keys:
  - the QR code carries the key fingerprint;
  - with the 6-digit fallback, the code is derived from both keys, which is why the two screens must match.
- Every connection uses **TLS**, and each side checks that the other's key is the one it linked with.
  - Result: traffic is encrypted, and a device that isn't linked can't connect.
  - This also fixes the biggest weakness of today's version.

### 7.3 What gets sent
- When two linked devices connect, each one offers the other:
  - files from its own backup folders whose rule names the other device;
  - **relay:** files it keeps for a third device, when that file's rule also names the other device. The rule travels with the files, so a holder knows where else they belong.
- For each file the receiver says "have it" or "send it", based on the content hash. Nothing already present is sent again.
- Files are written to a temporary `.part` file, checked against the hash, then renamed into place.
- **Resume:** an interrupted large file (for example a video) continues from where it stopped instead of starting over.
- Hashes are cached by path, size and modified time, so unchanged folders are not re-read every time.
- **Library catalog:** each device keeps a list of the photos and videos in its shared folders. Each entry has the hash, original device, folder, path, date taken, type, size, and which devices hold a copy.
  - Linked devices exchange changes to this list, plus the previews, every time they connect. This is how every device can show the whole library.
  - When a backup is confirmed, the "which devices hold a copy" part is updated, so any device knows where to fetch the full size from.
- **On request:** a device can ask a linked device for one file by its hash, to show a full-size freed photo or to download it back (6.3b). It can also ask "do you still have these?" (the free-up-space re-check, 6.3a).

### 7.4 Edits and name clashes
- A **file changed on the source** (same name, new content): the new version replaces the backup copy. The old version is moved to `<backup location>/.psphere/old/<date>/` and deleted after 30 days.
- **Different files with the same name** can't clash, because each source has its own folder.

### 7.5 Disk space
- A receiver keeps at least 1 GB free (this can be changed).
- When it would go below that, it stops accepting and the sender shows "Laptop is full".

### 7.6 Android folder access
- Today the app can only read photos and videos. To back up any folder (Documents, Downloads) and to receive into any folder, folders will be picked with **Android's own folder picker**. No extra permission is needed.
- **Limit:** Android doesn't allow choosing the root of `Download` or the `Android/data` folders. The picker explains this when it happens.
- Received photos are added to Android's media index so they appear in gallery apps.

### 7.7 Code layout
- **`shared/`** (new): pure Kotlin. Holds the protocol, hashing, the rules, the index of kept files and the transfer logic, with tests that run on a PC. Used by both apps.
- **`mobile/`**: the Android app. Its user interface plus Android parts such as folder picking, the background worker and discovery.
- **`desktop-app/`** (new): the Windows app in Kotlin with Compose for Desktop.
  - JmDNS for discovery, a system-tray icon, and a Windows installer.
  - The old Electron `desktop/` app was moved to `old.zip` (outside the repo) on 2026-10-31.

### 7.8 Compatibility
- Version 3 does not talk to version 2 or 1.1. Every device needs the new version.
- A device that meets an old one says "Update PSphere on Tablet".

## 8. Build order

Each step ends with something you can test on your own devices.

| Step | What | You can test |
|---|---|---|
| 1 | Shared module: protocol v3, encryption, rules, relay, resume, library catalog and previews, tests | Automated tests (no devices needed) |
| 2 | Android: linking, Sending, Receiving, Devices screens | Phone ↔ tablet backups both ways, multiple folders, choice of destination |
| 3 | Windows app | Phone/tablet → laptop to a drive you pick, tray icon, start with Windows |
| 4 | Photos (combined library on Android and Windows) and Free up space; relay, resume and full-disk handling on real devices; polish | See the tablet's photos on the phone. Free 8 GB on the phone, still browse those photos, open one full-size from the laptop, download it back. Laptop off → phone to tablet → laptop catches up from the tablet |
| Later | Laptop "Google Photos" extras: albums, faces, search | n/a |

## 9. Decisions I made (say if any are wrong)

1. **Linking is per pair.** Three devices means three quick scans. Linking a group in one go could come later.
2. **The laptop always shows the QR code.** It has no camera.
3. **Changed files** replace the backup, and the old version is kept for 30 days.
4. **Each folder has its own include setting**: "Photos and videos" or "All files".
5. **Relay is on by default.** I can add a switch to turn it off.
6. **Albums, search and server sign-in are hidden** in the Android app until the laptop photo-library work. The Photos tab stays, rebuilt as 6.3b.
7. **The website and docs get updated after step 3**, once the real behaviour exists.
8. **Free up space** needs one confirmed copy by default (every destination is an option) and frees everything backed up; keeping recent days is optional.
9. **Photos is one library across all devices.** Each folder can opt out with "Show on my other devices". Items deleted on their original device are hidden by default but stay in the backups.

## 10. Not in this version

- Backups over the internet or mobile data when you're away from home.
- iPhone and Mac.
- Albums, faces and AI search. The timeline itself is in this version (6.3b).
- Two-way sync where deletes and edits travel both ways.
