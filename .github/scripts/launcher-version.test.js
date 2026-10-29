// node --test .github/scripts/*.test.js : the launcher version check on plain data.
const { test } = require('node:test');
const assert = require('node:assert');
const { check, compare } = require('./launcher-version.js');

const PINS = 'src/main/resources/runelite-pins.json';
const MODULUS = 'src/main/resources/rsa-modulus.txt';
const TAGS = ['v0.1.0', 'v0.1.1'];
const same = { before: '0.1.1\n', after: '0.1.1\n', minBefore: '0.1.0\n', minAfter: '0.1.0\n', tags: TAGS };

test('a change elsewhere needs no bump', () => {
  assert.deepStrictEqual(check({ ...same, changed: ['launcher/src/main/java/X.java', 'README.md'] }), []);
});

test('the pin table or the modulus changing without a bump is a problem', () => {
  const [problem] = check({ ...same, changed: [PINS] });
  assert.match(problem, /runelite-pins\.json changed, but launcher\/version\.txt didn't/);
  assert.strictEqual(check({ ...same, changed: [PINS, MODULUS] }).length, 1);
  assert.match(check({ ...same, changed: [MODULUS] })[0], /rsa-modulus\.txt changed/);
});

test('the pin table changing with a bump is fine', () => {
  assert.deepStrictEqual(check({ ...same, changed: [PINS, 'launcher/version.txt'], after: '0.2.0\n' }), []);
});

test('a bump must be newer than the version before and not released yet', () => {
  assert.match(check({ ...same, changed: ['launcher/version.txt'], after: '0.1.0\n' })[0],
    /goes from 0\.1\.1 to 0\.1\.0/);
  assert.match(check({ ...same, changed: ['launcher/version.txt'], after: '0.1.1\n', before: '0.1.0\n' })[0],
    /v0\.1\.1 is released already/);
  assert.deepStrictEqual(check({ ...same, changed: ['launcher/version.txt'], after: '0.2\n' }), [],
    '0.2 is 0.2.0, newer');
  assert.deepStrictEqual(check({ ...same, changed: ['launcher/version.txt'], after: '1.0.0\n' }), []);
});

test('a bump must be dotted digits', () => {
  assert.match(check({ ...same, changed: ['launcher/version.txt'], after: '0.2.0-beta\n' })[0], /not dotted digits/);
  assert.match(check({ ...same, changed: ['launcher/version.txt'], after: 'dev\n' })[0], /not dotted digits/);
});

test('the version file touched without changing its version is no bump', () => {
  const [problem] = check({ ...same, changed: ['launcher/version.txt', PINS], after: '0.1.1  \n' });
  assert.match(problem, /changed, but launcher\/version\.txt didn't/);
});

test('the min-bootstrap file changes only with a bump, and never above the version', () => {
  assert.match(check({ ...same, changed: ['launcher/min-bootstrap-version.txt'], minAfter: '0.1.1\n' })[0],
    /min-bootstrap-version\.txt changed, but launcher\/version\.txt didn't/);
  assert.deepStrictEqual(check({ ...same, changed: ['launcher/min-bootstrap-version.txt', 'launcher/version.txt'],
    minAfter: '0.2.0\n', after: '0.2.0\n' }), []);
  assert.match(check({ ...same, changed: ['launcher/min-bootstrap-version.txt', 'launcher/version.txt'],
    minAfter: '0.3.0\n', after: '0.2.0\n' })[0], /above the version 0\.2\.0/);
});

test('the first version file ever, with nothing before it, is a bump that only has to be well formed', () => {
  assert.deepStrictEqual(check({ changed: ['launcher/version.txt'], before: null, after: '0.1.0\n', minBefore: null,
    minAfter: '0.1.0\n', tags: [] }), []);
});

test('compare reads dotted versions by number', () => {
  assert.strictEqual(compare('0.10.0', '0.9.9') > 0, true);
  assert.strictEqual(compare('0.2', '0.2.0'), 0);
  assert.strictEqual(compare('1.0', '0.99.99') > 0, true);
});
