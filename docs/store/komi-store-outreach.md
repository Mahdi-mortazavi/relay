# Komi Store — what actually moves the needle

**Read this before sending anything.** The brief assumed Komi has "a curated
manual layer for Trending" that needs a maintainer contacted on Discord or
Telegram. Their own pipeline says otherwise, so the message at the bottom is
optional and the section above it is not.

Source read: [`komi-store/komi-store-backend-data`](https://github.com/komi-store/komi-store-backend-data),
`scripts/fetch_all_categories.py` — the daily GitHub Actions job that builds
their index.

## Relay needs no submission, and is already discoverable

Their Android discovery queries filter on topics, ORed:

```python
tq = " OR ".join(f"topic:{t}" for t in topics)   # _build_query
"topics": ["android", "android-app", "kotlin-android"]
```

Relay carries `android`, so it matches. The **New Releases** specs are

```python
(7 days, stars:>5), (14, stars:>10), (21, stars:>50), plus (14, stars:>0), (21, stars:>0)
```

Relay is at **189 stars**, pushed today, not archived, and ships `.apk` **and**
`.exe` in tagged releases — which is the whole basis of their index. It matches
every one of those. **Nothing needs to be requested.**

- **New Releases** — already eligible, on both Android and Windows.
- **Trending** — already in the candidate pool via the highest-weight spec
  (`30 days, stars:>100`, weight 1.5). Appearing is a matter of ranking.
- **Most Popular** — `stars:>5000`, hard-coded. Relay is at 189. No message
  changes that.

## The one real lever: four topics, worth +23 and +33

`calculate_platform_score` awards **15** for a "high" keyword in topics, **8**
for "medium", **3** for "low", **20** for a matching primary language, **10** for
a framework topic, and **5**/**3** for high/medium keywords in the description.
Matching is exact list membership, so `winui3` does not match `winui`.

Relay's score today, computed against that function:

| | Android | Windows |
|---|---|---|
| high in topics | `android` +15 | `windows` +15 |
| medium in topics | `kotlin` +8, `jetpack-compose` +8 | `dotnet` +8 |
| primary language | `kotlin` **+20** | — (primary is c#/c++/rust; GitHub calls this repo Kotlin) |
| high in description | `android` +5 | `windows` +5 |
| framework topic | `jetpack-compose` +10 | — (`winui3` ≠ `winui`) |
| **total** | **66** | **28** |

Four topics that are true of this project and are worth points:

| topic | worth | why it is true |
|---|---|---|
| `kotlin-android` | Android **+15** (high) | it is a Kotlin Android app |
| `mobile` | Android **+8** (medium) | it is |
| `winui` | Windows **+15** high **and +10** framework | the Windows client is WinUI 3; the repo already says `winui3` |
| `desktop` | Windows **+8** (medium) | the Windows half is a desktop app |

That takes Android **66 → 89** and Windows **28 → 61**.

```bash
gh repo edit Mahdi-mortazavi/relay --add-topic kotlin-android --add-topic mobile --add-topic winui --add-topic desktop
```

**Not run.** You said to leave topics alone, and this is public repository
metadata. The command is here to run when you want it.

**Deliberately not suggested:** `cross-platform` is worth +15 on *both*
platforms, and I am not comfortable claiming it. Relay is two native apps that
talk to each other, not one codebase targeting many platforms. It would score
well and it would be shading the truth.

**Also worth knowing:** two Trending specs use `stars:>200`. Relay is **eleven
stars short** of matching them. Nothing to do about that except the obvious.

---

## Optional message, if you want to say hello anyway

Their README gives two channels — Discord `https://discord.komistore.app` and
the Telegram channel `https://t.me/komistoreapp`. The Telegram one is a
*channel*, so it may not accept replies; Discord is the better bet.

There is nothing to ask them for. This is an introduction, not a request, and it
is fine not to send it.

```
Hi — Relay (https://github.com/Mahdi-mortazavi/relay) ships .apk and .exe on
tagged GitHub releases, so your pipeline should already be picking it up; this
is just a hello rather than a request.

It shares an Android phone's internet with a Windows PC over an encrypted
WireGuard tunnel — no root, no account, no server in the middle, and both
halves are GPL-3.0 from one repository. 189 stars, released regularly, and
every release carries written notes and SHA256SUMS.

Thanks for building an index that reads releases instead of scraping.
```
