# AGENTS.md

OdinsGate is the launcher friends install to play on Sam's private game server: Java 21, package `net.odinsgate`, its
own Gradle build under `launcher/` (`./gradlew -p launcher build`). It downloads a pinned RuneLite from RuneLite's
servers, patches it in memory to our server and key, runs it in a child JVM behind a connect guard, and updates itself
from this repository's signed releases. See `README.md` for the layout, `launcher/README.md` for how each piece works
and `CONTRIBUTING.md` for how pull requests move. The game server itself is in a private repository of its own.

## Review guidelines

Review every pull request thoroughly. Read the whole diff and the code it calls, not only the changed lines, and
report every real problem you find with an honest priority, P2 and P3 included. Don't pad a review with style
opinions; do flag anything below that the diff gets wrong.

### The update path and the signed manifest

- The manifest decides what runs. Flag anything that takes a manifest, a jar or a RuneLite file without the check the
  code makes today: the Ed25519 signature against `TrustedKeys`, the SHA-256 and size of every file, the pinned
  bootstrap's hash and RuneLite's own signature, the refusal of an older version. A check made weaker, moved later
  or skipped on an error path is a finding whatever the reason given.
- Plain HTTP from the server (`/status.json`, `/proxy-targets.yaml`) is hints only; flag a decision about what code
  runs that reads it. Updates come from this repository's GitHub Releases alone.
- `TrustedKeys` holds public halves only. Flag any private key, token or secret in code, tests, resources or
  workflows, and any test that needs a real key: tests make throwaway keys.

### What the game may reach

- The child JVM reaches our server and RuneLite's Plugin Hub and static data (`Loader.RUNELITE_ENDPOINTS`, Sam's
  call) and nothing else (`ConnectGuard`, `Loader --allow`). Flag a new endpoint, a way
  around the guard, or a patch (`Preflight`, `ClassPatch`) found by class or method name instead of by bytecode
  shape with an exact count.
- Nothing of RuneLite's or of the game's publisher enters the repository: no jar but the Gradle wrapper's, no client,
  no cache, no gamepack, no bootstrap but the committed revision-241 proof. Flag any of these in a diff, and code
  that would write a RuneLite file anywhere but the launcher's own data folder.

### What a friend sees

- Every name a friend can see (the window, file names, the installers, the download page) is in `Names` and may not
  contain the banned words (`Names.BANNED`); `.github/scripts/friend-page.js` holds the page to it. Flag a new
  friend-visible name outside `Names`.
- Messages to friends are plain words with a next step; flag a stack trace, or an exit code without words, shown to a
  friend.
- Nothing from a friend's machine goes anywhere: flag a log line or a problem report that could carry a password, a
  token, the user name or a path with it (`ProblemReport` masks the data folder and drops such lines).

### Releases

- A release is `launcher/version.txt` changing on `main`. Flag a change to `runelite-pins.json`, `rsa-modulus.txt` or
  `min-bootstrap-version.txt` without a bump, a bump that isn't newer, a `min-bootstrap-version.txt` above the
  version, and a change to the shell-to-version contract (`Main.run`, `VersionLoader`, the started and healthy
  markers) that doesn't raise `min-bootstrap-version.txt`.
- The self-update replaces jars, never the runtime: a runtime change (`package.gradle`'s modules, jpackage's options)
  raises `min-bootstrap-version.txt`, and `SelfTest` checks what it fixes on every built installer.

### Tests

Every change has tests that fail without it: unit tests under `launcher/src/test`, `SelfTest` for anything the
installed launcher does on a friend's machine, and the workflow scripts' `*.test.js`. Flag a feature without tests,
tests that can't fail, and tests that need the network, a display, a real key or wall-clock timing (fakes on loopback
and hand-cranked drivers are the pattern here).

### Code

- Match the surrounding code: tabs, its naming, comment density and idiom. Comments explain why, in full sentences.
- Java 21; no new dependency without its line in `launcher/package/allowed-jars.txt` and its notice in
  `launcher/THIRD-PARTY-NOTICES.txt` (the image scan refuses it otherwise).
- Flag dead code, a public API nothing calls, swallowed exceptions, and `Thread.sleep` or wall-clock time in a test.
