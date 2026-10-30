// node --test .github/scripts/*.test.js : the friend page checks on made-up pages, then on the real docs/.
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { check, namesFrom, DOWNLOAD } = require('./friend-page.js');

const NAMES = `public static final Pattern BANNED = Pattern.compile("runescape|osrs|jagex|scape|rsps", Pattern.CASE_INSENSITIVE);
	public static final String WINDOWS_INSTALLER = "OdinsGate-Setup.exe";
	public static final String MAC_INSTALLER_ARM64 = "OdinsGate-mac-arm64.dmg";`;
const names = namesFrom(NAMES);

function page(links, words = 'Download the launcher and press Play.') {
  return `<html><body><p>${words}</p>${links.map(l => `<a href="${l}">x</a>`).join('')}</body></html>`;
}
const INDEX = page([DOWNLOAD + 'OdinsGate-Setup.exe', DOWNLOAD + 'OdinsGate-mac-arm64.dmg', 'mac.html', 'https://runelite.net']);

test('Names.java gives the banned pattern and the installers', () => {
  assert.deepStrictEqual(names.installers, ['OdinsGate-Setup.exe', 'OdinsGate-mac-arm64.dmg']);
  assert.ok(names.banned.test('OldSchoolRuneScape'));
  assert.ok(!names.banned.test('Odin\'s Gate'));
  assert.throws(() => namesFrom('nothing here'), /no BANNED/);
});

test('clean pages pass', () => {
  assert.deepStrictEqual(check({ 'index.html': INDEX, 'mac.html': page(['index.html']), 'style.css': 'body {}' }, names), []);
});

test('a banned word in the text is a problem, but the repo name inside a link is not', () => {
  const problems = check({ 'index.html': page([DOWNLOAD + 'OdinsGate-Setup.exe', DOWNLOAD + 'OdinsGate-mac-arm64.dmg'],
    'Made for the old school scape game') }, names);
  assert.deepStrictEqual(problems, ['index.html: holds the banned word "scape"']);
  assert.deepStrictEqual(check({ 'index.html': INDEX, 'mac.html': page([]), 'style.css': '/* no osrs here */' }, names),
    ['style.css: holds the banned word "osrs"']);
});

test('links must be https, on a known host, and download links must name a known installer', () => {
  const problems = check({ 'index.html': page([DOWNLOAD + 'OdinsGate-Setup.exe', DOWNLOAD + 'OdinsGate-mac-arm64.dmg',
    'http://runelite.net', 'https://example.com/x', DOWNLOAD + 'OdinsGate-other.zip',
    'https://github.com/SamSjosten/OdinsGate/releases']) }, names);
  assert.deepStrictEqual(problems, [
    'index.html: "http://runelite.net" isn\'t https',
    'index.html: "https://example.com/x" is on a host the page shouldn\'t link',
    'index.html: "OdinsGate-other.zip" isn\'t an installer Names.java knows',
    'index.html: "https://github.com/SamSjosten/OdinsGate/releases" points into the repo; friends only get releases/latest/download/ links',
  ]);
});

test('a relative link must be a file in docs/, and a fragment is fine', () => {
  const problems = check({ 'index.html': page([DOWNLOAD + 'OdinsGate-Setup.exe', DOWNLOAD + 'OdinsGate-mac-arm64.dmg',
    'linux.html', 'mac.html#install', '#play']), 'mac.html': page([]) }, names);
  assert.deepStrictEqual(problems, ['index.html: "linux.html" isn\'t a file in docs/']);
});

test('index.html links every installer exactly once', () => {
  assert.deepStrictEqual(check({ 'index.html': page([DOWNLOAD + 'OdinsGate-Setup.exe']) }, names),
    ['index.html links OdinsGate-mac-arm64.dmg 0 time(s), not once']);
  assert.deepStrictEqual(check({ 'index.html': page([DOWNLOAD + 'OdinsGate-Setup.exe', DOWNLOAD + 'OdinsGate-Setup.exe',
    DOWNLOAD + 'OdinsGate-mac-arm64.dmg']) }, names), ['index.html links OdinsGate-Setup.exe 2 time(s), not once']);
  // A guide may link an installer too; only index.html's count is held to one.
  assert.deepStrictEqual(check({ 'index.html': INDEX, 'mac.html': page([DOWNLOAD + 'OdinsGate-mac-arm64.dmg']) }, names), []);
  assert.deepStrictEqual(check({ 'mac.html': page([]) }, names).filter(p => p.includes('index.html'))[0], 'docs/ has no index.html');
});

test('the real docs/ pass against the real Names.java', () => {
  const root = path.join(__dirname, '..', '..');
  const docs = path.join(root, 'docs');
  const files = {};
  for (const entry of fs.readdirSync(docs, { withFileTypes: true })) {
    if (entry.isFile()) files[entry.name] = fs.readFileSync(path.join(docs, entry.name), 'utf8');
  }
  const real = namesFrom(fs.readFileSync(path.join(root, 'launcher/src/main/java/net/odinsgate/launcher/Names.java'), 'utf8'));
  assert.ok(real.installers.length >= 4, 'Windows, two Mac and Linux');
  assert.deepStrictEqual(check(files, real), []);
});
