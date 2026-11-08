"""Copies Mozilla's translation models onto this repository's "models" release.

Mozilla asks apps not to download straight from Firefox's servers, so Clip Captions downloads from
this mirror (and falls back to Mozilla only if the mirror is unreachable). For every language pair
it picks the same files the app would (the newest version-3 model meant for Android release
builds, with its vocabulary and word list), copies any that aren't mirrored yet, and writes
models.json, which tells the app what's there.

Run by .github/workflows/models.yml. Needs GH_TOKEN and GITHUB_REPOSITORY.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

RECORDS = (
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/"
    "translations-models-v2/records"
)
CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
TAG = "models"
SUPPORTED_MAJOR = 3
TYPES = ["model", "vocab", "srcvocab", "trgvocab", "lex"]
REPO = os.environ["GITHUB_REPOSITORY"]


def get(url, attempts=4):
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=300) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - retry any network problem
            if attempt == attempts - 1:
                raise
            print(f"  retrying {url}: {error}", flush=True)
            time.sleep(5 * (attempt + 1))


def gh(*args, check=True, capture=False):
    result = subprocess.run(["gh", *args, "--repo", REPO], check=check, text=True,
                            capture_output=capture)
    return result.stdout if capture else None


def version_key(text):
    return [int("".join(c for c in part if c.isdigit()) or 0) for part in text.split(".")]


def for_android_release(expression):
    e = (expression or "").replace('"', "'").replace(" ", "")
    if not e:
        return True
    if "env.appinfo.OS!='Android'" in e:
        return False
    if "env.channel" in e and "'release'" not in e:
        return False
    return True


def choose(records):
    """{"es-en": {type: record}} with the files the app would download for each pair."""
    by_pair = {}
    for r in records:
        if r.get("fileType") not in TYPES or "attachment" not in r:
            continue
        if not for_android_release(r.get("filter_expression")):
            continue
        version = r.get("version", "")
        if version_key(version)[0] != SUPPORTED_MAJOR:
            continue
        key = f"{r['sourceLanguage']}-{r['targetLanguage']}"
        by_pair.setdefault(key, []).append(r)

    def newest(items):
        # Releases before pre-releases ("3.0a1"), then the highest version.
        return sorted(items, key=lambda r: (not any(c.isalpha() for c in r["version"]),
                                            version_key(r["version"])), reverse=True)

    chosen = {}
    for key, items in by_pair.items():
        models = newest([r for r in items if r["fileType"] == "model"])
        if not models:
            continue
        model = models[0]
        picked = {"model": model}
        for t in TYPES[1:]:
            candidates = [r for r in items if r["fileType"] == t]
            same = [r for r in candidates if r["version"] == model["version"]
                    and r.get("architecture") == model.get("architecture")]
            best = newest(same) or newest(candidates)
            if best:
                picked[t] = best[0]
        if "vocab" in picked:
            picked.pop("srcvocab", None)
            picked.pop("trgvocab", None)
        if "vocab" not in picked and not ("srcvocab" in picked and "trgvocab" in picked):
            print(f"skipping {key}: no vocabulary")
            continue
        chosen[key] = picked
    return chosen


def asset_name(key, record):
    arch = record.get("architecture") or "model"
    return f"{key}.{record['fileType']}.{record['version']}.{arch}.zst"


def main():
    records = json.loads(get(RECORDS))["data"]
    chosen = choose(records)
    print(f"{len(records)} records, {len(chosen)} language pairs", flush=True)

    if subprocess.run(["gh", "release", "view", TAG, "--repo", REPO],
                      capture_output=True).returncode != 0:
        gh("release", "create", TAG, "--title", "Translation models (mirror)", "--latest=false",
           "--notes",
           "Mozilla's Firefox Translations models (MPL-2.0), copied unchanged from Mozilla's "
           "servers so Clip Captions doesn't depend on Firefox's hosting. The app downloads "
           "these by itself; there's nothing to install here. Kept up to date by "
           ".github/workflows/models.yml.")
    release_id = json.loads(subprocess.run(
        ["gh", "api", f"repos/{REPO}/releases/tags/{TAG}"], check=True, text=True,
        capture_output=True).stdout)["id"]
    listing = subprocess.run(
        ["gh", "api", "--paginate", f"repos/{REPO}/releases/{release_id}/assets?per_page=100",
         "--jq", ".[].name"], check=True, text=True, capture_output=True).stdout
    present = {line.strip() for line in listing.splitlines() if line.strip()}
    print(f"{len(present)} files already mirrored", flush=True)

    manifest = {
        "source": "Mozilla Firefox Translations models (translations-models-v2), MPL-2.0",
        "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pairs": {},
    }
    copied = 0
    with tempfile.TemporaryDirectory() as work:
        for key in sorted(chosen):
            entries = []
            uploads = []
            for t, r in chosen[key].items():
                name = asset_name(key, r)
                attachment = r["attachment"]
                entries.append({
                    "type": t,
                    "version": r["version"],
                    "architecture": r.get("architecture", ""),
                    "asset": name,
                    "size": attachment.get("size", 0),
                    "sha256": r.get("decompressedHash", ""),
                })
                if name in present:
                    continue
                data = get(CDN + attachment["location"])
                expected = attachment.get("hash")
                if expected and hashlib.sha256(data).hexdigest() != expected:
                    sys.exit(f"{name}: download doesn't match Mozilla's fingerprint")
                path = os.path.join(work, name)
                with open(path, "wb") as f:
                    f.write(data)
                uploads.append(path)
            if uploads:
                gh("release", "upload", TAG, *uploads, "--clobber")
                for path in uploads:
                    os.remove(path)
                copied += len(uploads)
                print(f"{key}: copied {len(uploads)} file(s)", flush=True)
            manifest["pairs"][key] = entries

        manifest_path = os.path.join(work, "models.json")
        with open(manifest_path, "w") as f:
            json.dump(manifest, f, indent=1, sort_keys=True)
        gh("release", "upload", TAG, manifest_path, "--clobber")

    # Files no pair uses any more (replaced by newer versions).
    wanted = {e["asset"] for entries in manifest["pairs"].values() for e in entries}
    wanted.add("models.json")
    for name in sorted(present - wanted):
        gh("release", "delete-asset", TAG, name, "--yes", check=False)
        print(f"removed old {name}")
    print(f"done: {copied} file(s) copied, {len(manifest['pairs'])} pairs listed")


if __name__ == "__main__":
    main()
