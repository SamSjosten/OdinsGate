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
  game off). Each endpoint
  is logged once as `[guard] allowed host:port` or `[guard] refused scheme://host:port`, never a path. The tests
  connect through an installed guard to a loopback socket, a refused socket, a refused URL and a refused
  `java.net.http` request and check the count. Its limit: NIO `SocketChannel` and a native library's own sockets
  never ask the selector. The client connects with `Socket` and RuneLite's HTTP goes through OkHttp, which both
  do; the jars are not yet scanned for `SocketChannel` use (a `Preflight` check for it is a follow-up).
- `Main` and `LauncherRun`: the launcher's entry point (the jar's `Main-Class`) and the headless run it makes for
  now, the sequence the window will drive step by step later: check the server (hints only; each outcome has its
  own exit code, 10-14), fetch the pinned RuneLite into `<data>/runelite/<commit>/` (exit 20 when it can't), plan
  the launch, start the game's child JVM with its output in `<data>/logs/game.log` (exit 21 when it can't), wait,
  and exit with the game's own code. Every state goes to the log and, with `--status-file`, the status file. Until
  the release PR ships the signed manifest, `--manifest <file>` names a local one, trusted as it is.
- The installed layout and the test VM: `installDist` (part of `build`) lays the launcher out the way it is run,
  `build/install/odinsgate-launcher/bin/odinsgate-launcher(.bat)` over `lib/*.jar`, and CI uploads that folder as
  the `odinsgate-launcher` artifact. The test VM runs it from `C:\RSPS\launcher\app` through the `OdinsGate`
  scheduled task (`test-runner/launch/OdinsGate.cmd`: `--auto --dev`, data under `C:\RSPS\launcher`, the status
  file and a manifest `write-dev-manifest.ps1` makes from the live server), and `start-client.ps1 -Launcher
  [-LauncherVersion 0.1.0]` brings up a logged-in client through it by waiting on the status file; see
  `test-runner/launch/README.md`. The installer PRs wrap the same `lib/` folder with a bundled runtime.

The spike that proved the patching route on the real jars was PR #113, closed unmerged as planned: nothing of
RuneLite's or Jagex's enters this repo, in git or in a release.
