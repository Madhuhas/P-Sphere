"""
Scans every file of a release on VirusTotal and writes the results as a Markdown section
(with a hidden JSON copy the website reads) to stdout.

Usage: python virustotal.py <folder with the release files>
Needs VT_API_KEY in the environment. The free API allows 4 requests a minute, so this waits
between calls; a release takes a few minutes.
"""
import hashlib
import json
import os
import sys
import time

import requests

API = "https://www.virustotal.com/api/v3"
KEY = os.environ["VT_API_KEY"]
HEAD = {"x-apikey": KEY}
GAP = 16  # seconds between calls: 4 a minute
_last = 0.0


def call(method, url, **kw):
    global _last
    wait = GAP - (time.time() - _last)
    if wait > 0:
        time.sleep(wait)
    for attempt in range(5):
        _last = time.time()
        r = requests.request(method, url, headers=HEAD, timeout=600, **kw)
        if r.status_code == 429:  # over the limit: back off and retry
            time.sleep(60)
            continue
        return r
    return r


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def stats(sha):
    """The finished results for a file, or None if VirusTotal hasn't analysed it yet."""
    r = call("GET", f"{API}/files/{sha}")
    if r.status_code != 200:
        return None
    a = r.json()["data"]["attributes"]
    s = a.get("last_analysis_stats") or {}
    if not a.get("last_analysis_date") or not s:
        return None
    return s


def upload(path):
    size = os.path.getsize(path)
    if size > 32 * 1024 * 1024:  # big files go to a one-time upload address
        url = call("GET", f"{API}/files/upload_url").json()["data"]
    else:
        url = f"{API}/files"
    with open(path, "rb") as f:
        r = call("POST", url, files={"file": (os.path.basename(path), f)})
    r.raise_for_status()
    return r.json()["data"]["id"]


def wait_for(analysis_id, limit_s=1800):
    end = time.time() + limit_s
    while time.time() < end:
        r = call("GET", f"{API}/analyses/{analysis_id}")
        if r.status_code == 200 and r.json()["data"]["attributes"].get("status") == "completed":
            return True
        time.sleep(30)
    return False


def main(folder):
    results = []
    for name in sorted(os.listdir(folder)):
        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            continue
        sha = sha256(path)
        s = stats(sha)
        if s is None:
            analysis = upload(path)
            wait_for(analysis)
            s = stats(sha)
        if s is None:
            results.append({"file": name, "sha256": sha, "flagged": None, "engines": None})
            continue
        flagged = s.get("malicious", 0) + s.get("suspicious", 0)
        engines = sum(s.get(k, 0) for k in ("malicious", "suspicious", "undetected", "harmless"))
        results.append({"file": name, "sha256": sha, "flagged": flagged, "engines": engines})
        print(f"{name}: {flagged} of {engines} flagged", file=sys.stderr)

    lines = [
        "<!-- virustotal -->",
        "## Virus scan",
        "",
        "Every file was scanned by the antivirus engines on [VirusTotal](https://www.virustotal.com) when this release came out. Open a report to see each engine's result.",
        "",
        "| File | Result | Report |",
        "|---|---|---|",
    ]
    for r in results:
        if r["flagged"] is None:
            result = "Still being scanned"
        elif r["flagged"] == 0:
            result = f"No engine flagged it (0 of {r['engines']})"
        else:
            result = f"{r['flagged']} of {r['engines']} engines flagged it"
        lines.append(f"| `{r['file']}` | {result} | [Report](https://www.virustotal.com/gui/file/{r['sha256']}) |")
    lines += [
        "",
        "A clean scan means no known malware was found; it isn't a guarantee. New, unsigned installers sometimes get a false alarm from one or two engines.",
        "",
        "<!-- virustotal-json " + json.dumps({"scanned": time.strftime("%Y-%m-%d"), "files": results}) + " -->",
        "<!-- /virustotal -->",
    ]
    print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1])
