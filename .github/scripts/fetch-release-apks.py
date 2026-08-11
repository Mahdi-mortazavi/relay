#!/usr/bin/env python3
"""Download the APK assets of a release into a directory.

Release assets on a private repo are not reachable by plain URL, so this goes
through the API with the job's token and asks for the raw bytes. Kept as a
file rather than inlined in YAML so it can be run — and read — on its own.

Usage: fetch-release-apks.py <repo> <tag> <out-dir> [prev-out-dir]

With a fourth argument it also downloads the release published immediately
before <tag>, so the probe can test what most people actually do: install the
new build on top of the one already on their phone.

GH_TOKEN in the env.
"""
import json
import os
import sys
import urllib.request

API = "https://api.github.com"


def get(url, accept="application/vnd.github+json"):
    req = urllib.request.Request(
        url, headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                      "Accept": accept, "User-Agent": "relay-install-matrix"})
    return urllib.request.urlopen(req)


def download(release, out):
    """Save every APK asset of a release, checking the byte count."""
    os.makedirs(out, exist_ok=True)
    apks = [a for a in release["assets"] if a["name"].endswith(".apk")]
    if not apks:
        sys.exit(f"no APK assets on {release['tag_name']} — nothing to probe")
    for asset in apks:
        dest = os.path.join(out, asset["name"])
        with get(asset["url"], "application/octet-stream") as r, open(dest, "wb") as f:
            f.write(r.read())
        got = os.path.getsize(dest)
        if got != asset["size"]:
            sys.exit(f"{asset['name']}: got {got} bytes, expected {asset['size']}")
        print(f"  {asset['name']}  {got:,} bytes")


def main():
    repo, tag, out = sys.argv[1], sys.argv[2], sys.argv[3]
    prev_out = sys.argv[4] if len(sys.argv) > 4 else None

    if not tag:
        with get(f"{API}/repos/{repo}/releases/latest") as r:
            tag = json.load(r)["tag_name"]
    print(f"probing release {tag}")

    with get(f"{API}/repos/{repo}/releases/tags/{tag}") as r:
        release = json.load(r)
    download(release, out)

    if prev_out:
        with get(f"{API}/repos/{repo}/releases?per_page=100") as r:
            releases = [x for x in json.load(r) if not x["draft"]]
        # Ordered newest-first by the API; the one after ours is the build a
        # person upgrading from the previous version would already be running.
        names = [x["tag_name"] for x in releases]
        if tag in names and names.index(tag) + 1 < len(names):
            previous = releases[names.index(tag) + 1]
            print(f"upgrade-from release {previous['tag_name']}")
            download(previous, prev_out)
            if env := os.environ.get("GITHUB_ENV"):
                with open(env, "a") as f:
                    f.write(f"RELAY_PREV_TAG={previous['tag_name']}\n")
        else:
            print("no earlier release to upgrade from — skipping that probe")

    # The tag is what the summary line reports, so hand it back to the job.
    if env := os.environ.get("GITHUB_ENV"):
        with open(env, "a") as f:
            f.write(f"RELAY_TAG={tag}\n")


if __name__ == "__main__":
    main()
