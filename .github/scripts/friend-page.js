// The friend download page and guides (docs/, served by GitHub Pages; README.md, "Letting friends test"), checked
// by the friend-page job in .github/workflows/build.yml:
//   - no page carries a banned word (the launcher's Names.BANNED, read from Names.java) outside its links, which
//     may name this repo;
//   - every link is https to a known host or a file in docs/; a download link under this repo's
//     releases/latest/download/ names an installer the launcher's Names.java knows;
//   - index.html links every installer exactly once.
//
//   node .github/scripts/friend-page.js [docs dir] [Names.java]
//
// The checks are check() below, on plain data; the command line reads the files and prints each problem.

const fs = require('fs');
const path = require('path');

const DOCS = 'docs';
const NAMES = 'launcher/src/main/java/net/odinsgate/launcher/Names.java';
const DOWNLOAD = 'https://github.com/SamSjosten/OSRSPS/releases/latest/download/';
const HOSTS = ['github.com', 'runelite.net', 'samsjosten.github.io'];

// The banned pattern and the installer names, read from Names.java so the page can't drift from the code.
function namesFrom(javaText) {
  const banned = javaText.match(/BANNED = Pattern\.compile\("([^"]+)"/);
  if (!banned) throw new Error('Names.java has no BANNED pattern');
  const installers = [...javaText.matchAll(/static final String [A-Z0-9_]*INSTALLER[A-Z0-9_]* = "([^"]+)"/g)]
    .map(m => m[1]);
  if (installers.length === 0) throw new Error('Names.java names no installer');
  return { banned: new RegExp(banned[1], 'i'), installers };
}

// The problems with the pages: `files` maps each file name under docs/ to its text, `names` is namesFrom().
function check(files, names) {
  const problems = [];
  const counts = new Map(names.installers.map(name => [name, 0]));
  for (const [file, text] of Object.entries(files)) {
    const words = text.replace(/\b(?:href|src)="[^"]*"/g, '');
    const banned = words.match(names.banned);
    if (banned) problems.push(`${file}: holds the banned word "${banned[0]}"`);
    if (!file.endsWith('.html')) continue;
    for (const [, href] of text.matchAll(/\bhref="([^"]*)"/g)) {
      if (href.startsWith('#')) continue;
      if (/^[a-z]+:/i.test(href)) {
        let url;
        try { url = new URL(href); } catch { problems.push(`${file}: "${href}" isn't a URL`); continue; }
        if (url.protocol !== 'https:') { problems.push(`${file}: "${href}" isn't https`); continue; }
        if (!HOSTS.includes(url.hostname)) { problems.push(`${file}: "${href}" is on a host the page shouldn't link`); continue; }
        if (href.startsWith(DOWNLOAD)) {
          const name = href.slice(DOWNLOAD.length);
          if (!counts.has(name)) problems.push(`${file}: "${name}" isn't an installer Names.java knows`);
          else if (file === 'index.html') counts.set(name, counts.get(name) + 1);
        } else if (href.startsWith('https://github.com/SamSjosten/OSRSPS/')) {
          problems.push(`${file}: "${href}" points into the repo; friends only get releases/latest/download/ links`);
        }
        continue;
      }
      const target = href.split('#')[0];
      if (!(target in files)) problems.push(`${file}: "${href}" isn't a file in docs/`);
    }
  }
  if (!('index.html' in files)) problems.push('docs/ has no index.html');
  for (const [name, count] of counts) {
    if (count !== 1) problems.push(`index.html links ${name} ${count} time(s), not once`);
  }
  return problems;
}

function main(argv) {
  const docs = argv[0] || DOCS;
  const namesFile = argv[1] || NAMES;
  const files = {};
  for (const entry of fs.readdirSync(docs, { withFileTypes: true })) {
    if (entry.isFile()) files[entry.name] = fs.readFileSync(path.join(docs, entry.name), 'utf8');
  }
  const problems = check(files, namesFrom(fs.readFileSync(namesFile, 'utf8')));
  for (const problem of problems) console.log(problem);
  if (problems.length > 0) {
    console.log(`${problems.length} problem(s) with the friend page`);
    return 1;
  }
  console.log(`friend page: ${Object.keys(files).length} file(s) in ${docs} checked, all links and words fine`);
  return 0;
}

module.exports = { check, namesFrom, DOWNLOAD, HOSTS };

if (require.main === module) process.exit(main(process.argv.slice(2)));
