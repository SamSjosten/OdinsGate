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

The spike that proved the patching route on the real jars was PR #113, closed unmerged as planned: nothing of
RuneLite's or Jagex's enters this repo, in git or in a release.
