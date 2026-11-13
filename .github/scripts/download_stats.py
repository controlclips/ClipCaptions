"""Counts how many times the Clip Captions APK has been downloaded, and writes DOWNLOADS.md.

Orion Store has no statistics of its own (it doesn't track anyone), but it installs the APK
straight from this repository's Releases page, and GitHub counts every download of every file.
This adds up the APK downloads of the proper releases (not test builds, not the translation
model files), keeps each version's count in stats/downloads.json so numbers aren't lost if a
release is ever deleted, and records the total once a week to show the trend.

Run by .github/workflows/stats.yml. Needs GH_TOKEN and GITHUB_REPOSITORY.
"""

import datetime
import json
import os
import subprocess

REPO = os.environ["GITHUB_REPOSITORY"]
# Builds before 6.3 were all signed with the test key and only downloaded by the developer.
FIRST_COUNTED = (6, 3)
DATA = "stats/downloads.json"
PAGE = "DOWNLOADS.md"


def version_of(tag):
    """'v6.3-build19' -> (6, 3), or None if the tag isn't an app version."""
    try:
        return tuple(int(p) for p in tag.lstrip("v").split("-")[0].split("."))
    except ValueError:
        return None


def releases():
    out = subprocess.run(
        ["gh", "api", "--paginate", f"repos/{REPO}/releases?per_page=100", "--jq",
         ".[] | {tag: .tag_name, pre: .prerelease, draft: .draft, published: .published_at,"
         " apks: [.assets[] | select(.name | endswith(\".apk\")) | .download_count]}"],
        check=True, text=True, capture_output=True).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def main():
    try:
        with open(DATA) as f:
            data = json.load(f)
    except FileNotFoundError:
        data = {"versions": {}, "history": []}

    for r in releases():
        version = version_of(r["tag"])
        if r["pre"] or r["draft"] or version is None or version < FIRST_COUNTED:
            continue
        name = ".".join(str(p) for p in version)
        count = sum(r["apks"])
        old = data["versions"].get(name, {})
        # Several builds of one version add up; a count never goes down (deleted releases keep theirs).
        builds = dict(old.get("builds", {}))
        builds[r["tag"]] = max(count, builds.get(r["tag"], 0))
        data["versions"][name] = {
            "published": old.get("published") or (r["published"] or "")[:10],
            "builds": builds,
        }

    total = sum(sum(v["builds"].values()) for v in data["versions"].values())
    today = datetime.date.today().isoformat()
    history = [h for h in data["history"] if h["date"] != today]
    history.append({"date": today, "total": total})
    data["history"] = history[-104:]

    os.makedirs(os.path.dirname(DATA), exist_ok=True)
    with open(DATA, "w") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")

    previous = history[-2]["total"] if len(history) > 1 else None
    lines = [
        "# Downloads",
        "",
        f"**{total:,} app downloads** as of {today}"
        + (f" ({total - previous:+,} since {history[-2]['date']})." if previous is not None else "."),
        "",
        f"Counted from the APK files on the [Releases page](https://github.com/{REPO}/releases), which is where Orion Store",
        "and people installing by hand download the app from. Updated every Monday.",
        "",
        "- This counts downloads, not people: each update downloaded counts again.",
        "- Only proper releases from 6.3 on. Test builds and the translation model files aren't counted.",
        "",
        "## By version",
        "",
        "| Version | Released | Downloads |",
        "|---|---|---:|",
    ]
    def key(item):
        return version_of(item[0]) or ()
    for name, v in sorted(data["versions"].items(), key=key, reverse=True):
        lines.append(f"| {name} | {v['published']} | {sum(v['builds'].values()):,} |")
    if not data["versions"]:
        lines.append("| (none yet) | | 0 |")
    lines += ["", "## Week by week", "", "| Date | Total downloads |", "|---|---:|"]
    for h in reversed(history[-26:]):
        lines.append(f"| {h['date']} | {h['total']:,} |")
    with open(PAGE, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{total} downloads")


if __name__ == "__main__":
    main()
