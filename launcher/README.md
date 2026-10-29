# Odin's Gate launcher

The launcher friends will install instead of RSProx: it downloads a pinned RuneLite from RuneLite's own servers,
patches it in memory to our server and key, and keeps itself up to date from this repo's GitHub Releases. This folder
is its own Gradle build, like `test-runner/`, so a launcher change never rebuilds or restarts the game server
(`deploy/auto-deploy.sh` skips `launcher/`).

Build and test: `./gradlew -p launcher build` (CI's `launcher` job runs the same). Java 21, package `net.odinsgate`.

## What's here so far

The core that later PRs build on; no window or releases yet:

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
  Outcomes: ready, server offline (GitHub answers, our server doesn't), server full (up, but not taking logins), no
  internet, a revision the manifest doesn't pin yet, or a served key that isn't the signed one, which stops the
  launch. Plain HTTP from the server is hints
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
- `Bootstrap`, `RuneLiteSignature` and `RuneLiteCache`: the pinned RuneLite download. The manifest's pin names a
  static.runelite.net commit and its bootstrap's SHA-256; the cache fetches that bootstrap, requires both the pinned
  hash and RuneLite's own SHA256withRSA signature (checked against the committed `runelite.crt`, BSD-2 from
  runelite/launcher - the key alone, no validity window, like RuneLite's own launcher, so the pin outlives the
  certificate's April 2027 notAfter), and only then parses it. Jars download from RuneLite's own servers into our
  data folder, one subfolder per pinned commit - never the friend's real RuneLite cache - and every jar is hashed
  against the verified bootstrap before every launch, so a healthy cache starts the game with no network at all and
  a tampered file is refetched or, fresh off the network, stops the launch. `prune` keeps only the pins the manifest
  still names. The tests run the production verify path against committed copies of RuneLite's real revision-241
  release files (`bootstrap-241.json` and its signature), so the chain is proven end to end without the network.
- `Preflight`, `ClassPatch` and `PatchingClassLoader`: the in-memory patching that points the pinned RuneLite at our
  server, RSProx's recipe redone with ASM on classes as they load, so no jar is ever rewritten on disk and a class
  RuneLite never loads is never touched. `Preflight` reads the jars once before launch and finds the four patch
  points by their bytecode, not by name: the server's RSA key (in the one injected-client class that also holds the
  exponent, and ours may not have more digits than the client's), `"127.0.0.1"` where `String.endsWith` takes it
  (the jav_config host check, made `""`), `ClientLoader.updateVanilla` when the jar has it (made to return at once,
  so a forged config can never download a gamepack), and `".jagex.com"` in `downloadConfig` (made `""`). It also
  checks, without patching, that `RuneLiteModule` reads `-Drunelite.http-service.url` and `RuntimeConfigLoader`
  reads `-Drunelite.rtconf`. A missing point stops the launch with every gap named; each patch carries the exact
  count the scan found and refuses a class that no longer matches it, so a RuneLite that changed under our pin never
  runs half-patched. The report names jars, classes, methods and counts, never a key. The tests build their own fake
  client with ASM (`FakeClient`), so no RuneLite or Jagex jar is ever in git, and see each patch through its effect
  after loading through the real loader.
- `LaunchOptions`, `Launch`, `Loader`, `RuntimeConfig` and `StatusFile`: the launch modes. Friends start the launcher
  with no arguments; the test VM and CI pass `--auto` (no click on Play), `--dev` (developer mode, so the sideloaded
  test-runner plugin loads), `--home <dir>` (the data folder, so the VM keeps everything under `C:\RSPS`),
  `--status-file <file>` (a small JSON the VM's scripts wait on instead of the server's `/version`: launcher
  version, state, revision, game PID) and `--server host:port[:httpPort]` (the staging server; the game port is
  patched into the client, since a direct connection carries the port itself, and the key stays the manifest's).
  `Launch` turns a mode into the game's command line and writes RuneLite's runtime config first, in every mode, so
  RuneLite's remote one can never mark our pin outdated (an empty object, which the spike proved in both modes).
  `Loader` is the child JVM's entry point: given the jars, key, port and main class it runs the preflight scan,
  refuses with every gap named (exit 2), or loads RuneLite through the patching loader and calls its main method on
  a thread of its own, the context class loader set so Swing and plugins find RuneLite's classes. It also takes
  `--allow host:port` for each endpoint the game may reach (our server's game and HTTP ports, which `Launch`
  passes) and installs `ConnectGuard` before the first RuneLite class loads.
- `ConnectGuard`: the game reaches our server and nothing else. It is the child JVM's default `ProxySelector`,
  which every `Socket`, `HttpURLConnection` and `java.net.http` connection in the JVM asks before it connects,
  so a host is never even resolved unless it is allowed. The allowed endpoints and loopback (RuneLite's own
  local sockets) get a direct connection; everything else is sent to a proxy that is a loopback socket of ours
  which accepts and closes at once, so the attempt fails on the spot instead of reaching the network. An
  allowed name is also allowed by its resolved addresses, but only while it still resolves to them: the names
  are looked up again, at most every ten seconds, when an address asks, so a name that moves takes its old
  address with it (a name that stops resolving keeps its last addresses, so a DNS hiccup doesn't cut a running
  game off); the JVM's own name cache is set to the same ten seconds, so the game and the guard see the move
  together. Each endpoint
  is logged once as `[guard] allowed host:port` or `[guard] refused scheme://host:port`, never a path. The tests
  connect through an installed guard to a loopback socket, a refused socket, a refused URL and a refused
  `java.net.http` request and check the count. Its limit: NIO `SocketChannel` and a native library's own sockets
  never ask the selector. The client connects with `Socket` and RuneLite's HTTP goes through OkHttp, which both
  do; the jars are not yet scanned for `SocketChannel` use (a `Preflight` check for it is a follow-up).
- `Main` and `LauncherRun`: the launcher's entry point (the jar's `Main-Class`) and one run's steps, in order:
  check the server (hints only; each outcome has its own exit code, 10-14), fetch the pinned RuneLite into
  `<data>/runelite/<commit>/` (exit 20 when it can't), plan the launch, start the game's child JVM with its output
  in `<data>/logs/game.log` (exit 21 when it can't), wait, and exit with the game's own code. A `Session` holds
  the steps; `run` takes them straight through with nobody at the screen, which is what `--auto` (the test VM, CI)
  and a machine without a display get, and the window takes them one at a time. Every state goes to the log and,
  with `--status-file`, the status file. `--manifest <file>` names a local manifest, trusted as it is, with no
  update looked for: the VM's own, or the accepted one the shell hands a downloaded version (below).
- `Screen`, `WindowRun`, `LauncherPanel` and `LauncherWindow`: the window a friend sees. `Screen` is the whole of
  it as a state machine with no Swing in it: what to show (status, detail, busy, Play, Retry, the notice, the
  first-run line) and what to do next (check, fetch, launch, retry after thirty seconds), driven by events (each
  check outcome, fetched, launched, exited, the buttons, a tick, the notice). The messages are the plan's: an
  offline server is "offline or restarting" and tried again every thirty seconds with a countdown, after five
  minutes the line says for how long; a full server and no internet wait the same way; a revision the launcher
  doesn't pin yet stops with "its update is on its way"; a changed key stops with "Please tell Sam" and nothing
  starts; a download or start that fails shows its reason, and Retry starts over from the check; after the game
  closes, Play checks again and starts it without a second click. `WindowRun` runs the screen's commands against
  a `Session` on a worker thread pool and hands the answers back on the window's thread, with the retry timer and
  the once-a-second countdown cancelled the moment a check starts. `LauncherPanel` draws a view and nothing more;
  its labels have Swing's HTML rendering switched off and the notice is a text area, so a notice holding `<html>`
  shows the tag. `LauncherWindow` is the frame (titled Odin's Gate) around them. The notice is `launcher/notice.txt`
  on main, read from raw.githubusercontent.com over HTTPS with a five-second timeout, 4 KB at most and cut to 500
  characters on screen, so a line reaches friends without a deploy and while the server is down; a notice that
  can't be fetched is no notice, never a stop. The tests drive the state machine through every outcome, the driver
  over a real `Session` with a hand-cranked worker, and the panel headless; the frame itself opens on a display
  only, which the installer PRs' `--self-test` will do on each system.
- Staying open, and support: while the game runs the window stays and looks at the server's `/status.json` once a
  minute (one connection, through `ServerCheck.status`); a revision that moved shows "The server was updated. Click
  Play to restart the game.", and Play then closes the game and takes the launch from the check again, so a
  revision the launcher pins already starts straight away and one it doesn't stops with "its update is on its
  way". The launcher's own lines go to `<data>/logs/launcher.log` as well as the console, and that log and the
  game's `game.log` are moved aside at 1 MB (`.1` to `.3`, `RotatingLog`) when next opened, so neither grows
  without end. "Copy problem report" puts on the clipboard what Sam needs in one paste (`ProblemReport`): the
  system and chip, the launcher's version, the server's revision and its pin, the launcher's last forty steps and
  checks, and the last sixty lines of `game.log` and of RuneLite's own `client.log`. Never a password: a log line
  mentioning a password, token, secret or authorization is left out whole, the data folder (which carries the
  friend's user name) is written as `<data>`, and the command line isn't in it. The tests flip a fake server's
  revision mid-session through the real `Session` and the hand-cranked driver, both for a pinned and an unpinned
  revision, and feed the report logs with passwords in them. On the VM, `OdinsGate.cmd` now sends the launcher's
  console to `logs\console.log`, since `logs\launcher.log` is the launcher's own.
- Pin health: `PinHealth` (`./gradlew -p launcher pinHealth`, run by `.github/workflows/pin-health.yml` weekly and on
  a pull request touching the pins or the check) takes the server's `src/main/resources/runelite-pins.json`, the same
  table the manifest's pins come from, and asks RuneLite's servers whether each pin still holds: the bootstrap
  carries the pinned SHA-256 and RuneLite's signature (through `RuneLiteCache` itself, into a scratch folder deleted
  after), every jar it names for any system answers a HEAD request with the bootstrap's size, and RuneLite's runtime
  config (`static.runelite.net/config.json`, whose `outdatedClientVersions` would put an update message over a
  client that read it) doesn't name the pinned version. HEAD only: no jar is downloaded or cached in Actions. One
  `ok` or `FAIL` line per check; exit 0, 1 when a check failed, 2 for a bad call. The tests run it against a fake
  static host, repository and config on loopback, signed by a throwaway key, and check that no jar is ever fetched;
  one reads the server's real table and finds the proven 241 pin in it.
- Self-update (`Updater`, `VersionLoader`, `Versions`): what the installed launcher, the thin shell under the
  installer's runtime, does before anything else when run without `--manifest`. It fetches the latest release's
  `manifest.json` and `.sig` from this repo's GitHub Releases (`releases/latest/download/`, redirects followed; the
  server isn't involved, so updates reach friends while it is down), takes the manifest only when its signature
  verifies against the trusted keys (the built-in primary and offline backup, or a primary that a backup-signed
  manifest's `keys.primary` named since, kept in `<data>/keys.json`), refuses one older than the last accepted (an
  emergency rollback ships as a newer version), and keeps the accepted copy under `<data>` for a start with no
  internet; the installer's own copy under `app/` serves a first start offline. A newer `format`, or a
  `minBootstrapVersion` above the shell's version, stops with "Please download the new installer." and the
  built-in download page (exit 67, a dialog with the link for a friend). A newer version is downloaded whole to
  `versions/<v>.downloading/`, each file checked against its SHA-256 and size, moved into `versions/<v>/` (moves
  tried five times, half a second apart, for a file antivirus holds), and only then does the `current` pointer
  switch, written atomically. Before each launch the chosen version is hashed again against the signed manifest
  kept beside it; a broken pointer or file falls back to the newest version that verifies, and when none does, to
  the shell itself, which also runs when it is the newest. The accepted pair is written whole under `.new` names
  and moved into place, a stop half way finished at the next start, and a pair that is no use anyway is stood in
  for by the newest verified version's own kept manifest. The chosen version runs in the same JVM
  (`VersionLoader`: the jars its signed manifest names, nothing else under `lib/`, on a class loader over the
  platform loader, `Main.run(String[], PrintStream)` by reflection, the one contract between a shell and every
  later version), handed the shell's arguments plus `--manifest <accepted>` and `--healthy <marker>`. The shell
  writes `.started` (naming its process, so a second launcher opened meanwhile sees a start under way, not a
  crash) before the hand-off and the version writes `.healthy` once its window is up or its steps under way; a
  version started and never healthy at the next run (a crash), or not healthy within the watchdog's sixty seconds
  (a hang), or that can't load at all, goes on `<data>/bad-versions.txt`: never run or downloaded again, until a
  newer version is released. GitHub is given twenty seconds for each whole answer, headers and body. Until the
  release PR ships the public keys, `TrustedKeys` is empty and a run without `--manifest` says so and stops. The
  tests run the updater against a fake GitHub with throwaway keys, one per failure: a tampered manifest, an older
  one, a crash or a hang before healthy, a bad version offered again, a corrupt pointer, a 404, a missing jar, a
  held file, the backup key naming a new primary, and an unsigned "please reinstall"; the loader runs ASM-built
  fake versions, and `Main` is seen handing off to one.
- The installed layout and the test VM: `installDist` (part of `build`) lays the launcher out the way it is run,
  `build/install/odinsgate-launcher/bin/odinsgate-launcher(.bat)` over `lib/*.jar`, and CI uploads that folder as
  the `odinsgate-launcher` artifact. The test VM runs it from `C:\RSPS\launcher\app` through the `OdinsGate`
  scheduled task (`test-runner/launch/OdinsGate.cmd`: `--auto --dev`, data under `C:\RSPS\launcher`, the status
  file and a manifest `write-dev-manifest.ps1` makes from the live server), and `start-client.ps1 -Launcher
  [-LauncherVersion 0.1.0]` brings up a logged-in client through it by waiting on the status file; see
  `test-runner/launch/README.md`. The installer PRs wrap the same `lib/` folder with a bundled runtime.

The spike that proved the patching route on the real jars was PR #113, closed unmerged as planned: nothing of
RuneLite's or Jagex's enters this repo, in git or in a release.

## Releases

A release is a merge to `main` that changes `launcher/version.txt` (`build.gradle` reads it, so the jar's version is
the file's). `.github/workflows/release.yml` then builds the jars and writes the manifest with the launcher's own
release tool, `net.odinsgate.launcher.Release`: every jar under `lib/` with its SHA-256 and size, the pin table from
`src/main/resources/runelite-pins.json`, the server (`odinsgate.net`, 43594 and 43595) with the modulus from
`src/main/resources/rsa-modulus.txt`, and `minBootstrapVersion` from `launcher/min-bootstrap-version.txt`, the oldest
installed shell that can still run the release (bumped only when the hand-off between shell and version changes).
The `publish` job, which only a push to `main` or a run by hand without `dry_run` reaches, signs the manifest with
the key in the `release` Environment, checks the signature against the keys built into `TrustedKeys` (a key no
installed launcher trusts publishes nothing), makes a draft release `v<version>`, uploads the jars, then the manifest
and its `.sig` last, and only then publishes it as the latest: `releases/latest/download/manifest.json` is what
every installed launcher fetches (`Updater`), and `releases/download/v<version>/<jar>` is where each file is.

- Dry runs: a pull request that touches the release path (`version.txt`, `min-bootstrap-version.txt`,
  `build.gradle`, `Release.java`, the workflow), and **Run workflow** with `dry_run` on, do the same build, sign with
  a throwaway key made in the run, verify, and publish nothing; the files are the run's `release-files` artifact.
- CI's `launcher-version` job (`.github/scripts/launcher-version.js`, its tests beside it) fails a pull request that
  changes the pin table or the modulus without bumping `version.txt` (the signed manifest carries them, so nothing
  else would ever get them to a launcher), a bump that isn't newer than the last or is a tag released already, or a
  `min-bootstrap-version.txt` change without a bump or above the version.
- `ReleaseTest` builds a release from a made-up lib folder, signs it with a throwaway key and runs an `Updater`
  against a fake GitHub serving the files under the tool's asset names, so the names the workflow publishes under
  are proven to be the ones the updater asks for. The tool itself refuses a lib folder built from another version
  than the one named, a `minBootstrapVersion` above the version, and anything a launcher's own parser would.
- Sam's setup, once, before the first version bump merges (the keys are made on his own PC, never in a session, a
  container or a VM, and never written anywhere but the password manager and the Environment):
  1. `openssl genpkey -algorithm ed25519 -out release-primary.pem`, and the same again for `release-backup.pem`,
     which stays offline in the password manager. Each one's public half, for `TrustedKeys`:
     `openssl pkey -in release-primary.pem -pubout -outform DER | base64 -w0` (plain `base64` on a Mac).
  2. Repository settings, Environments: `release`, deployment branches `main` only, Sam as required reviewer (one
     click per release), and the secret `RELEASE_SIGNING_KEY` holding the whole text of `release-primary.pem`. The
     workflow names the Environment, so GitHub would make an unprotected one at the first release if it isn't there.
  3. A pull request that puts both public halves into `TrustedKeys.SPKI_BASE64` (primary first, backup last) and
     bumps `version.txt`: its merge is the first release. Until then every real run stops at `verify` with "no
     release keys are built into this launcher yet", and the test VM keeps running from `--manifest`.
  4. `.github/CODEOWNERS` names Sam for `.github/workflows/`, so a change to the release path asks for his review.
- A release that turns out broken ships again as a new, higher version: the updater never downgrades. A primary key
  lost or leaked: a manifest signed by the backup key names a new primary in `keys.primary`, and the next release's
  jars carry it in `TrustedKeys` (see `Updater`).
