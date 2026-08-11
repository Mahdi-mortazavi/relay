#!/usr/bin/env python3
"""Download the APK assets of a release into a directory.

Release assets on a private repo are not reachable by plain URL, so this goes
through the API with the job's token and asks for the raw bytes. Kept as a
file rather than inlined in YAML so it can be run — and read — on its own.

Usage: fetch-release-apks.py <repo> <tag> <out-dir>   (GH_TOKEN in the env)
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


def main():
    repo, tag, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)

    if not tag:
        with get(f"{API}/repos/{repo}/releases/latest") as r:
            tag = json.load(r)["tag_name"]
    print(f"probing release {tag}")

    with get(f"{API}/repos/{repo}/releases/tags/{tag}") as r:
        release = json.load(r)

    apks = [a for a in release["assets"] if a["name"].endswith(".apk")]
    if not apks:
        sys.exit(f"no APK assets on {tag} — nothing to probe")

    for asset in apks:
        dest = os.path.join(out, asset["name"])
        with get(asset["url"], "application/octet-stream") as r, open(dest, "wb") as f:
            f.write(r.read())
        got = os.path.getsize(dest)
        if got != asset["size"]:
            sys.exit(f"{asset['name']}: got {got} bytes, expected {asset['size']}")
        print(f"  {asset['name']}  {got:,} bytes")

    # The tag is what the summary line reports, so hand it back to the job.
    if env := os.environ.get("GITHUB_ENV"):
        with open(env, "a") as f:
            f.write(f"RELAY_TAG={tag}\n")


if __name__ == "__main__":
    main()
