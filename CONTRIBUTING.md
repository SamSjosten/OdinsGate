# Contributing

Work comes in as one branch and one pull request against `main` per step. CI must be green and Codex's review
answered before a pull request merges; Sam merges.

## Checks

`.github/workflows/build.yml` runs on every pull request and push to `main`:

- `launcher` and `launcher-windows`: `./gradlew -p launcher build` on Linux and on Windows, where friends run it.
  The Linux job uploads the installed layout as the `odinsgate-launcher` artifact.
- `installer-windows`, `installer-mac` (Apple chip and Intel) and `installer-linux`: each installer built, its app
  image self-tested and scanned, and the installed, copied or extracted launcher self-tested again, as a friend would
  run it (`launcher/README.md`, "Installers").
- `launcher-version`: a change to the pin table or the modulus bumps `launcher/version.txt`; a bump is newer than the
  last and not released yet; `min-bootstrap-version.txt` only moves with a bump (`.github/scripts/launcher-version.js`).
- `friend-page`: no banned word on the download page, every link https to a known host or a file in `docs/`, every
  installer linked exactly once (`.github/scripts/friend-page.js`).
- `scripts`: the workflow scripts' own tests (`node --test .github/scripts/*.test.js`).

`.github/workflows/release.yml` runs as a dry run on a pull request that touches the release path (the version
files, the pin table, the modulus, `build.gradle`, `Release.java`, the packaging, the installer actions, the
workflow itself): the same build, signed with a throwaway key, publishing nothing.

## Releases

A merge to `main` that changes `launcher/version.txt` publishes a release: the jars, the four installers, the
manifest and its signature, signed with `RELEASE_SIGNING_KEY` from the `release` Environment after Sam's one-click
approval (`launcher/README.md`, "Releases"). Installed launchers fetch `releases/latest/download/manifest.json` at
every start, so a release reaches every friend at their next start. A broken release ships again as a higher
version; a runtime fix also raises `launcher/min-bootstrap-version.txt`, which sends older launchers to the download
page for the new installer.

## The two shared files

`launcher/runelite-pins.json` (the RuneLite build per game revision) and `launcher/rsa-modulus.txt` (the live
server's public modulus) are copies of files the game server reads from its own, private repository. A change to
either is made in both repositories, and here it bumps `launcher/version.txt`, since only the signed manifest carries
them to a launcher. The revision bump playbook is in `launcher/README.md`.

## Scheduled checks

`.github/workflows/pin-health.yml` runs Mondays, and on a pull request that touches the pin table or the check: every
pin still holds on RuneLite's servers (`./gradlew -p launcher pinHealth`; HEAD requests only, no jar is downloaded).
A scheduled run that fails opens one issue, **A RuneLite pin is no longer healthy**, assigned to the repository's
owner, kept up to date while the check keeps failing and closed with a comment when it passes again
(`.github/scripts/alert.js`). To run it now: Actions, **pin-health**, **Run workflow**.

## What never enters this repository

No file of RuneLite's or of the game's publisher: no jar but the Gradle wrapper's, no client, no game cache, no
bootstrap but the committed revision-241 proof the tests use. No key: the release key lives only in the `release`
Environment and Sam's password manager, and the tests make throwaway keys of their own. No name a friend sees may
carry the banned words (`launcher/src/main/java/net/odinsgate/launcher/Names.java`), and the download page is
checked for them.
