// The launcher's version file against a pull request's changes, run by the launcher-version job in
// .github/workflows/build.yml (launcher/README.md, "Releases"): a merge to main that changes launcher/version.txt
// publishes a release, and the signed manifest carries the RuneLite pin table and the server's modulus, so
//   - a change to launcher/runelite-pins.json or launcher/rsa-modulus.txt must bump launcher/version.txt, or no
//     installed launcher would ever get it;
//   - a bump must be dotted digits, newer than the version before it, and not a tag that is released already;
//   - launcher/min-bootstrap-version.txt can only change along with a bump, and never above the version.
//
//   node .github/scripts/launcher-version.js <base> <head>    e.g. origin/main HEAD, in a checkout that has both
//
// The checks are check() below, on plain data; the command line reads git and prints each problem.

const VERSION = 'launcher/version.txt';
const MIN_BOOTSTRAP = 'launcher/min-bootstrap-version.txt';
const SIGNED = ['launcher/runelite-pins.json', 'launcher/rsa-modulus.txt'];
const DOTTED = /^\d+(\.\d+)*$/;

// Dotted versions by number, a missing part being 0 (0.2 is 0.2.0), the way the launcher's Versions.compare does.
function compare(a, b) {
  const as = a.split('.').map(Number);
  const bs = b.split('.').map(Number);
  for (let i = 0; i < Math.max(as.length, bs.length); i++) {
    const d = (as[i] || 0) - (bs[i] || 0);
    if (d !== 0) return d;
  }
  return 0;
}

// The problems with a change: `changed` the paths it touches, `before`/`after` the version file's text at the base
// and the head (null when missing), `minBefore`/`minAfter` the same for the min-bootstrap file, `tags` the tags that
// exist on origin (v0.1.0, ...). An empty list means the change is fine.
function check({ changed, before, after, minBefore, minAfter, tags }) {
  const problems = [];
  const trim = text => (text === null || text === undefined ? null : text.trim());
  const version = trim(after);
  const wasVersion = trim(before);
  const min = trim(minAfter);
  const bumped = changed.includes(VERSION) && version !== wasVersion;
  const signed = SIGNED.filter(path => changed.includes(path));
  if (signed.length && !bumped) {
    problems.push(`${signed.join(' and ')} changed, but ${VERSION} didn't: the signed manifest carries them, so `
      + 'bump the launcher version to release them');
  }
  if (changed.includes(MIN_BOOTSTRAP) && trim(minBefore) !== min && !bumped) {
    problems.push(`${MIN_BOOTSTRAP} changed, but ${VERSION} didn't: it only reaches launchers with a release`);
  }
  if (bumped) {
    if (!DOTTED.test(version)) {
      problems.push(`${VERSION} says "${version}", not dotted digits such as 0.2.0`);
    } else {
      if (wasVersion !== null && DOTTED.test(wasVersion) && compare(version, wasVersion) <= 0) {
        problems.push(`${VERSION} goes from ${wasVersion} to ${version}: a release must be newer than the last`);
      }
      if (tags.includes(`v${version}`)) {
        problems.push(`v${version} is released already; ${VERSION} must name a version that isn't`);
      }
      if (min !== null && DOTTED.test(min) && compare(min, version) > 0) {
        problems.push(`${MIN_BOOTSTRAP} says ${min}, above the version ${version}: no installed launcher could run it`);
      }
    }
  }
  return problems;
}

function main(base, head) {
  const { execFileSync } = require('node:child_process');
  // git's own complaint about a file a ref doesn't have (the first version file ever) is no part of the verdict.
  const git = (...args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  const show = (ref, path) => {
    try { return git('show', `${ref}:${path}`); } catch (e) { return null; }
  };
  const changed = git('diff', '--name-only', `${base}...${head}`).split('\n').filter(Boolean);
  let tags = [];
  try {
    tags = git('ls-remote', '--tags', 'origin', 'refs/tags/v*').split('\n')
      .map(line => line.split('\t')[1]).filter(Boolean)
      .map(ref => ref.replace('refs/tags/', '').replace(/\^\{\}$/, ''));
  } catch (e) {
    // The release workflow refuses a released version again before publishing, so this check is not the last word.
    const why = e.message.split('\n')[0];
    console.log(`WARN the tags on origin couldn't be listed (${why}); a released version isn't checked for`);
  }
  const problems = check({
    changed, before: show(base, VERSION), after: show(head, VERSION),
    minBefore: show(base, MIN_BOOTSTRAP), minAfter: show(head, MIN_BOOTSTRAP), tags,
  });
  for (const problem of problems) console.log(`FAIL ${problem}`);
  if (problems.length) process.exit(1);
  const version = (show(head, VERSION) || '?').trim();
  console.log(`launcher version ${version}: ok${changed.includes(VERSION) ? ' (a release once this merges)' : ''}`);
}

module.exports = { check, compare };

if (require.main === module) {
  if (process.argv.length !== 4) {
    console.log('usage: node launcher-version.js <base> <head>');
    process.exit(2);
  }
  main(process.argv[2], process.argv[3]);
}
