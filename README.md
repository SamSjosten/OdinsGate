# Odin's Gate

Odin's Gate is the launcher friends install to play on Sam's private game server. It downloads a pinned RuneLite from
RuneLite's own servers, patches it in memory to point at the server, runs it behind a connect guard, and keeps itself
up to date from this repository's signed releases. Friends never see this repository: they get the download page,
https://samsjosten.github.io/OdinsGate/, and the installer for their system.

- `launcher/`: the launcher, its own Gradle build (`./gradlew -p launcher build`; Java 21, package `net.odinsgate`),
  its installers (`package.gradle`, `package/`) and the two files it shares with the server, the RuneLite pin table
  `runelite-pins.json` and the server's public modulus `rsa-modulus.txt`. `launcher/README.md` is the long
  description: what each class does, the installers, releases, and the playbook for a game revision bump.
- `docs/`: the download page and the first-start guides, served by GitHub Pages from `main`.
- `.github/`: CI (`workflows/build.yml`: the build and tests on Linux and Windows, the three installers built and
  self-tested, the version check, the friend page checks), releases (`workflows/release.yml`: a merge that changes
  `launcher/version.txt` publishes one, signed with the key in the `release` Environment after Sam's approval), the
  weekly pin health check (`workflows/pin-health.yml`), the installer actions and the scripts behind the checks.

The game server, its deploy and its test tooling live in a private repository of their own. The launcher moved here
from it on 2026-10-30 with its history; its releases up to 1.0.1 were published there, and from 1.0.2 they are
published here.

`CONTRIBUTING.md` says how changes move; `AGENTS.md` is the review guide.
