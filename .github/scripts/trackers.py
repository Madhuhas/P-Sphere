"""
Checks an APK for trackers, the way Exodus Privacy does: every class name in the app is matched
against Exodus Privacy's public tracker signatures. Also lists the app's permissions. Writes a
Markdown section (with a hidden JSON copy the website reads) to stdout.

Usage: python trackers.py <unshrunk.apk> <release.apk> <dexdump> <aapt2> [trackers.json]
Class names are read from an unshrunk (debug) build of the same code, because a release build
renames most classes; permissions are read from the release APK itself. Without trackers.json
the list is downloaded from Exodus Privacy.
"""
import json
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

EXODUS = "https://reports.exodus-privacy.eu.org/api/trackers"


def classes(apk, dexdump):
    names = set()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for entry in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", entry):
                path = z.extract(entry, tmp)
                out = subprocess.run([dexdump, path], capture_output=True, text=True, errors="replace").stdout
                for d in re.findall(r"Class descriptor\s*:\s*'L([^;]+);'", out):
                    names.add(d.replace("/", "."))
    return names


def permissions(apk, aapt2):
    out = subprocess.run([aapt2, "dump", "permissions", apk], capture_output=True, text=True).stdout
    return sorted(set(re.findall(r"uses-permission: name='([^']+)'", out)))


def main(apk, release_apk, dexdump, aapt2, trackers_file=None):
    if trackers_file:
        data = json.load(open(trackers_file, encoding="utf-8"))
    else:
        req = urllib.request.Request(EXODUS, headers={"User-Agent": "PSphere-tracker-check"})
        data = json.load(urllib.request.urlopen(req, timeout=60))
    trackers = list(data["trackers"].values()) if isinstance(data.get("trackers"), dict) else data["trackers"]
    names = classes(apk, dexdump)
    if not names:
        sys.exit("No classes found in the APK; is dexdump working?")
    found = []
    for t in trackers:
        sig = (t.get("code_signature") or "").strip().strip("|")
        if not sig:
            continue
        rx = re.compile(sig)
        if any(rx.search(n) for n in names):
            found.append(t["name"])
    perms = permissions(release_apk, aapt2)
    print(f"{len(names)} classes, {len(trackers)} signatures, found: {found}", file=sys.stderr)

    lines = ["<!-- trackers -->", "## Trackers and permissions", ""]
    if found:
        lines.append(f"The Android app matched {len(found)} tracker signature(s) from [Exodus Privacy](https://exodus-privacy.eu.org): " + ", ".join(found) + ".")
    else:
        lines.append(f"No trackers: every class in the Android app and its libraries ({len(names):,}) was checked against all {len(trackers)} tracker signatures from [Exodus Privacy](https://exodus-privacy.eu.org).")
    lines += ["", "Permissions it asks for: " + ", ".join(f"`{p.split('.')[-1]}`" for p in perms) + ".", ""]
    lines.append("<!-- trackers-json " + json.dumps({"apk": release_apk.replace("\\", "/").split("/")[-1], "classes": len(names), "signatures": len(trackers), "found": found, "permissions": perms}) + " -->")
    lines.append("<!-- /trackers -->")
    print("\n".join(lines))


if __name__ == "__main__":
    main(*sys.argv[1:])
