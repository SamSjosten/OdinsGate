// node --test .github/scripts/*.test.js : plays alert.js through its cases against a stand-in for GitHub's API.
const test = require('node:test');
const assert = require('node:assert');
const alert = require('./alert.js');
const { LABEL, marker } = alert;

// Open issues { number, body, labels, pull_request? }; records every write.
function fakeGitHub(issues) {
  const calls = [];
  let next = 100;
  const rest = {
    issues: {
      listForRepo: async params => {
        calls.push(['listForRepo', params]);
        return { data: issues.filter(issue => issue.state !== 'closed' && issue.labels.includes(params.labels)) };
      },
      create: async params => {
        calls.push(['create', params]);
        const made = { number: next++, body: params.body, labels: params.labels, state: 'open' };
        issues.push(made);
        return { data: made };
      },
      update: async params => {
        calls.push(['update', params]);
        const issue = issues.find(candidate => candidate.number === params.issue_number);
        if (params.body !== undefined) issue.body = params.body;
        if (params.state) issue.state = params.state;
        return { data: issue };
      },
      createComment: async params => { calls.push(['createComment', params]); return { data: {} }; },
      createLabel: async params => { calls.push(['createLabel', params]); return { data: {} }; },
    },
  };
  return { rest, paginate: async (fn, params) => (await fn(params)).data, calls, issues };
}

function fakeCore() {
  const core = { failed: null, lines: [] };
  core.setFailed = message => { core.failed = message; };
  core.info = line => core.lines.push(line);
  return core;
}

const context = { repo: { owner: 'sam', repo: 'game' }, serverUrl: 'https://github.com', runId: 42 };
const NOW = new Date('2026-10-29T20:30:00.123Z');
const LATER = new Date('2026-10-29T20:45:00Z');

test('a failing check with no issue open opens one, assigned to the owner, and fails the run', async () => {
  const github = fakeGitHub([]);
  const core = fakeCore();
  const result = await alert({ github, context, core },
    { key: 'probe', title: 'odinsgate.net is down', healthy: false, detail: 'down after 8 tries: refused', now: NOW });
  assert.deepStrictEqual(result, { action: 'opened', number: 100 });
  assert.strictEqual(core.failed, 'down after 8 tries: refused');
  const create = github.calls.find(([name]) => name === 'create')[1];
  assert.strictEqual(create.title, 'odinsgate.net is down');
  assert.deepStrictEqual(create.assignees, ['sam']);
  assert.deepStrictEqual(create.labels, [LABEL]);
  assert.ok(create.body.startsWith(marker('probe')));
  assert.match(create.body, /\*\*Failing since\*\* 2026-10-29T20:30:00Z, \*\*last seen failing\*\* 2026-10-29T20:30:00Z \(1 run, /);
  assert.match(create.body, /actions\/runs\/42\)/);
  assert.match(create.body, /\ndown after 8 tries: refused\n/);
  assert.ok(github.calls.some(([name]) => name === 'createLabel'), 'the label is made first');
  assert.ok(!github.calls.some(([name]) => name === 'createComment'));
});

test('a check that keeps failing brings the open issue up to date and comments nothing', async () => {
  const github = fakeGitHub([]);
  await alert({ github, context, core: fakeCore() },
    { key: 'probe', title: 't', healthy: false, detail: 'refused', now: NOW });
  const core = fakeCore();
  const result = await alert({ github, context, core },
    { key: 'probe', title: 't', healthy: false, detail: 'still refused', now: LATER });
  assert.deepStrictEqual(result, { action: 'updated', number: 100 });
  assert.strictEqual(core.failed, 'still refused');
  assert.strictEqual(github.calls.filter(([name]) => name === 'create').length, 1, 'no second issue');
  assert.ok(!github.calls.some(([name]) => name === 'createComment'), 'no notification while it stays down');
  const body = github.issues[0].body;
  assert.match(body, /\*\*Failing since\*\* 2026-10-29T20:30:00Z, \*\*last seen failing\*\* 2026-10-29T20:45:00Z \(2 runs, /);
  assert.match(body, /<!-- alert-runs:2 -->/);
  assert.match(body, /\nstill refused\n/);
  const third = await alert({ github, context, core: fakeCore() },
    { key: 'probe', title: 't', healthy: false, detail: 'refused', now: LATER });
  assert.strictEqual(third.action, 'updated');
  assert.match(github.issues[0].body, /\(3 runs, /);
});

test('a check that passes again closes the issue with a comment', async () => {
  const github = fakeGitHub([]);
  await alert({ github, context, core: fakeCore() },
    { key: 'probe', title: 't', healthy: false, detail: 'refused', now: NOW });
  const core = fakeCore();
  const result = await alert({ github, context, core },
    { key: 'probe', title: 't', healthy: true, detail: 'the server is up at revision 241', now: LATER });
  assert.deepStrictEqual(result, { action: 'closed', number: 100 });
  assert.strictEqual(core.failed, null);
  const comment = github.calls.find(([name]) => name === 'createComment')[1];
  assert.strictEqual(comment.issue_number, 100);
  assert.match(comment.body, /^Passing again at 2026-10-29T20:45:00Z \(\[run\]\(.*\/actions\/runs\/42\)\): the server is up/);
  const close = github.calls.filter(([name]) => name === 'update').pop()[1];
  assert.strictEqual(close.state, 'closed');
  assert.strictEqual(close.state_reason, 'completed');
  assert.strictEqual(github.issues[0].state, 'closed');
});

test('a passing check with nothing open does nothing', async () => {
  const github = fakeGitHub([]);
  const core = fakeCore();
  const result = await alert({ github, context, core }, { key: 'probe', title: 't', healthy: true, detail: 'up', now: NOW });
  assert.deepStrictEqual(result, { action: 'none' });
  assert.strictEqual(core.failed, null);
  assert.deepStrictEqual(github.calls.map(([name]) => name), ['listForRepo']);
});

test('each check has its own issue, and a pull request with the label is not one', async () => {
  const github = fakeGitHub([
    { number: 7, body: `${marker('pin-health')}\nold`, labels: [LABEL], state: 'open' },
    { number: 8, body: marker('probe'), labels: [LABEL], state: 'open', pull_request: {} },
  ]);
  const result = await alert({ github, context, core: fakeCore() },
    { key: 'probe', title: 'odinsgate.net is down', healthy: false, detail: 'refused', now: NOW });
  assert.deepStrictEqual(result, { action: 'opened', number: 100 }, 'neither the other check\'s issue nor the PR');
  const closed = await alert({ github, context, core: fakeCore() },
    { key: 'pin-health', title: 'p', healthy: true, detail: 'fine', now: NOW });
  assert.deepStrictEqual(closed, { action: 'closed', number: 7 });
});

test('a failing check with no detail still opens a readable issue', async () => {
  const github = fakeGitHub([]);
  const core = fakeCore();
  await alert({ github, context, core }, { key: 'probe', title: 't', healthy: false, detail: '', now: NOW });
  assert.strictEqual(core.failed, 'The check gave no detail; see the run.');
  assert.match(github.issues[0].body, /The check gave no detail; see the run\./);
});

test('a label that exists already is no error', async () => {
  const github = fakeGitHub([]);
  github.rest.issues.createLabel = async () => { const error = new Error('exists'); error.status = 422; throw error; };
  const result = await alert({ github, context, core: fakeCore() },
    { key: 'probe', title: 't', healthy: false, detail: 'refused', now: NOW });
  assert.strictEqual(result.action, 'opened');
});
