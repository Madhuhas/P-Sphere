# Security

PSphere copies your files between your own devices, so security reports are welcome and taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Report it privately instead:

1. Open the [Security tab](https://github.com/Madhuhas/P-Sphere/security) of this repository.
2. Choose **Report a vulnerability**.
3. Describe what you found, how to reproduce it, and what an attacker could do with it.

Only the maintainer sees the report. You will get a first answer within a week. Once a fix is released, the advisory is published, with credit to you if you want it.

## What is in scope

Anything that breaks a promise PSphere makes, for example:

- a device that was never linked, or was unlinked, connecting, sending or receiving anything,
- a file, file name or folder name travelling unencrypted, or readable by someone on the same Wi-Fi,
- linking succeeding when the codes on the two screens do not match,
- PSphere reading or writing outside the folders you chose, or a sender choosing where a file lands on the receiving device,
- a backup copy being deleted because the file was deleted on its original device, or an older version being lost instead of kept in `.psphere/old` for 30 days,
- PSphere contacting anything on the internet,
- a crafted message from another device crashing the app, filling the disk past the space PSphere keeps free, or reaching files it should not.

## What is not a vulnerability

These are documented limits, not bugs (see the [spec](docs/p2p-v3.md)):

- Anyone who can unlock a linked device can see the backups on it and send files to your other devices.
- Backups are stored as ordinary files on the receiving device, not encrypted at rest. Use the device's own encryption for that.
- Other devices on your network can see that a PSphere device is there, and its device ID, because devices find each other with mDNS.

## Supported versions

PSphere is in early development. Fixes go into the latest version only.
