# Odin's Gate launcher

The launcher friends will install instead of RSProx: it downloads a pinned RuneLite from RuneLite's own servers,
patches it in memory to our server and key, and keeps itself up to date from this repo's GitHub Releases. This folder
is its own Gradle build, like `test-runner/`, so a launcher change never rebuilds or restarts the game server
(`deploy/auto-deploy.sh` skips `launcher/`).

Build and test: `./gradlew -p launcher build` (CI's `launcher` job runs the same). Java 21, package `net.odinsgate`.

## What's here so far

The core that later PRs build on; no window, downloads or releases yet:

- `Manifest`: the signed manifest, the one piece of remote data that decides what runs. CI will publish it as a
  Release asset next to its `.sig` (base64 of the 64-byte Ed25519 signature over the manifest's exact bytes). It
  carries the launcher version, each release file's SHA-256 and size, the RuneLite pin table (revision → a
  static.runelite.net commit and its bootstrap's SHA-256), and the server's host, ports and RSA modulus. Validation
  is strict because every value is used somewhere dangerous; unknown members are ignored so old launchers survive
  additions, and a new `format` means "please download the new installer".
- `Signatures` and `TrustedKeys`: Ed25519 verification against the pinned public keys (a primary and Sam's offline
  backup). The production list is empty until the release PR ships the real public halves; the keys themselves are
  made on Sam's own PC, never in a session or a VM, and tests make throwaway pairs of their own.
- `ServerCheck`: the pre-Play check, with 5-second timeouts and at most three connections per try (far under the
  relay's 20 new connections a minute): `/status.json`, the game port, and the served key from `/proxy-targets.yaml`.
  Outcomes: ready, server offline (GitHub answers, our server doesn't), no internet, a revision the manifest doesn't
  pin yet, or a served key that isn't the signed one, which stops the launch. Plain HTTP from the server is hints
  only; it never decides what code runs.
- `Platform` and `ClientCommand`: the per-system pieces. The game runs in a child JVM from our bundled runtime
  (`bin/java` is kept), through our patching loader (its own later PR), with RuneLite's bootstrap JVM arguments, its
  own home folder (`-Duser.home`, never the friend's real RuneLite profile), a local runtime config
  (`-Drunelite.rtconf`, so RuneLite's remote config can't switch our pinned version off), our API base
  (`-Drunelite.http-service.url`, so the world list only ever holds our world), our `--jav_config`,
  `--disable-telemetry` and `--noupdate`. Developer mode (the test VM) adds `-ea:net.runelite.client.RuneLite` and
  `--developer-mode`, which is what lets the sideloaded test-runner plugin load.
- `Names` and `NamesTest`: every friend-visible name in one place, held to the rule that none may contain RuneScape,
  OSRS, Jagex, -scape or RSPS in any case, and the module's file names with them.

The spike that proved the patching route on the real jars was PR #113, closed unmerged as planned: nothing of
RuneLite's or Jagex's enters this repo, in git or in a release.
